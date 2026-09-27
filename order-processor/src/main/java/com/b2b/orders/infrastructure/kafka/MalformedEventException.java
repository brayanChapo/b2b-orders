package com.b2b.orders.infrastructure.kafka;

import java.io.Serial;

public class MalformedEventException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String code;

    public MalformedEventException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
