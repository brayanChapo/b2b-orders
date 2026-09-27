# order-processor

Worker que consume `orders.created.v1`, valida el evento, consulta clients-api y products-api, aplica las reglas de negocio, guarda el resultado en MongoDB y lo publica en `orders.processed.v1`. Los fallos que no pueden resolverse van a `orders.processing.dlt`.

Java 21, Spring Boot 3.5, virtual threads.

## Requisitos

- JDK 21. No hace falta Maven: el proyecto incluye Maven Wrapper.
- Docker, para los tests de integración y para ejecutar el flujo completo.

## Tests

```bash
./mvnw test       # unitarios: dominio, aplicación y adaptadores sin infraestructura (sin Docker)
./mvnw verify     # además, *IT.java con Testcontainers (Kafka y MongoDB reales)
```

En PowerShell: `.\mvnw.cmd test` y `.\mvnw.cmd verify`. Si Docker no está disponible, los tests de integración que lo necesitan se omiten en lugar de fallar.

| Test | Qué demuestra |
|---|---|
| `domain/*` | Validación, elegibilidad, impuestos por mercado y categoría, exención, descuento mayorista, redondeo y totales. |
| `ProcessOrderServiceTest`, `VersionPolicyTest` | Orquestación: rechazo sin consultar productos, `TECHNICAL_FAILURE` ante errores de dependencias, duplicados, versiones obsoletas, conflictos y reproceso de un fallo técnico. |
| `OutboxRelayTest` | Publicación en orden, marcado y reprogramación con backoff sin adelantar mensajes posteriores. |
| `OrderEventParserTest`, `OrderProcessedEventMapperTest` | Contrato: todos los fixtures de `/contracts` y el evento de salida *golden*, idéntico al del contrato. |
| `HttpCatalogsIT` | 404, 429 con `Retry-After`, 5xx, timeout, respuesta fuera de contrato, reintentos, paralelismo y propagación de `X-Request-Id`. |
| `MongoStoresIT` | Escritura condicional y outbox atómicos; el mismo evento en 8 hilos produce 1 documento y 1 mensaje; eventos distintos con la misma versión tienen un solo ganador. |
| `OrderProcessingIT` | Flujo completo con Kafka y MongoDB reales: aprobado, rechazado, duplicado, versión obsoleta, inválido a DLT, dependencia caída a `TECHNICAL_FAILURE` + DLT y recuperación tras un fallo transitorio. |

## Ejecutar

Todo el sistema, desde la raíz del repositorio:

```bash
docker compose up -d --build
docker compose ps          # order-processor debe quedar "healthy"
```

Solo el worker desde el IDE o la terminal, con la infraestructura en Docker:

```bash
docker compose up -d kafka kafka-init mongo mongo-init products-api clients-api
cd order-processor && ./mvnw spring-boot:run
```

Los valores por defecto de `application.yml` apuntan a `localhost` con los puertos de `.env.example`.

## Probar el flujo a mano

Desde la raíz, con Git Bash:

```bash
# Pedido aprobado (resultado esperado: grandTotal 2100.11)
infra/scripts/publish-event.sh contracts/events/examples/orders.created.v1/valid/mx-wholesale-golden.json
infra/scripts/consume-topic.sh orders.processed.v1

# Resultado en MongoDB
infra/scripts/mongo-shell.sh 'printjson(db.orders.findOne({_id: "ORD-MX-000147"}, {status: 1, totals: 1, reasons: 1}))'

# El mismo evento tres veces: un solo resultado; order_events cuenta las entregas
infra/scripts/publish-event.sh contracts/events/examples/orders.created.v1/valid/mx-wholesale-golden.json orders.created.v1 3
infra/scripts/mongo-shell.sh 'printjson(db.order_events.find({orderId: "ORD-MX-000147"}).toArray())'

# Evento inválido: va a la DLT con sus headers y no se guarda como pedido
infra/scripts/publish-event.sh contracts/events/examples/orders.created.v1/invalid-semantic/currency-market-mismatch.json
infra/scripts/consume-topic.sh orders.processing.dlt

# Dependencia caída: PRD-FAIL-503 siempre responde 503 (inyección de fallos de products-api)
sed -e 's/PRD-008/PRD-FAIL-503/' -e 's/ORD-MX-000147/ORD-MX-FAIL-1/' -e 's/01J8ZP6M5E4RH0K7Y2N9A3TQWX/01J8ZP6M5E4RH0K7Y2N9A3TQX0/' \
  contracts/events/examples/orders.created.v1/valid/mx-wholesale-golden.json > /tmp/fail.json
infra/scripts/publish-event.sh /tmp/fail.json
infra/scripts/mongo-shell.sh 'printjson(db.orders.findOne({_id: "ORD-MX-FAIL-1"}, {status: 1, error: 1}))'
```

Métricas y salud:

```bash
curl http://localhost:8083/actuator/health
curl -s http://localhost:8083/actuator/prometheus | grep -E "^orders_|^outbox_"
```

## Configuración

| Variable | Por defecto | Uso |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Brokers de Kafka |
| `MONGODB_URI` | `mongodb://localhost:27017/orders?directConnection=true` | Debe ser replica set: sin transacciones el servicio no arranca |
| `MONGODB_DATABASE` | `orders` | Base de datos |
| `CLIENTS_API_URL` | `http://localhost:8082` | clients-api |
| `PRODUCTS_API_URL` | `http://localhost:8081` | products-api |
| `KAFKA_LISTENER_CONCURRENCY` | `3` | Consumidores; no más que particiones |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | sin definir (texto) | `logstash` para logs JSON (así corre en Docker) |

Timeouts, reintentos, outbox y límites están en el bloque `app` de `application.yml`; cualquier valor se puede sobrescribir con variables de entorno (`APP_HTTP_READ_TIMEOUT=3s`).

## Estructura

```
com.b2b.orders
├── domain/                  reglas de negocio, Java puro (ver DomainIndependenceTest)
├── application/             caso de uso ProcessOrderService, puertos, VersionPolicy, relay del outbox
│   ├── port/                ClientCatalog, ProductCatalog, OrderResultStore
│   └── outbox/              OutboxRelay, OutboxStore, MessagePublisher
└── infrastructure/
    ├── kafka/               OrderCreatedListener, parser del evento, DLT, recuperador de errores
    ├── http/                RestClient, reintentos, mapeo de respuestas y DTOs de las APIs
    ├── mongo/               escritura condicional + outbox, guardia de entregas, esquema e índices
    ├── messaging/           JSON de orders.processed.v1, generador de ULID
    ├── outbox/              relay programado
    ├── observability/       métricas
    └── config/              wiring de Spring y propiedades
```

La aplicación no conoce Kafka, HTTP ni MongoDB: los usa a través de puertos. La infraestructura conoce a la aplicación, nunca al revés.

## Cómo se comporta ante cada caso

| Caso | Resultado | Offset |
|---|---|---|
| JSON inválido o contrato incumplido | DLT `VALIDATION_ERROR`, nada en `orders` | Se confirma tras publicar en la DLT |
| Cliente o producto no elegible | `REJECTED` + evento en `orders.processed.v1` | Se confirma |
| 404 de una API | Rechazo de negocio (`CLIENT_NOT_FOUND`, `PRODUCT_NOT_FOUND`) | Se confirma |
| 429, 5xx o timeout | Hasta 3 intentos (200 ms, 400 ms ±20 %); agotados → `TECHNICAL_FAILURE` + DLT por outbox | Se confirma |
| Otro 4xx o respuesta fuera de contrato | `TECHNICAL_FAILURE` sin reintentos + DLT | Se confirma |
| Mismo `eventId` ya procesado | Se descarta (`DUPLICATE`) | Se confirma |
| Versión menor que la vigente | Se descarta (`STALE`) | Se confirma |
| Otro `eventId` con la misma versión | Se descarta (`CONFLICT`) y se registra en `order_events` | Se confirma |
| MongoDB caído | Reintento del mismo mensaje con backoff 1 s → 60 s; tras 10 min, DLT `PERSISTENCE_ERROR` | No se confirma mientras reintenta |
| Kafka caído al publicar el resultado | El resultado ya está en `outbox`; el relay reintenta hasta publicarlo | — |
| El proceso muere a mitad del mensaje | Se reentrega; tras 3 caídas con el mismo offset, DLT `REPEATED_DELIVERY_FAILURE` | — |
