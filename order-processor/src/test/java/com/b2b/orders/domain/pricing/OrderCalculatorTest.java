package com.b2b.orders.domain.pricing;

import static com.b2b.orders.domain.TestData.PRD_001;
import static com.b2b.orders.domain.TestData.PRD_008;
import static com.b2b.orders.domain.TestData.client;
import static com.b2b.orders.domain.TestData.item;
import static com.b2b.orders.domain.TestData.order;
import static com.b2b.orders.domain.TestData.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.b2b.orders.domain.TestData;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.model.ProductId;
import com.b2b.orders.domain.model.Segment;
import com.b2b.orders.domain.model.TaxCategory;
import com.b2b.orders.domain.model.TaxRegime;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderCalculatorTest {

    private final OrderCalculator calculator = new OrderCalculator(new TaxPolicy(), new DiscountPolicy());

    @Nested
    @DisplayName("Caso golden del enunciado (MX, WHOLESALE, GENERAL, ambos STANDARD)")
    class Golden {

        private final PricedOrder result =
                calculator.price(TestData.goldenOrder(), TestData.goldenClient(), TestData.goldenProducts());

        @Test
        void totalesCoincidenConElEjemploDelContrato() {
            OrderTotals totals = result.totals();
            assertThat(totals.grossSubtotal()).isEqualTo("1836.00");
            assertThat(totals.discount()).isEqualTo("25.56");
            assertThat(totals.netSubtotal()).isEqualTo("1810.44");
            assertThat(totals.tax()).isEqualTo("289.67");
            assertThat(totals.grandTotal()).isEqualTo("2100.11");
        }

        @Test
        void lineaConDescuentoMayorista() {
            PricedLine line = result.lines().get(0);
            assertThat(line.productId()).isEqualTo(PRD_001);
            assertThat(line.discountRate()).isEqualByComparingTo("0.03");
            assertThat(line.taxRate()).isEqualByComparingTo("0.16");
            assertThat(line.grossSubtotal()).isEqualTo("852.00");
            assertThat(line.discount()).isEqualTo("25.56");
            assertThat(line.netSubtotal()).isEqualTo("826.44");
            assertThat(line.taxAmount()).isEqualTo("132.23"); // 132.2304
            assertThat(line.lineTotal()).isEqualTo("958.67");
        }

        @Test
        void lineaSinDescuentoPorDebajoDe20Unidades() {
            PricedLine line = result.lines().get(1);
            assertThat(line.productId()).isEqualTo(PRD_008);
            assertThat(line.discountRate()).isEqualByComparingTo("0");
            assertThat(line.discount()).isEqualTo("0.00");
            assertThat(line.netSubtotal()).isEqualTo("984.00");
            assertThat(line.taxAmount()).isEqualTo("157.44");
            assertThat(line.lineTotal()).isEqualTo("1141.44");
        }

        @Test
        void conservaElSnapshotDelProducto() {
            PricedLine line = result.lines().get(0);
            assertThat(line.name()).isEqualTo("Bebida 600 ml");
            assertThat(line.sku()).isEqualTo("BEB-600-PET");
            assertThat(line.taxCategory()).isEqualTo(TaxCategory.STANDARD);
        }
    }

    @Nested
    @DisplayName("Redondeo")
    class Rounding {

        @Test
        void usaHalfUpYNoHalfEven() {
            // 1.25 * 10 % = 0.125 → HALF_UP: 0.13 (HALF_EVEN daría 0.12)
            PricedLine line = priceSingle("PE", item("P-1", 1, "1.25"), TaxCategory.REDUCED, Segment.RETAIL, TaxRegime.GENERAL);
            assertThat(line.taxAmount()).isEqualTo("0.13");
            assertThat(line.lineTotal()).isEqualTo("1.38");
        }

        @Test
        void losTotalesSumanImportesYaRedondeados() {
            // Cada línea: impuesto 0.125 → 0.13. Suma de redondeados = 0.26;
            // redondear la suma cruda (0.250) daría 0.25, que NO es la regla del enunciado.
            OrderRequest order = order("PE", item("P-1", 1, "1.25"), item("P-2", 1, "1.25"));
            PricedOrder result = calculator.price(order, client(Segment.RETAIL, TaxRegime.GENERAL, "PE"), Map.of(
                    new ProductId("P-1"), product("P-1", TaxCategory.REDUCED),
                    new ProductId("P-2"), product("P-2", TaxCategory.REDUCED)));
            assertThat(result.totals().tax()).isEqualTo("0.26");
            assertThat(result.totals().grandTotal()).isEqualTo("2.76");
        }

        @Test
        void redondeaElSubtotalBrutoConPreciosDeMasDeDosDecimales() {
            // 3 * 35.555 = 106.665 → 106.67
            PricedLine line = priceSingle("MX", item("P-1", 3, "35.555"), TaxCategory.EXEMPT, Segment.RETAIL, TaxRegime.GENERAL);
            assertThat(line.grossSubtotal()).isEqualTo("106.67");
        }

        @Test
        void redondeaElDescuento() {
            // 21 * 0.25 = 5.25; 5.25 * 3 % = 0.1575 → 0.16
            PricedLine line = priceSingle("CO", item("P-1", 21, "0.25"), TaxCategory.EXEMPT, Segment.WHOLESALE, TaxRegime.GENERAL);
            assertThat(line.discount()).isEqualTo("0.16");
            assertThat(line.netSubtotal()).isEqualTo("5.09");
        }

        @Test
        void todosLosImportesTienenEscala2() {
            PricedLine line = priceSingle("MX", item("P-1", 7, "3.3333"), TaxCategory.STANDARD, Segment.WHOLESALE, TaxRegime.GENERAL);
            for (BigDecimal amount : new BigDecimal[] {line.grossSubtotal(), line.discount(), line.netSubtotal(), line.taxAmount(), line.lineTotal()}) {
                assertThat(amount.scale()).isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Impuestos y descuentos combinados")
    class Combinations {

        @Test
        void clienteExentoNoPagaImpuestoAunqueElProductoSeaStandard() {
            PricedLine line = priceSingle("CO", item("P-1", 5, "12500"), TaxCategory.STANDARD, Segment.RETAIL, TaxRegime.EXEMPT);
            assertThat(line.taxRate()).isEqualByComparingTo("0");
            assertThat(line.taxAmount()).isEqualTo("0.00");
            assertThat(line.lineTotal()).isEqualTo("62500.00");
        }

        @Test
        void elImpuestoSeCalculaSobreElNetoDespuesDelDescuento() {
            // 100 * 10 = 1000; descuento 30; neto 970; impuesto 18 % de 970 = 174.60
            PricedLine line = priceSingle("PE", item("P-1", 100, "10"), TaxCategory.STANDARD, Segment.WHOLESALE, TaxRegime.GENERAL);
            assertThat(line.discount()).isEqualTo("30.00");
            assertThat(line.taxAmount()).isEqualTo("174.60");
            assertThat(line.lineTotal()).isEqualTo("1144.60");
        }

        @Test
        void precioCeroProduceImportesCero() {
            PricedLine line = priceSingle("MX", item("P-1", 50, "0"), TaxCategory.STANDARD, Segment.WHOLESALE, TaxRegime.GENERAL);
            assertThat(line.lineTotal()).isEqualTo("0.00");
        }
    }

    @Test
    void exigeQueTodosLosProductosEstenDisponibles() {
        OrderRequest order = order("MX", item("P-1", 1, "1"));
        assertThatThrownBy(() -> calculator.price(order, client(Segment.RETAIL, TaxRegime.GENERAL, "MX"), Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    private PricedLine priceSingle(String market, com.b2b.orders.domain.validation.OrderDraft.ItemDraft item,
            TaxCategory category, Segment segment, TaxRegime regime) {
        OrderRequest order = order(market, item);
        PricedOrder result = calculator.price(order, client(segment, regime, market),
                Map.of(new ProductId(item.productId()), product(item.productId(), category)));
        return result.lines().getFirst();
    }
}
