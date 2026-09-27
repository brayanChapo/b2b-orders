package com.b2b.orders.domain.model;

final class EnumCodes {

    private EnumCodes() {
    }

    static <E extends Enum<E>> E parse(Class<E> type, String code, E unknown) {
        if (code == null) {
            return unknown;
        }
        for (E value : type.getEnumConstants()) {
            if (value != unknown && value.name().equals(code)) {
                return value;
            }
        }
        return unknown;
    }
}
