package com.b2b.orders.domain.model;

import java.util.Objects;

/** Identificador del cliente. Nunca vacío. */
public record ClientId(String value) {

    public ClientId {
        Objects.requireNonNull(value, "clientId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("clientId no puede estar vacío");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
