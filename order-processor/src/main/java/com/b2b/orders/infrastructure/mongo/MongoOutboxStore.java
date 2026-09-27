package com.b2b.orders.infrastructure.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lte;

import com.b2b.orders.application.outbox.OutboxMessage;
import com.b2b.orders.application.outbox.OutboxStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.bson.Document;

public class MongoOutboxStore implements OutboxStore {

    private static final int MAX_ERROR_LENGTH = 500;

    private final MongoCollection<Document> outbox;
    private final Clock clock;

    public MongoOutboxStore(MongoDatabase db, Clock clock) {
        this.outbox = db.getCollection(MongoSchema.OUTBOX);
        this.clock = clock;
    }

    @Override
    public Optional<OutboxMessage> claimNext(Duration lease) {
        Instant now = clock.instant();
        Document doc = outbox.findOneAndUpdate(
                and(eq("status", OutboxDocuments.PENDING), lte("lockedUntil", Date.from(now))),
                Updates.set("lockedUntil", Date.from(now.plus(lease))),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("createdAt")).returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(doc).map(OutboxDocuments::toMessage);
    }

    @Override
    public void markSent(String id) {
        outbox.updateOne(eq("_id", id), Updates.combine(
                Updates.set("status", OutboxDocuments.SENT),
                Updates.set("sentAt", Date.from(clock.instant()))));
    }

    @Override
    public void markFailed(String id, String error, Duration retryIn) {
        String detail = error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        outbox.updateOne(eq("_id", id), Updates.combine(
                Updates.set("lockedUntil", Date.from(clock.instant().plus(retryIn))),
                Updates.inc("attempts", 1),
                Updates.set("lastError", detail)));
    }

    public Optional<Instant> oldestPendingCreatedAt() {
        Document doc = outbox.find(eq("status", OutboxDocuments.PENDING))
                .sort(Sorts.ascending("createdAt"))
                .projection(Projections.include("createdAt"))
                .first();
        return Optional.ofNullable(doc).map(d -> d.getDate("createdAt").toInstant());
    }
}
