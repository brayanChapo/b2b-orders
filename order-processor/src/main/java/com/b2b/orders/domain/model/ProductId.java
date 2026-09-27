package com.b2b.orders.domain.model;

import java.util.Objects;

public record ProductId(String value) {

    public ProductId {
        Objects.requireNonNull(value, "productId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("productId no puede estar vacío");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
