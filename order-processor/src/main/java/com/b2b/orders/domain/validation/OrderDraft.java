package com.b2b.orders.domain.validation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;


public record OrderDraft(
        String eventId,
        Integer eventVersion,
        Instant occurredAt,
        String orderId,
        String market,
        String currency,
        String clientId,
        String channel,
        List<ItemDraft> items) {

    public record ItemDraft(String productId, Long quantity, BigDecimal unitPrice) {
    }
}
