package com.b2b.orders.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.b2b.orders.application.ErrorCategory;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeadLetterHeadersTest {

    private static DeadLetter letter(Optional<String> orderId, String summary) {
        return new DeadLetter("orders.created.v1", 2, 18841, "ORD-1", "{}", orderId, Optional.of("E1"),
                ErrorCategory.VALIDATION_ERROR, "CURRENCY_MARKET_MISMATCH", summary, 1,
                Instant.parse("2026-09-18T15:42:10Z"));
    }

    @Test
    void incluyeLosHeadersObligatoriosDelContrato() {
        Map<String, String> headers = DeadLetterHeaders.of(letter(Optional.of("ORD-1"), "moneda"), "order-processor@0.1.0");
        assertThat(headers).containsEntry("dlt-order-id", "ORD-1")
                .containsEntry("dlt-event-id", "E1")
                .containsEntry("dlt-error-category", "VALIDATION_ERROR")
                .containsEntry("dlt-error-code", "CURRENCY_MARKET_MISMATCH")
                .containsEntry("dlt-attempts", "1")
                .containsEntry("dlt-failed-at", "2026-09-18T15:42:10Z")
                .containsEntry("dlt-component", "order-processor@0.1.0")
                .containsEntry("dlt-original-topic", "orders.created.v1")
                .containsEntry("dlt-original-partition", "2")
                .containsEntry("dlt-original-offset", "18841");
    }

    @Test
    void omiteIdentificadoresQueNoSePudieronObtener() {
        assertThat(DeadLetterHeaders.of(letter(Optional.empty(), "x"), "c")).doesNotContainKey("dlt-order-id");
    }

    @Test
    void laCausaSeLimpiaYSeTrunca() {
        assertThat(DeadLetterHeaders.sanitize("línea 1\nlínea 2\t")).isEqualTo("línea 1 línea 2");
        assertThat(DeadLetterHeaders.sanitize("x".repeat(900))).hasSize(500).endsWith("...");
        assertThat(DeadLetterHeaders.sanitize(null)).isEqualTo("sin detalle");
    }
}
