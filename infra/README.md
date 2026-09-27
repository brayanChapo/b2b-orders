# Infraestructura local

Kafka (modo KRaft) y MongoDB (replica set de un nodo) con Docker Compose. Los tópicos, colecciones e índices se crean automáticamente al levantar el entorno.

## Requisitos

- Docker con Compose v2 (`docker compose version`).
- Bash para los scripts de `infra/scripts/` (en Windows: WSL2 o Git Bash).
- Puertos libres: 9092 (Kafka), 27017 (MongoDB) y 8080 (Kafka UI, opcional). Se cambian en `.env`.

## Levantar el entorno

```bash
cp .env.example .env          # opcional: solo si necesitas cambiar puertos o versiones
docker compose up -d
docker compose ps -a
```

Resultado esperado de `docker compose ps -a`:

| Servicio | Estado esperado |
|---|---|
| `kafka` | `Up (healthy)` |
| `mongo` | `Up (healthy)` |
| `kafka-init` | `Exited (0)`: creó los tópicos y terminó |
| `mongo-init` | `Exited (0)`: creó colecciones e índices y terminó |

Si un inicializador termina con código distinto de 0, revisa su salida con `docker compose logs kafka-init` o `docker compose logs mongo-init`.

Con Kafka UI (opcional):

```bash
docker compose --profile tools up -d    # http://localhost:8080
```

## Verificar

**Tópicos creados** (deben aparecer los tres, con 3 particiones cada uno):

```bash
docker compose logs kafka-init
```

**Colecciones e índices**:

```bash
infra/scripts/mongo-shell.sh 'db.getCollectionNames()'
infra/scripts/mongo-shell.sh 'db.orders.getIndexes()'
```

**Replica set activo** (debe mostrar `PRIMARY`):

```bash
infra/scripts/mongo-shell.sh 'rs.status().members[0].stateStr'
```

**Kafka de extremo a extremo** (publicar y leer un evento):

```bash
infra/scripts/publish-event.sh contracts/events/examples/orders.created.v1/valid/mx-wholesale-golden.json
infra/scripts/consume-topic.sh orders.created.v1
```

Debe aparecer el evento con key `ORD-MX-000147`.

## Scripts

| Script | Uso |
|---|---|
| `infra/scripts/publish-event.sh <archivo> [tópico] [repeticiones]` | Publica un JSON con key = `orderId`. Con repeticiones > 1 sirve para probar duplicados. |
| `infra/scripts/consume-topic.sh [tópico] [timeout_ms]` | Lee un tópico desde el inicio mostrando key, headers y timestamp. Por defecto `orders.processed.v1`. |
| `infra/scripts/mongo-shell.sh [expresión]` | Abre `mongosh` sobre la base `orders` o ejecuta una expresión. |

Los scripts pueden ejecutarse desde cualquier carpeta; internamente se ubican en la raíz del repositorio.

## Conexión desde tu máquina (IDE, Compass, tests locales)

| Recurso | Dentro de Docker | Desde tu máquina |
|---|---|---|
| Kafka | `kafka:19092` | `localhost:9092` |
| MongoDB | `mongodb://mongo:27017/orders?replicaSet=rs0` | `mongodb://localhost:27017/orders?directConnection=true` |

**Por qué `directConnection=true` desde tu máquina**: el replica set se anuncia como `mongo:27017`, un nombre que solo existe dentro de la red de Docker. Con `replicaSet=rs0`, el driver intentaría conectarse a `mongo:27017` y fallaría. `directConnection=true` hace que se conecte directo al nodo sin descubrir el replica set; las transacciones siguen funcionando porque ese nodo es el primario.

Kafka resuelve lo mismo con dos listeners: `INTERNAL` (anunciado como `kafka:19092`) para contenedores y `EXTERNAL` (anunciado como `localhost:9092`) para tu máquina.

## Detener y limpiar

```bash
docker compose stop     # detiene, conserva los datos
docker compose down     # detiene y BORRA todo: tópicos, mensajes, colecciones
```

El entorno es **efímero a propósito**: no usa volúmenes. Cada `docker compose down` + `up` parte desde cero, lo que hace reproducibles las pruebas manuales.

## Decisiones

| Decisión | Motivo |
|---|---|
| Kafka en KRaft, sin ZooKeeper | Es el modo por defecto desde Kafka 4.0; un contenedor menos. |
| `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` | Un nombre de tópico mal escrito debe fallar, no crear un tópico nuevo en silencio. |
| Tópicos e índices creados por contenedores `*-init` | Idempotentes, versionados en el repo y visibles en los logs. Los servicios no necesitan permisos de administración. |
| MongoDB como replica set | Sin replica set no hay transacciones, y sin transacciones no hay outbox atómico (ADR-002). |
| El healthcheck de Mongo inicia el replica set | Evita un contenedor extra y garantiza que "healthy" signifique "acepta escrituras". |
| DLT con 30 días de retención | Da tiempo para investigar y reprocesar; entrada y salida con 7 días. |
| Sin volúmenes | Entorno local reproducible; no se pretende persistencia. |
| Kafka UI en el perfil `tools` | Útil para depurar, pero no necesario para ejecutar ni probar. |
| `.gitattributes` con `eol=lf` | Los scripts corren en contenedores Linux; con CRLF (clon en Windows) fallan con errores confusos. |

## Problemas frecuentes

| Síntoma | Causa probable | Solución |
|---|---|---|
| `port is already allocated` | Otro proceso usa el puerto | Cambiar el puerto en `.env` |
| `kafka-init` falla con `/bin/sh^M` o `not found` | Script con fin de línea CRLF | Verificar `.gitattributes`; `git add --renormalize .` |
| `mongo` nunca queda `healthy` | Replica set sin iniciar o nombre de host distinto | `docker compose logs mongo`; `docker compose down` y volver a levantar |
| El IDE no conecta a Mongo | Falta `directConnection=true` | Usar la cadena de conexión de la tabla anterior |
| No encuentra la imagen `kafbat/kafka-ui` | Etiqueta no disponible | Cambiar `KAFKA_UI_VERSION` en `.env` (solo afecta al perfil `tools`) |
