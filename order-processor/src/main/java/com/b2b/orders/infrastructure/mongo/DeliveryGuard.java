package com.b2b.orders.infrastructure.mongo;

import static com.mongodb.client.model.Filters.eq;

import com.b2b.orders.infrastructure.kafka.DeliveryKey;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detecta mensajes veneno (architecture-proposal §5.6). Una entrega que encuentra su propio
 * registro todavía IN_PROGRESS significa que el proceso murió a mitad del intento anterior.
 * Los fallos manejados (RETRYING) no cuentan: esos los gestiona el DefaultErrorHandler.
 */
public class DeliveryGuard {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String RETRYING = "RETRYING";
    public static final String DONE = "DONE";
    public static final String DEAD_LETTERED = "DEAD_LETTERED";

    public record Delivery(boolean poison, int crashes) {
    }

    private static final Logger log = LoggerFactory.getLogger(DeliveryGuard.class);

    private final MongoCollection<Document> attempts;
    private final int maxCrashes;

    public DeliveryGuard(MongoDatabase db, int maxCrashes) {
        this.attempts = db.getCollection(MongoSchema.DELIVERY_ATTEMPTS);
        this.maxCrashes = maxCrashes;
    }

    public Delivery begin(DeliveryKey key, Optional<String> eventId, Optional<String> orderId, Instant now) {
        Document current = attempts.find(eq("_id", key.id())).first();
        int crashes = 0;
        if (current != null) {
            crashes = current.getInteger("crashes", 0) + (IN_PROGRESS.equals(current.getString("status")) ? 1 : 0);
        }
        if (crashes >= maxCrashes) {
            attempts.updateOne(eq("_id", key.id()), Updates.combine(
                    Updates.set("crashes", crashes), Updates.set("lastSeenAt", Date.from(now))));
            log.error("poison_message crashes={}", crashes);
            return new Delivery(true, crashes);
        }
        List<Bson> updates = new ArrayList<>(List.of(
                Updates.set("status", IN_PROGRESS),
                Updates.set("crashes", crashes),
                Updates.set("lastSeenAt", Date.from(now)),
                Updates.setOnInsert("firstSeenAt", Date.from(now))));
        eventId.ifPresent(id -> updates.add(Updates.set("eventId", id)));
        orderId.ifPresent(id -> updates.add(Updates.set("orderId", id)));
        attempts.updateOne(eq("_id", key.id()), Updates.combine(updates), new UpdateOptions().upsert(true));
        if (crashes > 0) {
            log.warn("redelivery_after_crash crashes={}", crashes);
        }
        return new Delivery(false, crashes);
    }

    public void finish(DeliveryKey key, String status, Instant now) {
        attempts.updateOne(eq("_id", key.id()),
                Updates.combine(Updates.set("status", status), Updates.set("lastSeenAt", Date.from(now))));
    }

    public void finishQuietly(DeliveryKey key, String status, Instant now) {
        try {
            finish(key, status, now);
        } catch (RuntimeException e) {
            log.warn("delivery_status_not_updated status={} error={}", status, e.toString());
        }
    }
}
