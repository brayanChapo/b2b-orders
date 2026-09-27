package com.b2b.orders.domain.model;

import java.util.Objects;

public record OrderId(String value) {

    public OrderId {
        Objects.requireNonNull(value, "orderId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("orderId no puede estar vacío");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
