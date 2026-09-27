package com.b2b.orders.infrastructure.http;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

/**
 * Traduce respuestas HTTP a resultado o HttpCallException:
 * 200 → cuerpo, 404 → vacío, 429 y 5xx → transitorio, resto de 4xx → definitivo.
 */
final class HttpResponses {

    static final String TRACE_HEADER = "X-Request-Id";

    private HttpResponses() {
    }

    static <T> Optional<T> read(ConvertibleClientHttpResponse response, Class<T> type, String prefix) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 200) {
            T body;
            try {
                body = response.bodyTo(type);
            } catch (RuntimeException e) {
                throw HttpCallException.definitiveFailure(prefix + "_INVALID_BODY", "Respuesta 200 fuera de contrato", e);
            }
            if (body == null) {
                throw HttpCallException.definitiveFailure(prefix + "_INVALID_BODY", "Respuesta 200 sin cuerpo", null);
            }
            return Optional.of(body);
        }
        if (status == 404) {
            return Optional.empty();
        }
        if (status == 429) {
            throw HttpCallException.transientFailure(prefix + "_429", "Límite de solicitudes (429)",
                    retryAfter(response), null);
        }
        if (status >= 500) {
            throw HttpCallException.transientFailure(prefix + "_" + status, "Error del servidor (" + status + ")", null, null);
        }
        throw HttpCallException.definitiveFailure(prefix + "_" + status, "Respuesta no reintentable (" + status + ")", null);
    }

    static <T> T guard(String prefix, Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw HttpCallException.transientFailure(prefix + "_UNAVAILABLE",
                    "Sin respuesta: " + e.getMostSpecificCause().getClass().getSimpleName(), null, e);
        }
    }

    static void trace(HttpHeaders headers) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            headers.set(TRACE_HEADER, traceId);
        }
    }

    private static Duration retryAfter(ClientHttpResponse response) {
        String value = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
