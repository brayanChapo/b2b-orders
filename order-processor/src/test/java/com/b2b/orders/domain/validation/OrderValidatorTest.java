package com.b2b.orders.domain.validation;

import static com.b2b.orders.domain.TestData.draft;
import static com.b2b.orders.domain.TestData.goldenDraft;
import static com.b2b.orders.domain.TestData.item;
import static org.assertj.core.api.Assertions.assertThat;

import com.b2b.orders.domain.model.Currency;
import com.b2b.orders.domain.model.Market;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.validation.OrderDraft.ItemDraft;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderValidatorTest {

    private final OrderValidator validator = new OrderValidator();

    private InvalidOrderException invalid(OrderDraft draft) {
        try {
            validator.validate(draft);
        } catch (InvalidOrderException e) {
            return e;
        }
        throw new AssertionError("se esperaba InvalidOrderException");
    }

    private List<Violation> violationsOf(OrderDraft draft) {
        return invalid(draft).violations();
    }

    private List<ViolationCode> codesOf(OrderDraft draft) {
        return violationsOf(draft).stream().map(Violation::code).toList();
    }

    @Test
    void eventoValidoProduceUnPedido() {
        OrderRequest order = validator.validate(goldenDraft());
        assertThat(order.orderId().value()).isEqualTo("ORD-MX-000147");
        assertThat(order.market()).isEqualTo(Market.MX);
        assertThat(order.currency()).isEqualTo(Currency.MXN);
        assertThat(order.items()).hasSize(2);
        assertThat(order.items().getFirst().unitPrice()).isEqualByComparingTo("35.5");
    }

    @ParameterizedTest(name = "{0} con {1} es válido")
    @CsvSource({"MX, MXN", "CO, COP", "PE, PEN"})
    void monedaCoherenteConElMercado(String market, String currency) {
        assertThat(validator.validate(draft(market, currency, item("P-1", 1, "1"))).market().name()).isEqualTo(market);
    }

    @ParameterizedTest(name = "{0} con {1} es inválido")
    @CsvSource({"MX, PEN", "MX, COP", "CO, MXN", "PE, USD"})
    void monedaQueNoCorrespondeAlMercado(String market, String currency) {
        assertThat(codesOf(draft(market, currency, item("P-1", 1, "1"))))
                .containsExactly(ViolationCode.CURRENCY_MARKET_MISMATCH);
    }

    @ParameterizedTest(name = "mercado \"{0}\" no soportado")
    @CsvSource({"AR, ARS", "mx, MXN", "US, USD"})
    void mercadoNoSoportadoNoReportaTambienLaMoneda(String market, String currency) {
        assertThat(codesOf(draft(market, currency, item("P-1", 1, "1"))))
                .containsExactly(ViolationCode.UNSUPPORTED_MARKET);
    }

    @Test
    void camposObligatorios() {
        OrderDraft empty = new OrderDraft(null, null, null, " ", null, null, "", null, null);
        List<Violation> violations = violationsOf(empty);
        assertThat(violations).allMatch(v -> v.code() == ViolationCode.MISSING_FIELD);
        assertThat(violations).extracting(Violation::field)
                .containsExactly("eventId", "orderId", "clientId", "eventVersion", "market", "currency", "items");
    }

    @Test
    void itemsVacio() {
        assertThat(codesOf(draft("MX", "MXN"))).containsExactly(ViolationCode.EMPTY_ITEMS);
    }

    @Test
    void productoRepetido() {
        List<Violation> violations = violationsOf(draft("MX", "MXN", item("P-1", 1, "1"), item("P-1", 2, "1")));
        assertThat(violations).extracting(Violation::code, Violation::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ViolationCode.DUPLICATE_PRODUCT, "items[1].productId"));
    }

    @ParameterizedTest(name = "quantity {0} es inválida")
    @CsvSource({"0", "-1"})
    void cantidadDebeSerMayorQueCero(long quantity) {
        assertThat(codesOf(draft("MX", "MXN", item("P-1", quantity, "1")))).containsExactly(ViolationCode.INVALID_QUANTITY);
    }

    @Test
    void precioNegativo() {
        assertThat(codesOf(draft("MX", "MXN", item("P-1", 1, "-0.01")))).containsExactly(ViolationCode.NEGATIVE_UNIT_PRICE);
    }

    @Test
    void precioCeroEsValido() {
        assertThat(validator.validate(draft("MX", "MXN", item("P-1", 1, "0"))).items()).hasSize(1);
    }

    @Test
    void eventVersionMenorQueUno() {
        OrderDraft golden = goldenDraft();
        OrderDraft invalid = new OrderDraft(golden.eventId(), 0, null, golden.orderId(), "MX", "MXN",
                golden.clientId(), null, golden.items());
        assertThat(codesOf(invalid)).containsExactly(ViolationCode.INVALID_EVENT_VERSION);
    }

    @Test
    void camposFaltantesDentroDeUnaLinea() {
        List<ItemDraft> items = new ArrayList<>(Arrays.asList(new ItemDraft(null, null, null), null));
        List<Violation> violations = violationsOf(draft("MX", "MXN", items.toArray(ItemDraft[]::new)));
        assertThat(violations).extracting(Violation::field)
                .containsExactly("items[0].productId", "items[0].quantity", "items[0].unitPrice", "items[1]");
    }

    @Test
    void reportaTodasLasViolacionesYNoSoloLaPrimera() {
        OrderDraft draft = new OrderDraft("EVT-1", 1, null, "ORD-1", "MX", "PEN", "CLI-1", null, List.of(
                item("P-1", 0, "1"),
                new ItemDraft("P-2", 1L, new BigDecimal("-5")),
                item("P-1", 1, "1")));
        assertThat(codesOf(draft)).containsExactly(
                ViolationCode.CURRENCY_MARKET_MISMATCH,
                ViolationCode.INVALID_QUANTITY,
                ViolationCode.NEGATIVE_UNIT_PRICE,
                ViolationCode.DUPLICATE_PRODUCT);
    }

    @Test
    void laViolacionPrincipalEsLaPrimera() {
        InvalidOrderException error = invalid(draft("MX", "PEN", item("P-1", 0, "1")));
        assertThat(error.primary().code()).isEqualTo(ViolationCode.CURRENCY_MARKET_MISMATCH);
    }
}
