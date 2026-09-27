# Notas de implementación

Registro de diferencias entre la propuesta inicial (`docs/architecture-proposal.md`) y la implementación, evidencia nueva, deuda técnica consciente y riesgos residuales. Se actualiza en cada paso del plan incremental.

## Diferencias con la propuesta

| # | Propuesta | Implementación | Motivo |
|---|---|---|---|
| D1 | Verificar la independencia del dominio con ArchUnit (sección 4.1). | `DomainIndependenceTest` revisa los imports del código fuente del dominio. | Cubre la regla completa sin agregar una dependencia. ArchUnit sigue siendo el reemplazo natural si las reglas de arquitectura crecen (p. ej. dependencias entre application e infrastructure). |
| D2 | Códigos de rechazo: `CLIENT_NOT_FOUND`, `CLIENT_NOT_ACTIVE`, `CLIENT_MARKET_MISMATCH`, `PRODUCT_NOT_FOUND`, `PRODUCT_NOT_ACTIVE`. | Se agrega `PRODUCT_TAX_CATEGORY_UNKNOWN`. | Un producto con una categoría fiscal que el servicio no conoce no puede gravarse correctamente. Aplicar una tasa supuesta cobraría mal. Es un cambio aditivo, compatible con `orders.processed.v1`. |
| D3 | La propuesta no fijaba cómo encadenar redondeos. | Cada importe se redondea al calcularse y el paso siguiente usa el valor redondeado. | Aplicación literal de "redondear cada importe monetario a dos decimales". Con esto `netSubtotal` y `lineTotal` son exactos. Cubierto por tests de redondeo. |
| D4 | La propuesta no definía los totales de un pedido rechazado. | `totals` es `null` cuando el pedido es `REJECTED` (decidido en los contratos, paso 0). | Si el cliente no es elegible no se consultan productos y no hay categoría fiscal para calcular impuestos. Publicar totales parciales invitaría a usarlos. |
| D5 | products-api con capa de servicio entre handler y catálogo (implícito). | El handler depende directamente de la interfaz `Catalog`. | No hay reglas de negocio que justifiquen la capa; sería una abstracción sin problema que resolver. |
| D6 | Tests del adaptador HTTP con WireMock (sección 10). | Servidor falso sobre `com.sun.net.httpserver.HttpServer` del JDK (`StubCatalogServer`). | Los escenarios necesarios (respuestas en cola por ruta, retrasos, headers) caben en ~150 líneas sin otra dependencia ni su configuración. WireMock sigue siendo la opción si hacen falta escenarios con estado más ricos. |
| D7 | `order_events` como registro inmutable, un documento por recepción. | Un documento por `eventId`: `outcome` guarda el primer resultado (`$setOnInsert`) y `lastOutcome`/`deliveries` las entregas posteriores. | Una reentrega masiva no multiplica documentos, y la pregunta habitual ("¿llegó este evento y qué pasó?") se responde con una lectura por `_id`. |
| D8 | ADR-002: el estado final de `delivery_attempts` se actualiza dentro de la transacción del resultado. | Se actualiza justo después del commit, desde el adaptador de Kafka. | El puerto `OrderResultStore` no debe conocer offsets de Kafka. Si el proceso cae entre el commit y la marca `DONE`, la reentrega cuenta una caída de más, pero el pre-chequeo la descarta como `DUPLICATE`: nunca hay doble efecto. |
| D9 | ADR-002: el relay reclama un lote de entradas con un *lease*. | Reclama de a una (`findOneAndUpdate` ordenado por `createdAt`) y corta el lote en el primer fallo. | Mantiene el orden de publicación sin lógica adicional. Con 50 mensajes por ciclo cada 500 ms sobra capacidad para el volumen esperado; reclamar en bloque es la optimización si el outbox se vuelve cuello de botella. |
| D10 | La propuesta no fijaba cómo tratar `24.0` en campos enteros. | `quantity` y `eventVersion` deben ser enteros JSON: `24.0` se rechaza como `INVALID_FIELD_TYPE`. | JSON Schema 2020-12 acepta `24.0` como entero; el consumidor es más estricto a propósito para no truncar valores fraccionarios en silencio. Si un productor legítimo envía `24.0`, se relaja en el parser sin tocar el dominio. |
| D11 | Test de caída y reentrega con un hook en el caso de uso (sección 10). | La detección de mensajes veneno se prueba sobre `DeliveryGuard` con MongoDB real; la reentrega tras caída se cubre con los tests de duplicados. | Un hook de test en el caso de uso ensucia código productivo. Queda como deuda un test que mate el contenedor del worker (ver abajo). |
| D12 | La tabla de ownership (§8.1) cita `contracts/events/orders.processing.dlt.md`. | El contrato de la DLT es `orders.processing.dlt.headers.schema.json`. | Un JSON Schema de los headers se puede validar en tests (`DeadLetterHeadersTest`); un documento Markdown no. |
| D13 | La propuesta no incluía documentos de operación ni la vista de plataforma completa. | Se agregan `platform-architecture.md` y `runbook.md` después de implementar. | La propuesta se mantiene como se escribió antes de programar; estos documentos la complementan con lo aprendido (p. ej., el tiempo máximo por pedido ante APIs caídas). |

## Decisiones de implementación de order-processor

| Decisión | Motivo |
|---|---|
| Driver síncrono de MongoDB, sin Spring Data | La consistencia depende de tres operaciones concretas (`findOneAndReplace` condicional con upsert, `insertOne` y `withTransaction`). Con el driver se ven tal cual en el código; un repositorio de Spring Data las escondería detrás de convenciones. |
| Reintentos HTTP propios (`HttpRetrier`, ~80 líneas) en lugar de Resilience4j | Solo se necesita retry con backoff, jitter y `Retry-After`. Un circuit breaker aporta poco con un único consumidor por partición y reintentos acotados; se agregaría si varias instancias saturan una API caída. |
| `RestClient` con `JdkClientHttpRequestFactory` | Cliente del ecosistema Spring, bloqueante, natural con virtual threads; timeouts de conexión y lectura sin dependencias extra. |
| Los productos se consultan en paralelo (virtual threads, máximo 8 simultáneos) | Un pedido con 20 líneas no suma 20 latencias. El semáforo protege a products-api. |
| El `eventId` viaja como `X-Request-Id` | Un mismo identificador une los logs de las tres aplicaciones. Ambas APIs ignoran un valor fuera de patrón y generan uno propio. |
| El esquema e índices se crean al arrancar (`MongoSchema`), con los mismos nombres que `infra/mongo/init-indexes.js` | La unicidad de `orders.eventId` es parte de la corrección, no una optimización: el servicio no puede depender de que alguien haya corrido un script. El arranque también falla si MongoDB no es replica set (ADR-002). |
| `TECHNICAL_FAILURE` llega a la DLT por el outbox, no por envío directo | Queda registrado junto con el resultado en la misma transacción: no puede existir un fallo técnico persistido sin su mensaje en la DLT. |

## Evidencia nueva y restricciones encontradas

- **NestJS 12 y TypeScript 7 ya existen.** clients-api usa NestJS 11 y TypeScript 5.9: `ts-jest` no soporta TypeScript 7 y NestJS 11 tiene un comportamiento conocido y verificado. Actualizar es un cambio independiente.
- **Express 5 tipa los parámetros de ruta como `string | string[]`.** El modo estricto de TypeScript lo detectó en clients-api; se maneja explícitamente.
- **Los tests detectaron un bug real en clients-api:** el repositorio en memoria lanzaba la cancelación de forma síncrona en lugar de devolver una `Promise` rechazada. Se corrigió haciendo el método `async`.
- **Spring Boot 3.5.15** es la última versión 3.x (el enunciado exige 3.x; Spring Boot 4 queda fuera de alcance).
- **Firmas verificadas contra el código fuente** de las versiones que gestiona Spring Boot 3.5.15: Spring Framework 6.2.19, Spring Kafka 3.3.16, driver de MongoDB 5.5.2, Micrometer 1.15.12, Testcontainers 1.21.4 y Kafka 3.9.2.
- **Boot conecta solo el `DefaultErrorHandler`** a los listeners si existe como bean, y con `spring.threads.virtual.enabled` los listeners corren en virtual threads. No hizo falta definir una fábrica de contenedores propia.
- **`assertThat(org.bson.Document)` resuelve a `MapAssert`** porque `Document` implementa `Map`: una aserción con `satisfies` sobre un documento no compila. Detectado al compilar los tests de integración.
- **Primera ejecución completa con Docker Compose:** las tres imágenes compilaron sin errores. En order-processor, `mvn -DskipTests package` compila también el código de test, así que es la primera confirmación de que código principal y tests compilan contra las librerías reales. Kafka, MongoDB, products-api y clients-api quedaron `healthy`.
- **Puertos reservados por Windows:** order-processor no pudo publicar el puerto 8083 (*"Intento de acceso a un socket no permitido por sus permisos de acceso"*). No lo usaba otro programa: Hyper-V/WSL2 reserva rangos de puertos al arrancar. Se resolvió cambiando `ORDER_PROCESSOR_PORT` en `.env`; se documentó en el README. El valor por defecto se mantiene en 8083 porque el rango reservado varía en cada máquina.
- **Git Bash convierte rutas en los argumentos:** `/opt/kafka/bin/...` llegaba a Docker como una ruta de Windows. Los scripts de `infra/scripts` exportan `MSYS_NO_PATHCONV=1`.

## Estado de verificación

| Verificación | Estado |
|---|---|
| Tests unitarios de order-processor (`./mvnw test`) | ✅ |
| Tests de integración de order-processor (`./mvnw verify`) | ⬜ completar con el resultado |
| Tests de products-api y clients-api, validación de contratos | ✅ |
| Compilación de las tres imágenes Docker (incluye la compilación de los tests de order-processor) | ✅ |
| `docker compose up -d --build` con los tres servicios `healthy` | ⬜ Kafka, MongoDB y las APIs `healthy`; order-processor pendiente de confirmar tras cambiar el puerto |
| Prueba manual del README: aprobado, duplicado, inválido, `TECHNICAL_FAILURE` | ⬜ completar con el resultado |
| Caída de MongoDB y de Kafka durante el procesamiento | ⬜ completar con el resultado |
| Pipeline de CI en GitHub | ⬜ completar con el resultado |

## Deuda técnica consciente

- clients-api no tiene ESLint configurado; la calidad de tipos se apoya en `strict` y el resto de `tsconfig.json`.
- Las APIs no tienen rate limiting real: el 429 solo existe por inyección de fallos.
- `DeliveryGuard.begin` lee y luego escribe (no es atómico). Es suficiente porque Kafka entrega cada partición a un solo consumidor a la vez; durante un rebalanceo dos consumidores podrían contar la misma caída dos veces, lo que solo adelanta el envío a la DLT.
- No hay test que mate el proceso a mitad de un mensaje (D11). El siguiente paso sería un IT que detenga el contenedor de la aplicación con Testcontainers.
- El relay del outbox solo marca `SENT`; no hay alerta automática sobre `outbox.oldest_pending.age.seconds`, solo la métrica. Los umbrales propuestos están en `runbook.md`.
- El CI no detecta todavía cambios incompatibles en los contratos (`oasdiff` y diff de JSON Schema contra `main`); hoy solo valida que esquemas y ejemplos sean coherentes.
- Las fases de sombra y de despliegue por mercado de `technical-leadership.md` necesitan dos configuraciones que no existen: desactivar la publicación y filtrar por mercado.
- `order_events` no tiene TTL (riesgo R10 de la propuesta).

## Funcionalidades no terminadas

- Opcionales del enunciado (paso 12): `GET /orders/{orderId}`, caché de productos, contract tests automatizados en CI, schema registry, prueba de carga, Flutter.

## Riesgos residuales

- La interpretación de `eventVersion` como revisión del pedido (supuesto S1) sigue sin validar con el dueño del contrato de entrada.
- Un error de persistencia sostenido detiene la partición hasta 10 minutos antes de enviar el mensaje a la DLT. Es intencional (no se pierden ni reordenan pedidos), pero una caída larga de MongoDB se traduce en lag creciente: la alerta debe ser sobre el lag del consumidor, no sobre la DLT.
- Si Kafka está caído mucho tiempo, el outbox crece sin límite; lo mitiga la métrica de antigüedad del pendiente más viejo.

## Siguiente cambio con más tiempo

1. Un test de integración que detenga el worker a mitad de un mensaje y compruebe que al reiniciar el pedido se procesa una sola vez.
2. Detección de cambios incompatibles de contratos en CI.
3. `GET /orders/{orderId}` de solo lectura, que habilita la extensión Flutter y simplifica el soporte.
