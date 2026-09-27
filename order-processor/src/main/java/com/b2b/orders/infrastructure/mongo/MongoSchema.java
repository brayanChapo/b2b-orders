package com.b2b.orders.infrastructure.mongo;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;

/**
 * Colecciones e índices de los que depende la corrección (unicidad, TTL). Idempotente y con
 * los mismos nombres que infra/mongo/init-indexes.js: el servicio no depende del script.
 */
public final class MongoSchema {

    public static final String ORDERS = "orders";
    public static final String OUTBOX = "outbox";
    public static final String ORDER_EVENTS = "order_events";
    public static final String DELIVERY_ATTEMPTS = "delivery_attempts";

    private static final long SEVEN_DAYS = 7;

    private MongoSchema() {
    }

    public static void ensure(MongoDatabase db) {
        requireTransactions(db);
        List<String> existing = db.listCollectionNames().into(new ArrayList<>());
        for (String name : List.of(ORDERS, OUTBOX, ORDER_EVENTS, DELIVERY_ATTEMPTS)) {
            if (!existing.contains(name)) {
                db.createCollection(name);
            }
        }

        db.getCollection(ORDERS).createIndex(Indexes.ascending("eventId"),
                new IndexOptions().unique(true).name("ux_orders_eventId"));
        db.getCollection(ORDERS).createIndex(
                Indexes.compoundIndex(Indexes.ascending("status"), Indexes.descending("processedAt")),
                new IndexOptions().name("ix_orders_status_processedAt"));

        db.getCollection(OUTBOX).createIndex(Indexes.ascending("status", "createdAt"),
                new IndexOptions().name("ix_outbox_status_createdAt"));
        db.getCollection(OUTBOX).createIndex(Indexes.ascending("sentAt"),
                new IndexOptions().expireAfter(SEVEN_DAYS, TimeUnit.DAYS).name("ttl_outbox_sentAt"));

        db.getCollection(ORDER_EVENTS).createIndex(Indexes.ascending("orderId", "eventVersion"),
                new IndexOptions().name("ix_order_events_orderId_version"));

        db.getCollection(DELIVERY_ATTEMPTS).createIndex(Indexes.ascending("status", "lastSeenAt"),
                new IndexOptions().name("ix_delivery_status_lastSeenAt"));
        db.getCollection(DELIVERY_ATTEMPTS).createIndex(Indexes.ascending("orderId"),
                new IndexOptions().sparse(true).name("ix_delivery_orderId"));
        db.getCollection(DELIVERY_ATTEMPTS).createIndex(Indexes.ascending("lastSeenAt"),
                new IndexOptions().expireAfter(SEVEN_DAYS, TimeUnit.DAYS).name("ttl_delivery_lastSeenAt"));
    }

    /** El outbox depende de transacciones: sin replica set el servicio no debe arrancar (ADR-002). */
    static void requireTransactions(MongoDatabase db) {
        Document hello = db.runCommand(new Document("hello", 1));
        boolean replicaSet = hello.containsKey("setName");
        boolean sharded = "isdbgrid".equals(hello.getString("msg"));
        if (!replicaSet && !sharded) {
            throw new IllegalStateException("MongoDB no es replica set: las transacciones del outbox no son posibles");
        }
    }
}
