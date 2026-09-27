# ADR-002 — Consistencia entre persistencia (MongoDB) y publicación (Kafka)

| Campo | Valor |
|---|---|
| Estado | Aceptado |
| Fecha | 2026-09-26 |
| Relacionado con | `docs/architecture-proposal.md` (secciones 5.5, 5.6, 6 y 7), ADR-001 |

## Contexto

Por cada pedido, `order-processor` debe **guardar el resultado en MongoDB** y **publicar un evento en `orders.processed.v1`**. Son dos sistemas sin una transacción común, y el proceso puede caer entre ambos pasos. El enunciado exige evitar, o manejar explícitamente:

1. un pedido persistido sin evento de salida;
2. un evento de salida publicado sin pedido persistido;
3. más de un efecto de negocio para el mismo pedido.

Además, Kafka entrega el mensaje de entrada al-menos-una-vez (ver ADR-001), así que todo el flujo debe tolerar reprocesamientos.

Un requisito adicional: los pedidos en `TECHNICAL_FAILURE` deben llegar a `orders.processing.dlt` con la misma garantía; un fallo técnico persistido pero no reportado sería invisible para la operación.

## Alternativas consideradas

| # | Alternativa | Qué pasa si el proceso cae a la mitad | Evaluación |
|---|---|---|---|
| A | **Persistir y luego publicar** | Pedido persistido sin evento (caso 1). Al reentregar, la escritura condicional ve un duplicado y no vuelve a publicar. | Descartada, salvo que se añadan flags de "publicado" y reconciliación, que terminan siendo un outbox improvisado. |
| B | **Publicar y luego persistir** | Evento sin pedido (caso 2); los consumidores actúan sobre algo que no existe. | Descartada. |
| C | **Transacciones de Kafka** (exactly-once) | Cubren consumir-procesar-producir en Kafka, pero MongoDB queda fuera de la transacción. | Descartada: no resuelve el problema central. |
| D | **Transactional outbox con relay por polling** | La entrada de outbox se confirma junto con el pedido; si el relay cae, se reintenta al volver. | **Elegida.** |
| E | Outbox con relay por **Change Streams** | Igual que D, con menor latencia. | Más complejidad operativa (resume tokens, reanudación tras caídas). Evolución natural de D. |
| F | Outbox con **CDC (Debezium)** | Igual que D, sin código de relay propio. | Requiere Kafka Connect y operar Debezium: demasiada infraestructura para esta etapa. |
| G | **"Escucharse a sí mismo"**: solo publicar, y un segundo consumidor persiste desde el tópico de salida | Kafka es la fuente de verdad; no hay doble escritura. | La idempotencia de la escritura condicional se evalúa tarde, cuando el evento ya fue publicado: no evita el caso 3. |

## Decisión

**Alternativa D: transactional outbox en MongoDB con relay por polling.**

1. **Una transacción por pedido** (MongoDB replica set) que incluye:
   - la escritura condicional del resultado en `orders` (sección 6.2 de la propuesta), que garantiza un solo efecto por `(orderId, eventVersion)`;
   - la inserción en `outbox` del mensaje de salida ya serializado, con `topic`, `key = orderId`, `eventId` de salida y `status = PENDING`;
   - la actualización de `order_events` y `delivery_attempts` a su estado final.

   Se usa la API `withTransaction` del driver, que reintenta automáticamente ante `TransientTransactionError` y `UnknownTransactionCommitResult`.
2. **El `eventId` de salida se genera una sola vez**, al crear la entrada del outbox. Toda republicación usa el mismo valor, lo que permite a los consumidores deduplicar.
3. **Relay** (`@Scheduled`, cada 500 ms):
   - reclama un lote de entradas `PENDING` (o con `lockedUntil` vencido) mediante `findOneAndUpdate`, asignando un *lease* de 30 s, para que varias instancias no publiquen la misma entrada a la vez;
   - publica en orden de `createdAt`, con productor idempotente (`acks=all`, `enable.idempotence=true`), y espera la confirmación;
   - marca `SENT` con `sentAt`. Si la publicación falla, incrementa `publishAttempts` y aplica backoff; nunca descarta la entrada.
4. **Destinos del outbox**: `APPROVED` y `REJECTED` → `orders.processed.v1`. `TECHNICAL_FAILURE` → `orders.processing.dlt` con el mensaje original y la metadata de error.
5. **Errores de validación** (que no se persisten como pedido) se publican en la DLT de forma **síncrona** antes del commit del offset. Si falla, no hay commit y Kafka reentrega.
6. **Offset**: se confirma solo después de que la transacción se confirmó (ver ADR-001).
7. **Orden de salida**: el evento incluye `sourceEventVersion` (campo aditivo en `v1`), para que los consumidores conserven la versión mayor si el relay publica fuera de orden.
8. **Limpieza**: índice TTL de 7 días sobre `outbox.sentAt`.

## Consecuencias

**Positivas**

- Los tres estados inconsistentes del enunciado quedan descartados por construcción:

  | Estado | Por qué no ocurre |
  |---|---|
  | Pedido sin evento | Pedido y entrada de outbox se confirman en la misma transacción; el relay reintenta hasta publicar. |
  | Evento sin pedido | Solo se publica lo que existe en el outbox, que solo existe si la transacción se confirmó. |
  | Más de un efecto | La escritura condicional admite una sola escritura por `(orderId, eventVersion)`. |

- Los fallos técnicos tienen la misma garantía de entrega a la DLT que los resultados de negocio.
- El relay es independiente del procesamiento: una caída de Kafka no detiene la persistencia de resultados, solo retrasa su publicación.

**Negativas y aceptadas**

- **Al-menos-una-vez en la salida**: si el relay cae entre publicar y marcar `SENT`, el evento se publica dos veces con el mismo `eventId`. Los consumidores deben deduplicar (supuesto S13 de la propuesta).
- **Latencia adicional** de hasta ~500 ms por el intervalo de polling.
- **MongoDB debe ser replica set** en todos los entornos, incluido el local y Testcontainers. El worker falla al arrancar si no detecta soporte de transacciones.
- **Carga extra** por el polling y la colección `outbox`, despreciable con el volumen supuesto.
- **Nueva señal operativa a vigilar**: antigüedad de la entrada `PENDING` más vieja (`orders.outbox.oldest_pending_age`). Si crece, los resultados se están acumulando sin publicar.

## Condiciones para revisar esta decisión

- Se exige una latencia de extremo a extremo menor de ~500 ms → migrar el relay a Change Streams (alternativa E) sin cambiar el esquema del outbox.
- La plataforma adopta CDC como estándar o el volumen hace costoso el polling → evaluar Debezium (alternativa F).
- Los consumidores de `orders.processed.v1` no pueden deduplicar → exigir un contrato de deduplicación o evaluar entrega transaccional en el lado consumidor.
- El resultado deja de persistirse en MongoDB, o pasa a vivir en Kafka como fuente de verdad → reevaluar las alternativas C y G.