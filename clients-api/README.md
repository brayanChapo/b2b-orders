# clients-api

Datos de clientes (distribuidores) por mercado, en NestJS con TypeScript estricto. Contrato: [`contracts/http/clients-api.openapi.yaml`](../contracts/http/clients-api.openapi.yaml).

## Requisitos

- Node.js 22 o superior.
- Docker (opcional, para ejecutarlo en contenedor).

## Ejecutar

```bash
# Local
cd clients-api
npm ci
npm run build
npm start                                   # http://localhost:8080

# Con Docker Compose (desde la raíz del repositorio)
docker compose up -d --build clients-api    # http://localhost:8082
```

En PowerShell, las variables se definen antes de arrancar: `$env:FAULT_INJECTION_ENABLED="true"; npm start`.

## Probar

```bash
cd clients-api
npm test              # unitarios + e2e
npm run test:unit     # solo unitarios (src/**/*.spec.ts)
npm run test:e2e      # solo endpoint (test/*.e2e-spec.ts)
npm run test:cov      # con cobertura
npm run typecheck     # verificación de tipos estricta, incluidos los tests
```

Los tests de contrato leen `../contracts`. Si esa carpeta no existe, se omiten en lugar de fallar.

## Endpoints

| Método y ruta | Descripción |
|---|---|
| `GET /clients/{clientId}` | Cliente por identificador. |
| `GET /health` | `200 {"status":"UP"}`; `503 {"status":"DOWN"}` durante el apagado. |

```bash
curl.exe -i "http://localhost:8082/clients/CLI-99821"      # 200
curl.exe -i "http://localhost:8082/clients/CLI-30002"      # 200, cliente BLOCKED
curl.exe -i "http://localhost:8082/clients/CLI-00000"      # 404 CLIENT_NOT_FOUND
curl.exe -i "http://localhost:8082/clients/CLI_1"          # 400 INVALID_PARAMETER
curl.exe -i "http://localhost:8082/clients/CLI-FAIL-429"   # 429 con Retry-After (inyección de fallos)
```

### Errores

Mismo contrato de error y mismos códigos que products-api ([`contracts/http/error.schema.json`](../contracts/http/error.schema.json)), incluidas rutas y métodos inexistentes.

| HTTP | `code` | Cuándo | ¿Reintentable? |
|---|---|---|---|
| 400 | `INVALID_PARAMETER` | `clientId` con formato inválido. `details` indica el campo. | No |
| 404 | `CLIENT_NOT_FOUND` | El cliente no existe. | No |
| 404 | `ROUTE_NOT_FOUND` | Ruta inexistente. | No |
| 405 | `METHOD_NOT_ALLOWED` | Método distinto de GET/HEAD. Incluye `Allow`. | No |
| 429 | `RATE_LIMITED` | Solo por inyección de fallos. Incluye `Retry-After`. | Sí |
| 500 | `INTERNAL_ERROR` | Error inesperado. Sin detalles internos en la respuesta. | Sí |
| 502 | `BAD_GATEWAY` | Solo por inyección de fallos. | Sí |
| 503 | `SERVICE_UNAVAILABLE` | Solo por inyección de fallos. | Sí |
| 503 | `REQUEST_TIMEOUT` | Se superó `REQUEST_TIMEOUT`. | Sí |

Un cliente `BLOCKED` **no** es un error: se responde 200 y decidir si puede comprar es responsabilidad de order-processor.

## Configuración

Mismos nombres y formatos que products-api, para operar ambos servicios igual.

| Variable | Defecto | Descripción |
|---|---|---|
| `PORT` | `8080` | Puerto HTTP. |
| `REQUEST_TIMEOUT` | `5s` | Deadline por solicitud, propagado con `AbortSignal`. Formato: `500ms`, `5s`, `1m`. |
| `SHUTDOWN_TIMEOUT` | `10s` | Tiempo máximo para drenar solicitudes al apagar. |
| `FAULT_INJECTION_ENABLED` | `false` | Habilita la inyección de fallos. En Docker Compose local es `true`. |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn` o `error`. |

Un valor inválido detiene el arranque con un mensaje claro.

## Inyección de fallos

Misma convención que products-api. Solo activa con `FAULT_INJECTION_ENABLED=true`.

| Mecanismo | Valores | Uso previsto |
|---|---|---|
| Header `X-Fault` | `429`, `500`, `502`, `503`, `timeout` | Tests y pruebas manuales. |
| IDs reservados `CLI-FAIL-<tipo>` | `400`, `429`, `500`, `502`, `503`, `TIMEOUT` | Pruebas de extremo a extremo con order-processor. |

## Datos semilla

9 clientes en `src/clients/infrastructure/client.seed.ts`, en los tres mercados, con clientes `BLOCKED`, ambos segmentos y los tres regímenes fiscales. Los fixtures de `contracts/` dependen de `CLI-99821`, `CLI-20002` y `CLI-30002`; un test falla si alguien los cambia.

## Estructura

```
src/
├── main.ts                     arranque, logs JSON y apagado controlado
├── healthcheck.ts              healthcheck para Docker (la imagen no tiene curl)
├── app.module.ts               módulo raíz: filtro, pipe y middleware globales
├── app.setup.ts                ajustes HTTP compartidos por main y los tests e2e
├── config/                     configuración validada desde variables de entorno
├── health/                     GET /health y estado de apagado
├── common/http/                transversal: contrato de error, traceId, AbortSignal,
│                               validación, filtro de excepciones, inyección de fallos
└── clients/
    ├── domain/                 modelo y errores de dominio (sin NestJS)
    ├── application/            caso de uso + puerto ClientRepository
    ├── infrastructure/         repositorio en memoria + datos semilla
    └── http/                   controller, DTO de parámetros y DTO de respuesta
```

Dependencias permitidas: `http → application → domain` e `infrastructure → application, domain`. El dominio no importa nada de NestJS.

## Decisiones

| Decisión | Motivo |
|---|---|
| Controller sin reglas ni datos | Recibe parámetros validados, llama al caso de uso y mapea al DTO. Los errores los traduce un único filtro. |
| Puerto `ClientRepository` como clase abstracta | TypeScript borra las interfaces al compilar; NestJS necesita un token en tiempo de ejecución para inyectar. |
| Errores de dominio (`ClientNotFoundError`) en lugar de `HttpException` | El caso de uso no conoce HTTP. La traducción a 404 vive en `AllExceptionsFilter`. |
| Validación con `class-validator` y `ValidationPipe` global | Validación explícita y declarativa; `exceptionFactory` produce el contrato de error con `details`. |
| `AbortSignal` por solicitud | Equivalente a `context.Context` de Go: se aborta si el cliente se desconecta o vence el deadline, y llega hasta el repositorio. Los tests lo verifican con un repositorio que bloquea. |
| Inyección de fallos como interceptor | Se aplica solo al controller de clientes, después del enrutamiento; `/health` nunca se ve afectado. |
| 405 con `Allow` en lugar del 404 por defecto de Nest | Mismo comportamiento que products-api; el consumidor distingue ruta inexistente de método incorrecto. |
| Logs JSON con el `ConsoleLogger` nativo de Nest 11 | Sin dependencias adicionales. |
| Apagado propio en lugar de `enableShutdownHooks()` | Permite acotar la espera con `SHUTDOWN_TIMEOUT`, igual que products-api. |
| `tsc` en lugar de Nest CLI para compilar | Menos dependencias; el CLI no aporta nada necesario para este servicio. |
| NestJS 11 (existe la 12) | Versión con comportamiento conocido y verificado. Actualizar es un cambio independiente. |
| TypeScript 5.9 (existe la 7) | `ts-jest` aún no soporta TypeScript 7. |

## Cómo se reemplazaría la memoria por una base de datos

1. Crear `src/clients/infrastructure/postgres-client.repository.ts` (o similar) que extienda `ClientRepository`, pasando el `signal` al driver para heredar cancelación y deadline.
2. Devolver `null` cuando no hay fila; dejar que los errores de conexión se propaguen (el filtro los convierte en 500 sin filtrar detalles).
3. Cambiar el `useFactory` de `clients.module.ts`.

Ni el controller, ni el caso de uso, ni el contrato, ni los consumidores cambian.

## Limitaciones conocidas

- Sin rate limiting real: el 429 solo existe por inyección de fallos.
- Sin métricas expuestas; la observabilidad es por logs JSON con `traceId`.
- Sin ESLint configurado: la calidad de tipos la garantiza `strict` y el resto del `tsconfig.json`.
- Datos en memoria: un cambio de clientes requiere redeploy.
