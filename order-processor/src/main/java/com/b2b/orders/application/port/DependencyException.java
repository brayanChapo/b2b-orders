package com.b2b.orders.application.port;

import java.io.Serial;

public class DependencyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Kind {
        TRANSIENT, DEFINITIVE
    }

    private final Kind kind;
    private final String dependency;
    private final String code;
    private final int attempts;

    public DependencyException(Kind kind, String dependency, String code, String message, int attempts, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.dependency = dependency;
        this.code = code;
        this.attempts = attempts;
    }

    public Kind kind() {
        return kind;
    }

    public String dependency() {
        return dependency;
    }

    public String code() {
        return code;
    }

    public int attempts() {
        return attempts;
    }

    public boolean isTransient() {
        return kind == Kind.TRANSIENT;
    }
}
