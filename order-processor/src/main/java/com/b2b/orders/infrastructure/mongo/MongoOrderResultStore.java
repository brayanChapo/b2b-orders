package com.b2b.orders.infrastructure.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.or;

import com.b2b.orders.application.ErrorCategory;
import com.b2b.orders.application.ProcessedOrder;
import com.b2b.orders.application.ProcessingStatus;
import com.b2b.orders.application.StoreOutcome;
import com.b2b.orders.application.StoredOrderState;
import com.b2b.orders.application.VersionPolicy;
import com.b2b.orders.application.port.OrderResultStore;
import com.b2b.orders.domain.model.OrderId;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.infrastructure.kafka.DeadLetter;
import com.b2b.orders.infrastructure.kafka.DeadLetterHeaders;
import com.b2b.orders.infrastructure.messaging.OrderProcessedEventMapper;
import com.mongodb.MongoServerException;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.Projections;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Escritura atómica del resultado (ADR-002): en una transacción se reemplaza el documento
 * del pedido de forma condicional y se inserta su mensaje en el outbox.
 *
 * El filtro solo coincide si el evento es más nuevo que el vigente o reprocesa un
 * TECHNICAL_FAILURE del mismo evento. Si no coincide, el upsert intenta insertar un _id
 * existente y MongoDB responde DuplicateKey: esa es la garantía bajo concurrencia.
 */
public class MongoOrderResultStore implements OrderResultStore {

    private static final Logger log = LoggerFactory.getLogger(MongoOrderResultStore.class);
    private static final int DUPLICATE_KEY = 11000;
    private static final TransactionOptions TRANSACTION = TransactionOptions.builder()
            .writeConcern(WriteConcern.MAJORITY)
            .build();

    private final MongoClient client;
    private final MongoCollection<Document> orders;
    private final MongoCollection<Document> outbox;
    private final EventLedger ledger;
    private final OrderProcessedEventMapper eventMapper;
    private final Supplier<String> ids;
    private final Clock clock;
    private final String outputTopic;
    private final String deadLetterTopic;
    private final String component;

    public MongoOrderResultStore(MongoClient client, MongoDatabase db, EventLedger ledger,
            OrderProcessedEventMapper eventMapper, Supplier<String> ids, Clock clock,
            String outputTopic, String deadLetterTopic, String component) {
        this.client = client;
        this.orders = db.getCollection(MongoSchema.ORDERS);
        this.outbox = db.getCollection(MongoSchema.OUTBOX);
        this.ledger = ledger;
        this.eventMapper = eventMapper;
        this.ids = ids;
        this.clock = clock;
        this.outputTopic = outputTopic;
        this.deadLetterTopic = deadLetterTopic;
        this.component = component;
    }

    @Override
    public Optional<StoredOrderState> findState(OrderId orderId) {
        Document doc = orders.find(eq("_id", orderId.value()))
                .projection(Projections.include("eventId", "eventVersion", "status"))
                .first();
        return Optional.ofNullable(doc).map(OrderDocumentMapper::toState);
    }

    @Override
    public StoreOutcome save(ProcessedOrder result) {
        OrderRequest order = result.order();
        Document orderDocument = OrderDocumentMapper.toDocument(result);
        Document message = outboxMessage(result);
        Bson writable = and(
                eq("_id", order.orderId().value()),
                or(lt("eventVersion", order.eventVersion()),
                        and(eq("eventId", order.eventId().value()),
                                eq("status", ProcessingStatus.TECHNICAL_FAILURE.name()))));
        Instant now = clock.instant();

        try (ClientSession session = client.startSession()) {
            session.withTransaction(() -> {
                orders.findOneAndReplace(session, writable, orderDocument, new FindOneAndReplaceOptions().upsert(true));
                outbox.insertOne(session, message);
                ledger.record(session, order.eventId().value(), order.orderId().value(), order.eventVersion(),
                        "PROCESSED_" + result.status().name(), now);
                return null;
            }, TRANSACTION);
            return StoreOutcome.SAVED;
        } catch (MongoServerException e) {
            if (e.getCode() != DUPLICATE_KEY) {
                throw e;
            }
            StoreOutcome outcome = findState(order.orderId())
                    .flatMap(state -> VersionPolicy.classify(state, order.eventId(), order.eventVersion()))
                    .orElse(StoreOutcome.CONFLICT);
            log.info("conditional_write_lost outcome={}", outcome);
            return outcome;
        }
    }

    private Document outboxMessage(ProcessedOrder result) {
        String id = ids.get();
        String key = result.order().orderId().value();
        Instant now = clock.instant();
        if (result.status() != ProcessingStatus.TECHNICAL_FAILURE) {
            return OutboxDocuments.pending(id, outputTopic, key, eventMapper.toJson(result, id), Map.of(), now);
        }
        var meta = result.metadata();
        var error = result.error();
        if (error.category() == ErrorCategory.VALIDATION_ERROR) {
            throw new IllegalArgumentException("Los errores de validación no se persisten como pedido");
        }
        DeadLetter letter = new DeadLetter(meta.topic(), meta.partition(), meta.offset(), key, meta.rawPayload(),
                Optional.of(key), Optional.of(result.order().eventId().value()),
                error.category(), error.code(), error.summary(), error.attempts(), now);
        return OutboxDocuments.pending(id, deadLetterTopic, key, meta.rawPayload(),
                DeadLetterHeaders.of(letter, component), now);
    }
}
