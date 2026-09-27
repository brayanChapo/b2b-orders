package com.b2b.orders.domain.model;

public enum Segment {
    WHOLESALE, RETAIL, UNKNOWN;

    public static Segment fromCode(String code) {
        return EnumCodes.parse(Segment.class, code, UNKNOWN);
    }
}
