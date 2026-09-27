# Contratos

Fuente única de verdad de los contratos entre componentes. Los tres servicios **comparten contratos, no código**: cada uno implementa sus propios modelos y valida contra estos archivos en sus tests.

```
contracts/
├── events/
│   ├── orders.created.v1.schema.json            # entrada   (dueño: captura de pedidos)
│   ├── orders.processed.v1.schema.json          # salida    (dueño: order-processor)
│   ├── orders.processing.dlt.headers.schema.json# DLT       (dueño: order-processor)
│   └── examples/                                # fixtures reutilizables en tests
│       ├── orders.created.v1/
│       │   ├── valid/              # deben pasar el esquema y procesarse
│       │   ├── invalid-schema/     # deben fallar el esquema
│       │   └── invalid-semantic/   # pasan el esquema, pero el consumidor los rechaza
│       ├── orders.processed.v1/
│       └── orders.processing.dlt/
├── http/
│   ├── error.schema.json          # cuerpo de error común (estándar de plataforma)
│   ├── products-api.openapi.yaml  # dueño: products-api
│   ├── clients-api.openapi.yaml   # dueño: clients-api
│   └── examples/
└── scripts/validate.mjs           # validación ejecutada en CI
```

## Ownership

| Contrato | Dueño | Consumidores | Aprobación obligatoria de cambios |
|---|---|---|---|
| `orders.created.v1` | Captura de pedidos (externo) | order-processor | Dueño + consumidores afectados |
| `orders.processed.v1` | order-processor | Sistemas aguas abajo | order-processor |
| `orders.processing.dlt` | order-processor | Operación / soporte | order-processor |
| `GET /products/{productId}` | products-api | order-processor | products-api + order-processor |
| `GET /clients/{clientId}` | clients-api | order-processor | clients-api + order-processor |
| `error.schema.json` | Estándar compartido | Todos | Tech Lead + un representante por servicio |

Se aplica con `CODEOWNERS` sobre cada archivo.

## Esquema vs. política del consumidor

Los esquemas describen la **forma** de los mensajes. Las reglas de negocio de cada consumidor **no** forman parte del contrato, para que el productor no quede acoplado a decisiones ajenas:

| Regla | Dónde vive | Por qué |
|---|---|---|
| Campos obligatorios, tipos, `quantity >= 1`, `unitPrice >= 0`, `items` no vacío | Esquema | Es la forma del evento. |
| Mercados soportados (MX, CO, PE) | order-processor | Abrir un mercado nuevo es una decisión de negocio del consumidor, no un cambio de contrato. |
| Moneda coherente con el mercado | order-processor | Depende de la tabla de mercados soportados. |
| `productId` sin repetir en `items` | order-processor | JSON Schema no puede expresarlo: `uniqueItems` compara objetos completos, no un campo. |

Por eso existen los fixtures `invalid-semantic/`: **pasan** el esquema y los tests de `order-processor` verifican que terminan en la DLT como `VALIDATION_ERROR`.

## Decisiones del contrato

- **`eventVersion` es obligatorio** en `orders.created.v1`, aunque el enunciado no lo liste entre los obligatorios. La idempotencia por versión depende de él (supuesto S1). Validar con el dueño del contrato.
- **`eventVersion` es la revisión del pedido**, no la versión del esquema. La versión del esquema la da el sufijo del tópico (`.v1`).
- **Importes como número JSON** en `orders.processed.v1`, porque así lo define el contrato original. Todos los consumidores deben leerlos como decimal exacto. Un texto (`"2100.11"`) sería más seguro, pero cambiarlo es un cambio incompatible y se deja para una eventual `v2`.
- **`totals` es `null` cuando el pedido es `REJECTED`**: si el cliente no es elegible no se consultan productos y no hay categoría fiscal para calcular impuestos. Publicar totales parciales invitaría a usarlos.
- **`sourceEventVersion`** se agrega a la salida (cambio aditivo) para que los consumidores conserven el resultado más reciente aunque lleguen fuera de orden.
- **`reasons`** (opcional) detalla todas las razones evaluadas; `reason` conserva la principal.
- **La DLT no envuelve el mensaje**: el value es el original byte a byte y la metadata viaja en headers. Reprocesar es republicar el value en el tópico de origen, sin transformaciones.
- **Tolerant reader**: todos los objetos permiten campos adicionales (`additionalProperties: true`). Los consumidores deben ignorar campos y valores de enum desconocidos, tratándolos con un valor seguro.
- **`X-Fault`** en las APIs es un header solo para pruebas; se ignora salvo que `FAULT_INJECTION_ENABLED=true`.

## Compatibilidad

**Permitido dentro de `v1`** (no requiere versión nueva):
- agregar campos opcionales;
- agregar valores de enum en respuestas, con aviso previo a los consumidores;
- agregar códigos de error o de rechazo nuevos;
- relajar una validación de entrada (p. ej. aumentar un `maxLength`).

**Incompatible** (requiere `v2`):
- eliminar o renombrar campos;
- cambiar tipos, unidades o semántica;
- volver obligatorio un campo opcional;
- endurecer una validación de entrada;
- cambiar la key del mensaje o el significado de `eventVersion`.

**Proceso para un cambio incompatible (expand–contract)**:
1. PR en `/contracts` con el nuevo esquema `v2`, aprobado por el dueño y los consumidores afectados.
2. El productor publica `v1` y `v2` en paralelo (tópico `orders.created.v2` o ruta `/v2/...`).
3. Cada consumidor migra de forma independiente; se mide el uso de `v1`.
4. Retiro de `v1` en una fecha anunciada, cuando el uso llega a cero.

## Validación en CI

```bash
cd contracts
npm ci
npm run validate
```

El script verifica que:
- todos los esquemas compilan (JSON Schema 2020-12, modo estricto);
- los fixtures `valid/` pasan y los `invalid-schema/` fallan;
- los fixtures `invalid-semantic/` pasan el esquema (su rechazo se prueba en `order-processor`);
- las invariantes condicionales de `orders.processed.v1` se cumplen (`APPROVED` exige `totals`; `REJECTED` exige `reason`);
- los headers de ejemplo de la DLT cumplen su esquema;
- los OpenAPI son válidos y sus respuestas de ejemplo cumplen los esquemas.

Controles adicionales del pipeline:
- **Detección de cambios incompatibles**: `oasdiff breaking` compara los OpenAPI contra `main` y falla el build ante un cambio incompatible en `v1`. Para los esquemas de eventos se aplica la misma regla en la revisión obligatoria de `CODEOWNERS` (evolución prevista: diff automático de JSON Schema).
- **Cada servicio** valida su salida real contra estos contratos en sus propios tests: products-api y clients-api contra su OpenAPI, y order-processor contra `orders.processed.v1` y los headers de la DLT. order-processor además usa los fixtures de `orders.created.v1` como entradas de sus tests.

## Datos semilla requeridos por los fixtures

Los fixtures asumen estos datos. Las semillas de las APIs deben incluirlos:

| ID | Datos requeridos | Usado en |
|---|---|---|
| `CLI-99821` | MX, `ACTIVE`, `WHOLESALE`, `GENERAL` | caso *golden* |
| `CLI-20002` | CO, `ACTIVE`, régimen `EXEMPT` | exención por régimen |
| `CLI-30002` | PE, `BLOCKED` | rechazo por cliente |
| `PRD-001` | `ACTIVE`, `STANDARD` en MX y PE | caso *golden*, cliente bloqueado |
| `PRD-008` | `ACTIVE`, `STANDARD` en MX | caso *golden* |
| `PRD-003` | `ACTIVE`, `REDUCED` en CO | exención por régimen |

Con ellos, `mx-wholesale-golden.json` debe producir exactamente `approved-mx-golden.json`: 1836.00 / 25.56 / 1810.44 / 289.67 / 2100.11.
