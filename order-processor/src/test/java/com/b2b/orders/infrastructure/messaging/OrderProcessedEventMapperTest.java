package com.b2b.orders.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.b2b.orders.application.ErrorCategory;
import com.b2b.orders.application.EventMetadata;
import com.b2b.orders.application.ProcessedOrder;
import com.b2b.orders.application.TechnicalError;
import com.b2b.orders.domain.TestData;
import com.b2b.orders.domain.eligibility.RejectionCode;
import com.b2b.orders.domain.eligibility.RejectionReason;
import com.b2b.orders.domain.pricing.DiscountPolicy;
import com.b2b.orders.domain.pricing.OrderCalculator;
import com.b2b.orders.domain.pricing.TaxPolicy;
import com.b2b.orders.infrastructure.Contracts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderProcessedEventMapperTest {

    private static final Instant AT = Instant.parse("2026-09-18T15:42:11Z");
    private final ObjectMapper json = new ObjectMapper();
    private final OrderProcessedEventMapper mapper = new OrderProcessedEventMapper();
    private final EventMetadata metadata = new EventMetadata("orders.created.v1", 0, 1, AT, "t", "{}");

    private ProcessedOrder approvedGolden() {
        var pricing = new OrderCalculator(new TaxPolicy(), new DiscountPolicy())
                .price(TestData.goldenOrder(), TestData.goldenClient(), TestData.goldenProducts());
        return ProcessedOrder.approved(TestData.goldenOrder(), TestData.goldenClient(), pricing, metadata, AT);
    }

    @Test
    void elEventoAprobadoCoincideConElEjemploDelContrato() throws Exception {
        JsonNode actual = json.readTree(mapper.toJson(approvedGolden(), "01J8ZP6N2B7C8D9E0F1G2H3J4K"));
        JsonNode expected = json.readTree(Contracts.read("events/examples/orders.processed.v1/approved-mx-golden.json"));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void losImportesConservanDosDecimales() {
        assertThat(mapper.toJson(approvedGolden(), "E")).contains("\"grossSubtotal\":1836.00", "\"grandTotal\":2100.11");
    }

    @Test
    void elRechazoPublicaTotalesNulosYLaRazonPrincipal() throws Exception {
        ProcessedOrder rejected = ProcessedOrder.rejected(TestData.goldenOrder(), null,
                List.of(RejectionReason.ofClient(RejectionCode.CLIENT_NOT_FOUND, "no existe")), metadata, AT);

        ObjectNode event = (ObjectNode) json.readTree(mapper.toJson(rejected, "E"));

        assertThat(event.get("status").asText()).isEqualTo("REJECTED");
        assertThat(event.get("totals").isNull()).isTrue();
        assertThat(event.get("reason").asText()).isEqualTo("CLIENT_NOT_FOUND");
        assertThat(event.get("reasons").get(0).has("productId")).isFalse();
    }

    @Test
    void unFalloTecnicoNoSePublicaEnElTopicoDeSalida() {
        ProcessedOrder failure = ProcessedOrder.technicalFailure(TestData.goldenOrder(), null,
                new TechnicalError(ErrorCategory.TRANSIENT_DEPENDENCY_EXHAUSTED, "X", "x", 3), metadata, AT);
        assertThatThrownBy(() -> mapper.toJson(failure, "E")).isInstanceOf(IllegalArgumentException.class);
    }
}
