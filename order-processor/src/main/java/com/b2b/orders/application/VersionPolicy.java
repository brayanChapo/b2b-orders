package com.b2b.orders.application;

import com.b2b.orders.domain.model.EventId;
import java.util.Optional;

public final class VersionPolicy {

    private VersionPolicy() {
    }

    /** Vacío si el evento puede escribirse; si no, el motivo por el que se descarta. */
    public static Optional<StoreOutcome> classify(StoredOrderState current, EventId eventId, int eventVersion) {
        if (current.eventVersion() > eventVersion) {
            return Optional.of(StoreOutcome.STALE);
        }
        if (current.eventVersion() < eventVersion) {
            return Optional.empty();
        }
        if (!current.eventId().equals(eventId)) {
            return Optional.of(StoreOutcome.CONFLICT);
        }
        return current.status() == ProcessingStatus.TECHNICAL_FAILURE
                ? Optional.empty()
                : Optional.of(StoreOutcome.DUPLICATE);
    }
}
