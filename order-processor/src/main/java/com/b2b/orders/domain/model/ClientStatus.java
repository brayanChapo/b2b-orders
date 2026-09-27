package com.b2b.orders.domain.model;

public enum ClientStatus {
    ACTIVE, BLOCKED, UNKNOWN;

    public static ClientStatus fromCode(String code) {
        return EnumCodes.parse(ClientStatus.class, code, UNKNOWN);
    }
}
