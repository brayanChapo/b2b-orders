package com.b2b.orders.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.b2b.orders.application.port.DependencyException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class HttpRetrierTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final List<Integer> retries = new ArrayList<>();
    private final RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(200), 2.0, 0.2, Duration.ofSeconds(2));
    private final HttpRetrier retrier = new HttpRetrier(policy, sleeps::add, () -> 0.5,
            (dependency, code, attempt) -> retries.add(attempt));

    private static Supplier<String> sequence(Object... results) {
        Deque<Object> queue = new ArrayDeque<>(List.of(results));
        return () -> {
            Object next = queue.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (String) next;
        };
    }

    private static HttpCallException transientError() {
        return HttpCallException.transientFailure("API_503", "503", null, null);
    }

    @Test
    void devuelveElResultadoSinReintentarSiNoHayError() {
        assertThat(retrier.execute("api", () -> "ok")).isEqualTo("ok");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void reintentaErroresTransitoriosConBackoffExponencial() {
        assertThat(retrier.execute("api", sequence(transientError(), transientError(), "ok"))).isEqualTo("ok");
        assertThat(sleeps).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
        assertThat(retries).containsExactly(1, 2);
    }

    @Test
    void agotaLosIntentosYReportaErrorTransitorio() {
        assertThatThrownBy(() -> retrier.execute("products-api",
                sequence(transientError(), transientError(), transientError())))
                .isInstanceOfSatisfying(DependencyException.class, e -> {
                    assertThat(e.isTransient()).isTrue();
                    assertThat(e.attempts()).isEqualTo(3);
                    assertThat(e.dependency()).isEqualTo("products-api");
                    assertThat(e.code()).isEqualTo("API_503");
                });
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void nuncaReintentaErroresDefinitivos() {
        HttpCallException definitive = HttpCallException.definitiveFailure("API_400", "400", null);
        assertThatThrownBy(() -> retrier.execute("api", sequence(definitive, "ok")))
                .isInstanceOfSatisfying(DependencyException.class, e -> {
                    assertThat(e.isTransient()).isFalse();
                    assertThat(e.attempts()).isEqualTo(1);
                });
        assertThat(sleeps).isEmpty();
    }

    @Test
    void respetaRetryAfterDentroDelLimite() {
        HttpCallException rateLimited = HttpCallException.transientFailure("API_429", "429", Duration.ofSeconds(1), null);
        assertThat(retrier.execute("api", sequence(rateLimited, "ok"))).isEqualTo("ok");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void noEsperaSiRetryAfterSuperaElLimite() {
        HttpCallException rateLimited = HttpCallException.transientFailure("API_429", "429", Duration.ofSeconds(30), null);
        assertThatThrownBy(() -> retrier.execute("api", sequence(rateLimited, "ok")))
                .isInstanceOfSatisfying(DependencyException.class, e -> assertThat(e.attempts()).isEqualTo(1));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void elJitterVariaElBackoffDentroDelRango() {
        assertThat(policy.backoff(1, 0.0)).isEqualTo(Duration.ofMillis(160));
        assertThat(policy.backoff(1, 1.0)).isEqualTo(Duration.ofMillis(240));
        assertThat(policy.backoff(2, 0.5)).isEqualTo(Duration.ofMillis(400));
    }

    @Test
    void unaInterrupcionDetieneLosReintentos() {
        HttpRetrier interrupted = new HttpRetrier(policy, d -> {
            throw new InterruptedException();
        }, () -> 0.5, (a, b, c) -> {
        });
        assertThatThrownBy(() -> interrupted.execute("api", sequence(transientError(), "ok")))
                .isInstanceOfSatisfying(DependencyException.class, e -> assertThat(e.code()).isEqualTo("INTERRUPTED"));
        assertThat(Thread.interrupted()).isTrue();
    }
}
