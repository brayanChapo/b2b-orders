package com.b2b.orders.domain.model;

public enum TaxRegime {
    GENERAL, SIMPLIFIED, EXEMPT, UNKNOWN;

    public static TaxRegime fromCode(String code) {
        return EnumCodes.parse(TaxRegime.class, code, UNKNOWN);
    }
}
