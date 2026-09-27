package com.b2b.orders.infrastructure.mongo;

import com.b2b.orders.application.EventMetadata;
import com.b2b.orders.application.ProcessedOrder;
import com.b2b.orders.application.ProcessingStatus;
import com.b2b.orders.application.StoredOrderState;
import com.b2b.orders.application.TechnicalError;
import com.b2b.orders.domain.eligibility.RejectionReason;
import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.EventId;
import com.b2b.orders.domain.model.OrderItem;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.pricing.OrderTotals;
import com.b2b.orders.domain.pricing.PricedLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;

/**
 * Esquema de la colección orders. Un documento por pedido con el resultado vigente.
 * Importes en Decimal128; schemaVersion permite migrar documentos de forma perezosa.
 */
public final class OrderDocumentMapper {

    public static final int SCHEMA_VERSION = 1;

    private OrderDocumentMapper() {
    }

    public static Document toDocument(ProcessedOrder result) {
        OrderRequest order = result.order();
        EventMetadata meta = result.metadata();
        Document doc = new Document("_id", order.orderId().value())
                .append("eventId", order.eventId().value())
                .append("eventVersion", order.eventVersion())
                .append("status", result.status().name())
                .append("market", order.market().name())
                .append("currency", order.currency().name())
                .append("clientId", order.clientId().value())
                .append("channel", order.channel())
                .append("client", result.clientIfKnown().map(OrderDocumentMapper::client).orElse(null))
                .append("items", order.items().stream().map(OrderDocumentMapper::item).toList())
                .append("lines", result.pricing() == null ? List.of() : result.pricing().lines().stream().map(OrderDocumentMapper::line).toList())
                .append("totals", result.pricing() == null ? null : totals(result.pricing().totals()))
                .append("reason", result.primaryReason().map(r -> r.code().name()).orElse(null))
                .append("reasons", result.reasons().stream().map(OrderDocumentMapper::reason).toList())
                .append("error", result.error() == null ? null : error(result.error()))
                .append("occurredAt", order.occurredAtIfPresent().map(Date::from).orElse(null))
                .append("receivedAt", Date.from(meta.receivedAt()))
                .append("processedAt", Date.from(result.processedAt()))
                .append("trace", new Document("topic", meta.topic())
                        .append("partition", meta.partition())
                        .append("offset", meta.offset())
                        .append("traceId", meta.traceId()))
                .append("schemaVersion", SCHEMA_VERSION);
        return doc;
    }

    public static StoredOrderState toState(Document doc) {
        return new StoredOrderState(
                new EventId(doc.getString("eventId")),
                doc.getInteger("eventVersion"),
                ProcessingStatus.valueOf(doc.getString("status")));
    }

    private static Document client(Client c) {
        return new Document("clientId", c.id().value())
                .append("name", c.name())
                .append("status", c.status().name())
                .append("segment", c.segment().name())
                .append("taxRegime", c.taxRegime().name())
                .append("market", c.marketCode());
    }

    private static Document item(OrderItem item) {
        return new Document("productId", item.productId().value())
                .append("quantity", item.quantity())
                .append("unitPrice", decimal(item.unitPrice()));
    }

    private static Document line(PricedLine line) {
        return new Document("productId", line.productId().value())
                .append("name", line.name())
                .append("sku", line.sku())
                .append("taxCategory", line.taxCategory().name())
                .append("quantity", line.quantity())
                .append("unitPrice", decimal(line.unitPrice()))
                .append("discountRate", decimal(line.discountRate()))
                .append("taxRate", decimal(line.taxRate()))
                .append("grossSubtotal", decimal(line.grossSubtotal()))
                .append("discount", decimal(line.discount()))
                .append("netSubtotal", decimal(line.netSubtotal()))
                .append("taxAmount", decimal(line.taxAmount()))
                .append("lineTotal", decimal(line.lineTotal()));
    }

    private static Document totals(OrderTotals totals) {
        return new Document("grossSubtotal", decimal(totals.grossSubtotal()))
                .append("discount", decimal(totals.discount()))
                .append("netSubtotal", decimal(totals.netSubtotal()))
                .append("tax", decimal(totals.tax()))
                .append("grandTotal", decimal(totals.grandTotal()));
    }

    private static Document reason(RejectionReason reason) {
        return new Document("code", reason.code().name())
                .append("productId", reason.productIdIfPresent().map(p -> p.value()).orElse(null))
                .append("detail", reason.detail());
    }

    private static Document error(TechnicalError error) {
        return new Document("category", error.category().name())
                .append("code", error.code())
                .append("summary", error.summary())
                .append("attempts", error.attempts());
    }

    private static Decimal128 decimal(BigDecimal value) {
        return new Decimal128(value);
    }

    static Date date(Instant instant) {
        return Date.from(instant);
    }
}
