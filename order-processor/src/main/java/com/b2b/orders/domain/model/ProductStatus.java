package com.b2b.orders.domain.model;

public enum ProductStatus {
    ACTIVE, DISCONTINUED, UNKNOWN;

    public static ProductStatus fromCode(String code) {
        return EnumCodes.parse(ProductStatus.class, code, UNKNOWN);
    }
}
