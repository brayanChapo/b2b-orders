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

## Evidencia nueva y restricciones encontradas

- **NestJS 12 y TypeScript 7 ya existen.** clients-api usa NestJS 11 y TypeScript 5.9: `ts-jest` no soporta TypeScript 7 y NestJS 11 tiene un comportamiento conocido y verificado. Actualizar es un cambio independiente.
- **Express 5 tipa los parámetros de ruta como `string | string[]`.** El modo estricto de TypeScript lo detectó en clients-api; se maneja explícitamente.
- **Los tests detectaron un bug real en clients-api:** el repositorio en memoria lanzaba la cancelación de forma síncrona en lugar de devolver una `Promise` rechazada. Se corrigió haciendo el método `async`.
- **Spring Boot 3.5.15** es la última versión 3.x (el enunciado exige 3.x; Spring Boot 4 queda fuera de alcance).

## Deuda técnica consciente

- clients-api no tiene ESLint configurado; la calidad de tipos se apoya en `strict` y el resto de `tsconfig.json`.
- Las APIs no tienen rate limiting real: el 429 solo existe por inyección de fallos.

## Funcionalidades no terminadas

- order-processor: casos de uso, adaptadores HTTP, persistencia, consumidor Kafka, outbox y DLT (pasos 5 a 9).

## Riesgos residuales

- La interpretación de `eventVersion` como revisión del pedido (supuesto S1) sigue sin validar con el dueño del contrato de entrada.
