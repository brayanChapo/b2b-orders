package com.b2b.orders.domain.model;

import java.util.Arrays;
import java.util.Optional;

public enum Market {
    MX(Currency.MXN),
    CO(Currency.COP),
    PE(Currency.PEN);

    private final Currency currency;

    Market(Currency currency) {
        this.currency = currency;
    }

    public Currency currency() {
        return currency;
    }

    /** Coincidencia exacta: el contrato define los códigos en mayúsculas. */
    public static Optional<Market> fromCode(String code) {
        return Arrays.stream(values()).filter(m -> m.name().equals(code)).findFirst();
    }
}
