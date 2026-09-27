# products-api

Catálogo de productos por mercado, en Go, usando solo la librería estándar. Contrato: [`contracts/http/products-api.openapi.yaml`](../contracts/http/products-api.openapi.yaml).

## Requisitos

- Go 1.22 o superior.
- Docker (opcional, para ejecutarlo en contenedor).

## Ejecutar

```bash
# Local
cd products-api
go run ./cmd/products-api                     # http://localhost:8080

# Con Docker Compose (desde la raíz del repositorio)
docker compose up -d products-api             # http://localhost:8081
```

## Probar

```bash
cd products-api
go test ./...                 # todos los tests
go test -race ./...           # con detector de carreras (requiere un compilador C; en Windows, usar WSL)
go test -cover ./...          # con cobertura
go vet ./...
```

Los tests de contrato leen `../contracts`. Si esa carpeta no existe, se omiten con un aviso en lugar de fallar.

## Endpoints

| Método y ruta | Descripción |
|---|---|
| `GET /products/{productId}?market={MX\|CO\|PE}` | Producto en un mercado. |
| `GET /health` | `200 {"status":"UP"}`; `503 {"status":"DOWN"}` durante el apagado. |

```bash
curl "localhost:8081/products/PRD-001?market=MX"   # 200
curl "localhost:8081/products/PRD-005?market=MX"   # 404: no se vende en MX
curl "localhost:8081/products/PRD-001?market=AR"   # 400: mercado no soportado
curl -i "localhost:8081/products/PRD-FAIL-429?market=MX"   # 429 con Retry-After (inyección de fallos)
```

### Errores

Todas las respuestas de error siguen [`contracts/http/error.schema.json`](../contracts/http/error.schema.json), incluidas las rutas o métodos inexistentes (que `ServeMux` respondería en texto plano por defecto).

| HTTP | `code` | Cuándo | ¿Reintentable? |
|---|---|---|---|
| 400 | `INVALID_PARAMETER` | `productId` o `market` inválidos. `details` indica cada campo. | No |
| 404 | `PRODUCT_NOT_FOUND` | No existe o no se vende en ese mercado. | No |
| 404 | `ROUTE_NOT_FOUND` | Ruta inexistente. | No |
| 405 | `METHOD_NOT_ALLOWED` | Método distinto de GET/HEAD. Incluye `Allow`. | No |
| 429 | `RATE_LIMITED` | Solo por inyección de fallos. Incluye `Retry-After`. | Sí |
| 500 | `INTERNAL_ERROR` | Error inesperado o pánico. Sin detalles internos en la respuesta. | Sí |
| 502 | `BAD_GATEWAY` | Solo por inyección de fallos. | Sí |
| 503 | `SERVICE_UNAVAILABLE` | Solo por inyección de fallos. | Sí |
| 503 | `REQUEST_TIMEOUT` | Se superó `REQUEST_TIMEOUT`. | Sí |

Un producto `DISCONTINUED` **no** es un error: se responde 200 y decidir qué hacer es responsabilidad del consumidor.

## Configuración

| Variable | Defecto | Descripción |
|---|---|---|
| `PORT` | `8080` | Puerto HTTP. |
| `REQUEST_TIMEOUT` | `5s` | Deadline por solicitud, propagado por `context.Context`. |
| `SHUTDOWN_TIMEOUT` | `10s` | Tiempo máximo para drenar solicitudes al apagar. |
| `FAULT_INJECTION_ENABLED` | `false` | Habilita la inyección de fallos. En Docker Compose local es `true`. |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn` o `error`. |

Un valor inválido detiene el arranque con un mensaje claro: es preferible no arrancar que arrancar mal configurado.

## Inyección de fallos

Solo activa con `FAULT_INJECTION_ENABLED=true`. Con la bandera apagada, el código de inyección ni siquiera se registra en la cadena de handlers.

| Mecanismo | Valores | Uso previsto |
|---|---|---|
| Header `X-Fault` | `429`, `500`, `502`, `503`, `timeout` | Tests y pruebas manuales con curl. |
| IDs reservados `PRD-FAIL-<tipo>` | `400`, `429`, `500`, `502`, `503`, `TIMEOUT` | Pruebas de extremo a extremo: publicar un pedido con `PRD-FAIL-503` para ver los reintentos y el `TECHNICAL_FAILURE` en order-processor. |

`timeout` no responde hasta que vence el deadline o el cliente cancela. `PRD-FAIL-400` simula un error **definitivo**. `/health` nunca se ve afectado.

## Datos semilla

12 productos en `internal/catalog/seed.go`, con disponibilidad, estado y categoría fiscal por mercado. Incluyen a propósito productos `DISCONTINUED` y productos que no se venden en algún mercado. Los fixtures de `contracts/` dependen de algunos de ellos; `TestSeedMatchesContractFixtures` falla si alguien los rompe.

## Estructura

```
cmd/products-api/main.go     arranque, servidor HTTP, apagado controlado, flag -healthcheck
internal/product/            dominio: tipos, validación de productId y market, errores
internal/catalog/            consulta de productos en memoria + datos semilla
internal/httpapi/            transporte: rutas, handler, DTO, errores, middlewares, fallos
internal/config/             configuración desde variables de entorno
```

Dependencias permitidas: `httpapi → product`, `catalog → product`, `main → todos`. `product` no depende de nadie; `httpapi` no conoce `catalog`.

## Decisiones

| Decisión | Motivo |
|---|---|
| Librería estándar, sin framework | Desde Go 1.22, `ServeMux` admite métodos y parámetros de ruta. Con `log/slog`, `context` y `net/http` no hace falta más. Cero dependencias externas. |
| Interfaz `Catalog` definida en `httpapi` (el consumidor) | Idiomático en Go. Reemplazar la memoria por una base de datos es implementar `Find` en otro tipo y cambiar una línea en `main.go`; el handler no cambia. |
| DTO `productResponse` separado del dominio | El dominio incluye `Market`, que el contrato no expone. El contrato puede mantenerse estable aunque el dominio evolucione. |
| Sin capa de "servicio" entre handler y catálogo | Hoy no hay reglas de negocio que la justifiquen; sería una abstracción sin problema que resolver. Se agregaría con la primera regla (p. ej. caché o autorización por mercado). |
| Deadline por solicitud vía middleware | Todo lo que recibe `r.Context()` hereda el deadline y la cancelación del cliente. Los tests lo verifican con un catálogo que bloquea. |
| Validación estricta de `market` (sin normalizar `mx`) | El contrato define los valores exactos; normalizar ocultaría errores del consumidor. |
| Todos los errores de validación en una respuesta | `details` lista cada campo inválido; el consumidor corrige todo de una vez. |
| `traceparent` → `X-Request-Id` → generado | Permite seguir un pedido desde order-processor. `X-Request-Id` se valida para no inyectar texto arbitrario en logs. |
| `/health` a `DOWN` al iniciar el apagado | Un balanceador deja de enviar tráfico nuevo mientras se drenan las solicitudes en curso. |
| Imagen distroless con usuario no root | Sin shell ni gestor de paquetes. Por eso el propio binario hace el healthcheck (`-healthcheck`). |
| Timeouts del `http.Server` configurados | Evitan conexiones colgadas y clientes lentos (slowloris). |

## Cómo se reemplazaría la memoria por una base de datos

1. Crear `internal/catalog/postgres.go` (o similar) con un tipo que implemente `Find(ctx, id, market)`, pasando `ctx` al driver para heredar deadline y cancelación.
2. Traducir "sin filas" a `product.ErrNotFound`, y los errores de conexión a un error genérico (el handler ya los convierte en 500 sin filtrar detalles).
3. Cambiar la construcción en `main.go`.

Ni el handler, ni el contrato, ni los consumidores cambian.

## Limitaciones conocidas

- Sin rate limiting real: el 429 solo existe por inyección de fallos.
- Sin métricas expuestas; la observabilidad es por logs JSON con `trace_id`.
- Datos en memoria: un cambio de catálogo requiere redeploy.
