# Runbook de order-processor

## Un cliente dice que envió un pedido y no aparece como aprobado ni rechazado

Objetivo: ubicar en qué punto del recorrido se detuvo el pedido. Se necesita el `orderId`; el `eventId` ayuda pero no es imprescindible.

```
productor → orders.created.v1 → order-processor → MongoDB (orders + outbox) → orders.processed.v1
                                      ↘ orders.processing.dlt
```

Los comandos son para el entorno local (`infra/scripts`); en producción son las mismas consultas sobre MongoDB y Kafka, y la misma búsqueda en la plataforma de logs.

### 1. ¿Qué sabe order-processor del pedido?

```bash
infra/scripts/mongo-shell.sh 'printjson(db.orders.findOne({_id: "ORD-MX-000147"}, {status: 1, eventId: 1, eventVersion: 1, reason: 1, error: 1, processedAt: 1, trace: 1}))'
infra/scripts/mongo-shell.sh 'printjson(db.order_events.find({orderId: "ORD-MX-000147"}).toArray())'
infra/scripts/mongo-shell.sh 'printjson(db.delivery_attempts.find({orderId: "ORD-MX-000147"}).toArray())'
infra/scripts/mongo-shell.sh 'printjson(db.outbox.find({key: "ORD-MX-000147"}, {payload: 0}).toArray())'
```

| Lo que se encuentra | Significa | Acción |
|---|---|---|
| `orders.status` = `APPROVED` o `REJECTED` y outbox `SENT` | Se procesó y publicó. El problema está aguas abajo | Revisar el consumidor de `orders.processed.v1` (buscar por `sourceEventId`) |
| `orders.status` = `APPROVED`/`REJECTED` y outbox `PENDING` con `attempts` > 0 | Persistido pero Kafka no aceptó la publicación | Ver `lastError` en outbox y la salud de Kafka. Se publicará solo cuando Kafka responda |
| `orders.status` = `TECHNICAL_FAILURE` | Una dependencia falló; el mensaje está en la DLT | Ver `error.code` (p. ej. `PRODUCTS_API_503`), corregir la causa y republicar el mensaje original: el mismo `eventId` puede reprocesar un fallo técnico |
| `order_events.outcome` = `INVALID` | El evento no cumplió el contrato | Buscar el mensaje en la DLT (paso 3); lo corrige el productor con un evento nuevo |
| `order_events.outcome` = `STALE` | Llegó una revisión más nueva antes que esta | Es correcto: el resultado vigente es el de la versión mayor en `orders` |
| `order_events.outcome` = `CONFLICT` | Otro evento distinto ya ocupaba la misma `eventVersion` | Error del productor (dos eventos con la misma revisión); escalar con ambos `eventId` |
| `delivery_attempts.status` = `RETRYING` | El worker está reintentando (normalmente MongoDB con problemas) | Revisar MongoDB y el lag; se resuelve solo o termina en la DLT como `PERSISTENCE_ERROR` a los 10 min |
| `delivery_attempts.status` = `IN_PROGRESS` antiguo o `crashes` > 0 | El proceso murió procesándolo | Revisar reinicios del contenedor (memoria, OOM). A las 3 caídas va a la DLT como `REPEATED_DELIVERY_FAILURE` |
| Nada en ninguna colección | order-processor nunca lo leyó | Seguir con el paso 2 |

### 2. ¿Llegó a Kafka y se está consumiendo?

```bash
# Lag del consumidor: si LAG crece, el worker está detenido o atascado en un mensaje
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group order-processor

# Buscar el pedido en el tópico de entrada (la key es el orderId)
infra/scripts/consume-topic.sh orders.created.v1 20000 | grep "ORD-MX-000147"
```

- Si no está en el tópico: el pedido no salió del productor. Se escala al dueño de `orders.created.v1` con el `orderId` y la hora aproximada.
- Si está en el tópico y el lag de su partición no baja: el worker está detenido o reintentando un mensaje anterior de esa partición. Los logs del paso 4 muestran cuál.
- Si publicaron el pedido sin `orderId` como key, puede estar en otra partición que la esperada: se busca por el contenido.

### 3. ¿Está en la DLT?

```bash
infra/scripts/consume-topic.sh orders.processing.dlt 20000 | grep "ORD-MX-000147"
```

Los headers `dlt-error-category`, `dlt-error-code`, `dlt-error-summary` y `dlt-attempts` explican el motivo; `dlt-original-partition` y `dlt-original-offset` ubican el mensaje original.

### 4. Logs

```bash
docker compose logs order-processor | grep "ORD-MX-000147"
docker compose logs products-api clients-api | grep "<eventId>"
```

Cada línea de order-processor lleva `orderId` y `eventId`. El `eventId` viaja como `X-Request-Id`, así que la misma búsqueda encuentra las llamadas en las APIs. La secuencia normal es `event_received` → `event_validated` → `client_fetched` → `products_fetched` → `order_decided` → `order_persisted` → `outbox_published`; la última línea presente indica dónde se detuvo.

## Alertas sugeridas

| Condición | Umbral inicial | Indica |
|---|---|---|
| Lag de `order-processor` creciente | > 1 000 mensajes durante 5 min | Worker detenido o partición bloqueada |
| `outbox_oldest_pending_age_seconds` | > 60 s | Kafka no acepta publicaciones |
| `orders_dead_lettered_total` | Cualquier `PERSISTENCE_ERROR` o `REPEATED_DELIVERY_FAILURE` | Problema de infraestructura o mensaje veneno |
| Tasa de `orders_processed_total{status="TECHNICAL_FAILURE"}` | > 1 % en 10 min | Dependencia degradada |
| `orders_dependency_retries_total` | Aumento brusco | Una API empieza a fallar antes de que haya fallos técnicos |
