package com.b2b.orders.infrastructure.kafka;

import java.util.LinkedHashMap;
import java.util.Map;

/** Headers de orders.processing.dlt (contracts/events/orders.processing.dlt.headers.schema.json). */
public final class DeadLetterHeaders {

    static final int MAX_SUMMARY_LENGTH = 500;

    private DeadLetterHeaders() {
    }

    public static Map<String, String> of(DeadLetter letter, String component) {
        Map<String, String> headers = new LinkedHashMap<>();
        letter.orderId().ifPresent(v -> headers.put("dlt-order-id", v));
        letter.eventId().ifPresent(v -> headers.put("dlt-event-id", v));
        headers.put("dlt-error-category", letter.category().name());
        headers.put("dlt-error-code", letter.code());
        headers.put("dlt-error-summary", sanitize(letter.summary()));
        headers.put("dlt-attempts", Integer.toString(Math.max(1, letter.attempts())));
        headers.put("dlt-failed-at", letter.failedAt().toString());
        headers.put("dlt-component", component);
        headers.put("dlt-original-topic", letter.originalTopic());
        headers.put("dlt-original-partition", Integer.toString(letter.originalPartition()));
        headers.put("dlt-original-offset", Long.toString(letter.originalOffset()));
        return headers;
    }

    static String sanitize(String summary) {
        String clean = summary == null || summary.isBlank()
                ? "sin detalle"
                : summary.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.length() <= MAX_SUMMARY_LENGTH ? clean : clean.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
    }
}
