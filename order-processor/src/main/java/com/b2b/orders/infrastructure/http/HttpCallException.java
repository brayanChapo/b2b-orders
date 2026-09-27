package com.b2b.orders.infrastructure.http;

import java.io.Serial;
import java.time.Duration;
import java.util.Optional;

public class HttpCallException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final String code;
    private final transient Duration retryAfter;

    private HttpCallException(boolean retryable, String code, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public static HttpCallException transientFailure(String code, String message, Duration retryAfter, Throwable cause) {
        return new HttpCallException(true, code, message, retryAfter, cause);
    }

    public static HttpCallException definitiveFailure(String code, String message, Throwable cause) {
        return new HttpCallException(false, code, message, null, cause);
    }

    public boolean retryable() {
        return retryable;
    }

    public String code() {
        return code;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
