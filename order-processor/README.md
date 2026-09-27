# order-processor

Worker que consume `orders.created.v1`, aplica las reglas de negocio y publica el resultado en `orders.processed.v1`. Java 21 y Spring Boot 3.5.

> **Estado:** paso 4 del plan incremental. Está implementado el **dominio** (validación, elegibilidad, impuestos, descuentos y totales) con sus tests. Los adaptadores (HTTP, MongoDB, Kafka) se agregan en los pasos siguientes.

## Requisitos

- JDK 21 (por ejemplo, Eclipse Temurin 21). `java -version` debe mostrar 21 o superior.
- No hace falta instalar Maven: el proyecto incluye Maven Wrapper (`mvnw`), que descarga Maven 3.9.16 la primera vez.

## Probar

```bash
cd order-processor
./mvnw test          # Linux, macOS, Git Bash
.\mvnw.cmd test      # PowerShell
```

## Estructura del dominio

```
src/main/java/com/b2b/orders/domain/
├── OrderEvaluator.java        servicio de dominio: elegibilidad + cálculo → decisión
├── OrderDecision.java         resultado sellado: Approved | Rejected
├── model/                     pedido, cliente, producto, mercados, monedas, identificadores
├── validation/                OrderValidator: reglas del contrato de entrada → OrderRequest
├── eligibility/               EligibilityPolicy y razones de rechazo
└── pricing/                   TaxPolicy, DiscountPolicy, OrderCalculator, redondeo y totales
```

El dominio es Java puro: no importa Spring, Kafka, MongoDB, Jackson ni clientes HTTP. `DomainIndependenceTest` falla el build si alguien agrega uno de esos imports.

## Reglas implementadas

| Regla | Dónde |
|---|---|
| Campos obligatorios, `items` no vacío, `productId` sin repetir, `quantity > 0`, `unitPrice >= 0` | `OrderValidator` |
| Mercados MX, CO, PE y moneda coherente (MXN, COP, PEN) | `OrderValidator`, `Market` |
| Cliente existente, `ACTIVE` y del mismo mercado; productos existentes y `ACTIVE` | `EligibilityPolicy` |
| Tasa por mercado y categoría; cliente `EXEMPT` → 0 % | `TaxPolicy` |
| 3 % de descuento para `WHOLESALE` con 20 o más unidades por línea | `DiscountPolicy` |
| `BigDecimal`, 2 decimales, `HALF_UP`; totales = suma de importes ya redondeados | `OrderCalculator`, `Money`, `OrderTotals` |

## Decisiones del dominio

| Decisión | Motivo |
|---|---|
| Cada importe se redondea al calcularse y el paso siguiente usa el valor redondeado | Aplica literalmente "redondear cada importe monetario". Así `netSubtotal` y `lineTotal` son sumas y restas exactas. |
| La validación reporta **todas** las violaciones, en orden estable | Quien investiga la DLT ve el problema completo; la primera violación es el código principal. |
| Elegibilidad en dos pasos (cliente, luego productos) | Si el cliente no es elegible, el pedido no puede aprobarse y no se consultan productos. |
| Valores de enum desconocidos → `UNKNOWN` | *Tolerant reader*: un estado nuevo del proveedor no rompe el servicio; se trata como no elegible, nunca como `ACTIVE`. |
| `PRODUCT_TAX_CATEGORY_UNKNOWN` como razón de rechazo | Aplicar una tasa supuesta a una categoría desconocida cobraría mal; es un código aditivo, compatible con el contrato. |
| `OrderDecision` sellado sin `TECHNICAL_FAILURE` | Un fallo técnico no es una decisión de negocio; lo maneja la capa de aplicación. |
| Tabla de impuestos en código | Cambiar una tasa exige revisión, tests y deploy. Cada línea guarda la tasa aplicada. |
