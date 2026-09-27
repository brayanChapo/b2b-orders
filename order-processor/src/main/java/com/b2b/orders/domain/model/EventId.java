package com.b2b.orders.domain.model;

import java.util.Objects;

public record EventId(String value) {

    public EventId {
        Objects.requireNonNull(value, "eventId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("eventId no puede estar vacío");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
