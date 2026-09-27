package com.b2b.orders.infrastructure.http;

import com.b2b.orders.application.port.DependencyException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Reintenta solo errores transitorios (429, 5xx, timeout, conexión), con backoff exponencial
 * y jitter. 429 respeta Retry-After hasta maxRetryAfter; si pide esperar más, no se reintenta.
 */
public class HttpRetrier {

    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public interface RetryListener {
        void onRetry(String dependency, String code, int attempt);
    }

    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final DoubleSupplier random;
    private final RetryListener listener;

    public HttpRetrier(RetryPolicy policy, RetryListener listener) {
        this(policy, d -> Thread.sleep(d), () -> ThreadLocalRandom.current().nextDouble(), listener);
    }

    HttpRetrier(RetryPolicy policy, Sleeper sleeper, DoubleSupplier random, RetryListener listener) {
        this.policy = policy;
        this.sleeper = sleeper;
        this.random = random;
        this.listener = listener;
    }

    public <T> T execute(String dependency, Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            final int current = attempt;
            try {
                return call.get();
            } catch (HttpCallException e) {
                if (!e.retryable()) {
                    throw new DependencyException(DependencyException.Kind.DEFINITIVE, dependency, e.code(),
                            e.getMessage(), attempt, e);
                }
                Duration wait = e.retryAfter().orElseGet(() -> policy.backoff(current, random.getAsDouble()));
                if (attempt >= policy.maxAttempts() || wait.compareTo(policy.maxRetryAfter()) > 0) {
                    throw new DependencyException(DependencyException.Kind.TRANSIENT, dependency, e.code(),
                            e.getMessage() + " (intentos: " + attempt + ")", attempt, e);
                }
                listener.onRetry(dependency, e.code(), attempt);
                pause(dependency, wait, attempt);
            }
        }
    }

    private void pause(String dependency, Duration wait, int attempt) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyException(DependencyException.Kind.TRANSIENT, dependency, "INTERRUPTED",
                    "Reintento interrumpido", attempt, e);
        }
    }
}
