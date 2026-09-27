package com.b2b.orders.infrastructure.mongo;

import com.b2b.orders.application.outbox.OutboxMessage;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bson.Document;

public final class OutboxDocuments {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";

    private OutboxDocuments() {
    }

    public static Document pending(String id, String topic, String key, String payload,
            Map<String, String> headers, Instant now) {
        return new Document("_id", id)
                .append("topic", topic)
                .append("key", key)
                .append("payload", payload)
                .append("headers", new Document(new LinkedHashMap<String, Object>(headers)))
                .append("status", PENDING)
                .append("attempts", 0)
                .append("createdAt", Date.from(now))
                .append("lockedUntil", Date.from(now));
    }

    public static OutboxMessage toMessage(Document doc) {
        Map<String, String> headers = new LinkedHashMap<>();
        Document stored = doc.get("headers", Document.class);
        if (stored != null) {
            stored.forEach((k, v) -> headers.put(k, String.valueOf(v)));
        }
        return new OutboxMessage(
                doc.getString("_id"),
                doc.getString("topic"),
                doc.getString("key"),
                doc.getString("payload"),
                headers,
                doc.getInteger("attempts", 0));
    }
}
