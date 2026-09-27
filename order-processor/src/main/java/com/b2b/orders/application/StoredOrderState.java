package com.b2b.orders.application;

import com.b2b.orders.domain.model.EventId;

public record StoredOrderState(EventId eventId, int eventVersion, ProcessingStatus status) {
}
