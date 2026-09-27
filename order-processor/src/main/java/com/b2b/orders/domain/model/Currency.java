package com.b2b.orders.domain.model;

import java.util.Arrays;
import java.util.Optional;

public enum Currency {
    MXN, COP, PEN;

    public static Optional<Currency> fromCode(String code) {
        return Arrays.stream(values()).filter(c -> c.name().equals(code)).findFirst();
    }
}
