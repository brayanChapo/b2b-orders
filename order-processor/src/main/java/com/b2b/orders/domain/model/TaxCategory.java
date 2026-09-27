package com.b2b.orders.domain.model;

public enum TaxCategory {
    STANDARD, REDUCED, EXEMPT, UNKNOWN;

    public static TaxCategory fromCode(String code) {
        return EnumCodes.parse(TaxCategory.class, code, UNKNOWN);
    }
}
