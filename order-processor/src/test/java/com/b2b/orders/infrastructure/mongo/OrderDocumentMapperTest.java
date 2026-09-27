package com.b2b.orders.infrastructure.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.b2b.orders.application.EventMetadata;
import com.b2b.orders.application.ProcessedOrder;
import com.b2b.orders.application.ProcessingStatus;
import com.b2b.orders.application.StoredOrderState;
import com.b2b.orders.domain.TestData;
import com.b2b.orders.domain.pricing.DiscountPolicy;
import com.b2b.orders.domain.pricing.OrderCalculator;
import com.b2b.orders.domain.pricing.TaxPolicy;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

class OrderDocumentMapperTest {

    private static final Instant AT = Instant.parse("2026-09-18T15:42:11Z");

    private final ProcessedOrder approved = ProcessedOrder.approved(TestData.goldenOrder(), TestData.goldenClient(),
            new OrderCalculator(new TaxPolicy(), new DiscountPolicy())
                    .price(TestData.goldenOrder(), TestData.goldenClient(), TestData.goldenProducts()),
            new EventMetadata("orders.created.v1", 2, 18841, AT.minusSeconds(1), "trace-1", "{}"), AT);

    @Test
    void usaElOrderIdComoIdentificadorYGuardaLaTrazabilidad() {
        Document doc = OrderDocumentMapper.toDocument(approved);

        assertThat(doc.getString("_id")).isEqualTo("ORD-MX-000147");
        assertThat(doc.getString("eventId")).isEqualTo("01J8ZP6M5E4RH0K7Y2N9A3TQWX");
        assertThat(doc.getInteger("eventVersion")).isEqualTo(1);
        assertThat(doc.getString("status")).isEqualTo("APPROVED");
        Document trace = doc.get("trace", Document.class);
        assertThat(trace.getInteger("partition")).isEqualTo(2);
        assertThat(trace.getLong("offset")).isEqualTo(18841L);
        assertThat(doc.getInteger("schemaVersion")).isEqualTo(1);
    }

    @Test
    void losImportesSeGuardanComoDecimal128Exactos() {
        Document totals = OrderDocumentMapper.toDocument(approved).get("totals", Document.class);
        assertThat(totals.get("grandTotal", Decimal128.class).bigDecimalValue()).isEqualTo("2100.11");
        assertThat(totals.get("discount", Decimal128.class).bigDecimalValue()).isEqualTo("25.56");
    }

    @Test
    void guardaLasLineasEnriquecidasConLasTasasAplicadas() {
        @SuppressWarnings("unchecked")
        List<Document> lines = (List<Document>) OrderDocumentMapper.toDocument(approved).get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getString("sku")).isEqualTo("BEB-600-PET");
        assertThat(lines.get(0).get("taxRate", Decimal128.class).bigDecimalValue()).isEqualByComparingTo("0.16");
    }

    @Test
    void reconstruyeElEstadoParaElControlDeVersiones() {
        StoredOrderState state = OrderDocumentMapper.toState(OrderDocumentMapper.toDocument(approved));
        assertThat(state.eventId().value()).isEqualTo("01J8ZP6M5E4RH0K7Y2N9A3TQWX");
        assertThat(state.eventVersion()).isEqualTo(1);
        assertThat(state.status()).isEqualTo(ProcessingStatus.APPROVED);
    }
}
