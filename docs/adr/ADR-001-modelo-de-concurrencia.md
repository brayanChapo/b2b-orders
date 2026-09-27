# ADR-001 — Modelo de concurrencia y procesamiento de `order-processor`

| Campo | Valor |
|---|---|
| Estado | Aceptado |
| Fecha | 2026-09-26 |
| Relacionado con | `docs/architecture-proposal.md` (secciones 5.3, 5.6 y 6.4), ADR-002 |

## Contexto

`order-processor` consume `orders.created.v1` y, por cada pedido, hace una llamada a `clients-api` y N llamadas a `products-api` (una por línea, hasta ~200). El tiempo de procesamiento está dominado por **E/S** (HTTP y MongoDB), no por CPU: el cálculo de impuestos y descuentos es trivial.

Restricciones que condicionan la decisión:

- **Orden por pedido.** Los eventos de un mismo `orderId` deben procesarse en el orden en que llegan a su partición; si no, aumentan los casos `STALE` y `CONFLICT`.
- **Commit de offset seguro.** El offset solo puede confirmarse cuando el registro terminó (ver ADR-002). Cualquier modelo que procese registros de una misma partición en paralelo complica saber qué offset es seguro confirmar.
- **Volumen supuesto moderado** (S11): decenas de pedidos por segundo.
- **Equipo mixto** (Java, Go, TypeScript): el código del worker debe ser fácil de leer, depurar y revisar por cualquiera.
- **Java 21+ y Spring Boot 3.x** son requisitos.

## Alternativas consideradas

| # | Alternativa | A favor | En contra |
|---|---|---|---|
| A | **Reactivo** (WebFlux + cliente reactivo de Kafka + driver reactivo de Mongo) | Máximo aprovechamiento de E/S con pocos hilos. | Curva de aprendizaje alta; stack traces difíciles; todo el stack debe ser no bloqueante; el ecosistema de Kafka reactivo en Spring tiene un futuro de mantenimiento incierto. Complejidad sin un problema de escala que la justifique. |
| B | **Hilos de plataforma** con pool fijo y código bloqueante | Modelo conocido y estable. | Hay que dimensionar pools para la E/S; las consultas paralelas de productos consumen hilos caros; más ajustes manuales. |
| C | **Virtual threads** con código bloqueante | Código imperativo simple; la E/S bloqueante es barata; paralelizar consultas de productos no requiere dimensionar pools. | En Java 21, un bloque `synchronized` que hace E/S "fija" el hilo portador (*pinning*) y reduce la ventaja; necesita observación. |
| D | **Procesamiento paralelo dentro de una partición** con orden por key (p. ej. Confluent Parallel Consumer) | Más rendimiento que el número de particiones. | Dependencia extra; gestión de offsets más compleja; resuelve un problema de escala que no tenemos. |
| E | **Listener por lotes** (`batch listener`) | Menos overhead por registro. | Un fallo en un registro complica el commit del lote; mezcla resultados de pedidos distintos en una misma unidad de trabajo. |

## Decisión

**Alternativa C: virtual threads con código bloqueante y procesamiento secuencial por partición.**

1. `spring.threads.virtual.enabled=true`. Spring Boot usa virtual threads para los contenedores de listeners y el trabajo asíncrono.
2. **Concurrencia del listener = número de particiones** de `orders.created.v1` (3 en local). Cada consumidor procesa sus registros **uno por uno**, lo que preserva el orden por `orderId` y hace trivial el commit.
3. `enable.auto.commit=false` y `AckMode.RECORD`: se confirma el offset de cada registro al terminar su procesamiento.
4. **Paralelismo solo dentro de un pedido**: las consultas de productos se lanzan en paralelo con `Executors.newVirtualThreadPerTaskExecutor()`, acotadas por un `Semaphore` de 8 permisos para no saturar `products-api`. No se usa `StructuredTaskScope` porque en Java 21 es una API *preview*.
5. **Presupuesto por pedido de 10 s** (sección 5.3 de la propuesta), para acotar cuánto puede bloquearse una partición.
6. **Coherencia con el poll de Kafka**: `max.poll.records=10` y `max.poll.interval.ms=300000`. En el peor caso, 10 registros × 10 s = 100 s, muy por debajo de los 300 s tras los cuales Kafka expulsaría al consumidor del grupo.
7. **Apagado controlado** y **contador de entregas** según la sección 5.6 de la propuesta.

La escalabilidad se obtiene **aumentando particiones e instancias**, no aumentando paralelismo dentro de una partición.

## Consecuencias

**Positivas**

- Código imperativo, legible y fácil de depurar por todo el equipo.
- El orden por `orderId` y la política de offsets quedan triviales de razonar y de probar.
- La latencia de un pedido con muchas líneas se reduce gracias al paralelismo interno sin configurar pools.

**Negativas y aceptadas**

- **Techo de rendimiento** = particiones × (1 / latencia por pedido). Con 3 particiones y ~50 ms por pedido, unos 60 pedidos/s por grupo, suficiente para S11. Superar ese techo exige más particiones, y **aumentar particiones cambia la asignación de keys**: los eventos en vuelo de un mismo pedido podrían quedar en particiones distintas durante la transición. La escritura condicional (ADR-002 y sección 6 de la propuesta) mantiene la corrección, pero puede haber más `STALE`.
- **Bloqueo de cabeza de fila**: un pedido con reintentos detiene su partición hasta 10 s. Se mitiga con el presupuesto y con alertas de lag por partición.
- **Pinning en Java 21**: si un driver (HTTP, Mongo, Kafka) hace E/S dentro de `synchronized`, el hilo portador queda bloqueado. Se monitorea con `-Djdk.tracePinnedThreads=short` en las pruebas de carga.

## Condiciones para revisar esta decisión

- El lag del consumer group crece de forma sostenida y agregar particiones no es viable → evaluar la alternativa D.
- Se observa *pinning* relevante en pruebas de carga → migrar a Java 24+ (JEP 491 elimina el pinning en `synchronized`) o cambiar el componente afectado.
- La latencia de las APIs crece al punto de que 10 s de presupuesto sean insuficientes → revisar reintentos no bloqueantes, aceptando la pérdida de orden.
- Los pedidos llegan a miles de líneas → evaluar consultas por lote (`GET /products?ids=...`) en `products-api` antes de aumentar el paralelismo.