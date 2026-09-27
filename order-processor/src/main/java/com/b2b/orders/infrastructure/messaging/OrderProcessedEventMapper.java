package com.b2b.orders.infrastructure.messaging;

import com.b2b.orders.application.ProcessedOrder;
import com.b2b.orders.application.ProcessingStatus;
import com.b2b.orders.domain.eligibility.RejectionReason;
import com.b2b.orders.domain.pricing.OrderTotals;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Construye orders.processed.v1 (contracts/events/orders.processed.v1.schema.json). */
public class OrderProcessedEventMapper {

    public static final int EVENT_SCHEMA_VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper()
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    public String toJson(ProcessedOrder result, String outputEventId) {
        if (result.status() == ProcessingStatus.TECHNICAL_FAILURE) {
            throw new IllegalArgumentException("TECHNICAL_FAILURE no se publica en orders.processed.v1");
        }
        var order = result.order();
        ObjectNode event = mapper.createObjectNode();
        event.put("eventId", outputEventId);
        event.put("eventVersion", order.eventVersion());
        event.put("occurredAt", result.processedAt().toString());
        event.put("sourceEventId", order.eventId().value());
        event.put("sourceEventVersion", order.eventVersion());
        event.put("orderId", order.orderId().value());
        event.put("clientId", order.clientId().value());
        event.put("status", result.status().name());
        event.put("market", order.market().name());
        event.put("currency", order.currency().name());

        if (result.status() == ProcessingStatus.APPROVED) {
            OrderTotals totals = result.pricing().totals();
            ObjectNode node = event.putObject("totals");
            node.put("grossSubtotal", totals.grossSubtotal());
            node.put("discount", totals.discount());
            node.put("netSubtotal", totals.netSubtotal());
            node.put("tax", totals.tax());
            node.put("grandTotal", totals.grandTotal());
        } else {
            event.putNull("totals");
        }

        result.primaryReason().ifPresentOrElse(
                reason -> event.put("reason", reason.code().name()),
                () -> event.putNull("reason"));
        ArrayNode reasons = event.putArray("reasons");
        for (RejectionReason reason : result.reasons()) {
            ObjectNode node = reasons.addObject();
            node.put("code", reason.code().name());
            reason.productIdIfPresent().ifPresent(id -> node.put("productId", id.value()));
            node.put("detail", reason.detail());
        }

        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar orders.processed.v1", e);
        }
    }
}
