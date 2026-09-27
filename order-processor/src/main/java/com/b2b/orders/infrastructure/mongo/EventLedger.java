package com.b2b.orders.infrastructure.mongo;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registro de cada evento recibido (order_events, _id = eventId) para investigar un pedido.
 * outcome guarda el primer resultado; lastOutcome y deliveries, las entregas posteriores.
 */
public class EventLedger {

    private static final Logger log = LoggerFactory.getLogger(EventLedger.class);

    private final MongoCollection<Document> events;

    public EventLedger(MongoDatabase db) {
        this.events = db.getCollection(MongoSchema.ORDER_EVENTS);
    }

    /** Fuera de la transacción de resultado: si falla, solo se pierde trazabilidad. */
    public void record(String eventId, String orderId, Integer eventVersion, String outcome, Instant at) {
        try {
            events.updateOne(eq("_id", eventId), update(orderId, eventVersion, outcome, at), new UpdateOptions().upsert(true));
        } catch (RuntimeException e) {
            log.warn("ledger_write_failed eventId={} error={}", eventId, e.toString());
        }
    }

    void record(ClientSession session, String eventId, String orderId, int eventVersion, String outcome, Instant at) {
        events.updateOne(session, eq("_id", eventId), update(orderId, eventVersion, outcome, at), new UpdateOptions().upsert(true));
    }

    private static Bson update(String orderId, Integer eventVersion, String outcome, Instant at) {
        List<Bson> updates = new ArrayList<>();
        updates.add(Updates.setOnInsert("outcome", outcome));
        updates.add(Updates.setOnInsert("firstSeenAt", Date.from(at)));
        updates.add(Updates.set("lastOutcome", outcome));
        updates.add(Updates.set("lastSeenAt", Date.from(at)));
        updates.add(Updates.inc("deliveries", 1));
        if (orderId != null) {
            updates.add(Updates.set("orderId", orderId));
        }
        if (eventVersion != null) {
            updates.add(Updates.set("eventVersion", eventVersion));
        }
        return Updates.combine(updates);
    }
}
