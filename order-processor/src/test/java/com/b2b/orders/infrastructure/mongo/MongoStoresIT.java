package com.b2b.orders.infrastructure.mongo;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

import com.b2b.orders.application.ErrorCategory;
import com.b2b.orders.application.EventMetadata;
import com.b2b.orders.application.ProcessedOrder;
import com.b2b.orders.application.ProcessingStatus;
import com.b2b.orders.application.StoreOutcome;
import com.b2b.orders.application.TechnicalError;
import com.b2b.orders.application.outbox.OutboxMessage;
import com.b2b.orders.domain.TestData;
import com.b2b.orders.domain.model.EventId;
import com.b2b.orders.domain.model.OrderId;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.pricing.DiscountPolicy;
import com.b2b.orders.domain.pricing.OrderCalculator;
import com.b2b.orders.domain.pricing.TaxPolicy;
import com.b2b.orders.infrastructure.kafka.DeliveryKey;
import com.b2b.orders.infrastructure.messaging.OrderProcessedEventMapper;
import com.b2b.orders.infrastructure.messaging.UlidGenerator;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class MongoStoresIT {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

    private static final Clock CLOCK = Clock.systemUTC();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static MongoClient client;
    private static MongoDatabase db;
    private MongoOrderResultStore store;
    private MongoOutboxStore outbox;
    private String orderId;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(MONGO.getReplicaSetUrl("orders") + "?directConnection=true");
        db = client.getDatabase("orders");
        MongoSchema.ensure(db);
        MongoSchema.ensure(db);
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void setUp() {
        store = new MongoOrderResultStore(client, db, new EventLedger(db), new OrderProcessedEventMapper(),
                new UlidGenerator(CLOCK), CLOCK, "orders.processed.v1", "orders.processing.dlt", "order-processor@test");
        outbox = new MongoOutboxStore(db, CLOCK);
        orderId = "ORD-IT-" + SEQUENCE.incrementAndGet();
        db.getCollection(MongoSchema.OUTBOX).deleteMany(new Document());
    }

    private OrderRequest order(String eventId, int version) {
        OrderRequest golden = TestData.goldenOrder();
        return new OrderRequest(new EventId(eventId), version, golden.occurredAtIfPresent().orElse(null),
                new OrderId(orderId), golden.market(), golden.currency(), golden.clientId(), golden.channel(), golden.items());
    }

    private static EventMetadata metadata() {
        return new EventMetadata("orders.created.v1", 0, 1, Instant.now(), "trace", "{\"raw\":true}");
    }

    private static ProcessedOrder approved(OrderRequest order) {
        var pricing = new OrderCalculator(new TaxPolicy(), new DiscountPolicy())
                .price(order, TestData.goldenClient(), TestData.goldenProducts());
        return ProcessedOrder.approved(order, TestData.goldenClient(), pricing, metadata(), Instant.now());
    }

    private static ProcessedOrder technicalFailure(OrderRequest order) {
        return ProcessedOrder.technicalFailure(order, TestData.goldenClient(),
                new TechnicalError(ErrorCategory.TRANSIENT_DEPENDENCY_EXHAUSTED, "PRODUCTS_API_503", "503", 3),
                metadata(), Instant.now());
    }

    private long ordersFor() {
        return db.getCollection(MongoSchema.ORDERS).countDocuments(eq("_id", orderId));
    }

    private List<Document> outboxFor() {
        return db.getCollection(MongoSchema.OUTBOX).find(eq("key", orderId)).into(new ArrayList<>());
    }

    @Test
    void guardaElPedidoYSuEventoDeSalidaEnLaMismaTransaccion() {
        assertThat(store.save(approved(order("E1-" + orderId, 1)))).isEqualTo(StoreOutcome.SAVED);

        assertThat(store.findState(new OrderId(orderId))).get()
                .satisfies(s -> assertThat(s.status()).isEqualTo(ProcessingStatus.APPROVED));
        List<Document> messages = outboxFor();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getString("topic")).isEqualTo("orders.processed.v1");
        assertThat(messages.getFirst().getString("status")).isEqualTo("PENDING");
        assertThat(messages.getFirst().getString("payload")).contains("\"sourceEventId\":\"E1-" + orderId + "\"");
    }

    @Test
    void elMismoEventoNoProduceUnSegundoEfecto() {
        OrderRequest order = order("E1-" + orderId, 1);
        store.save(approved(order));

        assertThat(store.save(approved(order))).isEqualTo(StoreOutcome.DUPLICATE);
        assertThat(outboxFor()).hasSize(1);
    }

    @Test
    void unaVersionMenorNoSobrescribeUnResultadoMasNuevo() {
        store.save(approved(order("E2-" + orderId, 2)));

        assertThat(store.save(approved(order("E1-" + orderId, 1)))).isEqualTo(StoreOutcome.STALE);
        assertThat(store.findState(new OrderId(orderId))).get()
                .satisfies(s -> assertThat(s.eventVersion()).isEqualTo(2));
    }

    @Test
    void unaVersionMayorReemplazaElResultado() {
        store.save(approved(order("E1-" + orderId, 1)));

        assertThat(store.save(approved(order("E2-" + orderId, 2)))).isEqualTo(StoreOutcome.SAVED);
        assertThat(ordersFor()).isEqualTo(1);
        assertThat(outboxFor()).hasSize(2);
    }

    @Test
    void otroEventoConLaMismaVersionEsUnConflicto() {
        store.save(approved(order("E1-" + orderId, 1)));

        assertThat(store.save(approved(order("OTRO-" + orderId, 1)))).isEqualTo(StoreOutcome.CONFLICT);
        assertThat(outboxFor()).hasSize(1);
    }

    @Test
    void unFalloTecnicoVaALaDltPorElOutboxYPuedeReprocesarse() {
        OrderRequest order = order("E1-" + orderId, 1);
        assertThat(store.save(technicalFailure(order))).isEqualTo(StoreOutcome.SAVED);
        Document deadLetter = outboxFor().getFirst();
        assertThat(deadLetter.getString("topic")).isEqualTo("orders.processing.dlt");
        assertThat(deadLetter.getString("payload")).isEqualTo("{\"raw\":true}");
        assertThat(deadLetter.get("headers", Document.class).getString("dlt-error-category"))
                .isEqualTo("TRANSIENT_DEPENDENCY_EXHAUSTED");

        assertThat(store.save(approved(order))).isEqualTo(StoreOutcome.SAVED);
        assertThat(store.findState(new OrderId(orderId))).get()
                .satisfies(s -> assertThat(s.status()).isEqualTo(ProcessingStatus.APPROVED));
    }

    @Nested
    class Concurrencia {

        private List<StoreOutcome> race(int threads, Callable<StoreOutcome> write) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<StoreOutcome>> futures = new ArrayList<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
                for (int i = 0; i < threads; i++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return write.call();
                    }));
                }
                start.countDown();
                List<StoreOutcome> outcomes = new ArrayList<>();
                for (Future<StoreOutcome> future : futures) {
                    outcomes.add(future.get());
                }
                return outcomes;
            }
        }

        @Test
        void elMismoEventoEnParaleloSeGuardaUnaSolaVez() throws Exception {
            OrderRequest order = order("E1-" + orderId, 1);

            List<StoreOutcome> outcomes = race(8, () -> store.save(approved(order)));

            assertThat(outcomes).containsOnlyOnce(StoreOutcome.SAVED);
            assertThat(outcomes).filteredOn(o -> o != StoreOutcome.SAVED).containsOnly(StoreOutcome.DUPLICATE);
            assertThat(ordersFor()).isEqualTo(1);
            assertThat(outboxFor()).hasSize(1);
        }

        @Test
        void eventosDistintosConLaMismaVersionEnParaleloTienenUnSoloGanador() throws Exception {
            AtomicInteger n = new AtomicInteger();

            List<StoreOutcome> outcomes = race(6, () -> store.save(approved(order("E" + n.incrementAndGet() + "-" + orderId, 1))));

            assertThat(outcomes).containsOnlyOnce(StoreOutcome.SAVED);
            assertThat(outcomes).filteredOn(o -> o != StoreOutcome.SAVED).containsOnly(StoreOutcome.CONFLICT);
            assertThat(outboxFor()).hasSize(1);
        }
    }

    @Nested
    class Outbox {

        @Test
        void reservaPublicaYMarcaEnOrdenDeCreacion() {
            store.save(approved(order("E1-" + orderId, 1)));
            store.save(approved(order("E2-" + orderId, 2)));

            OutboxMessage first = outbox.claimNext(Duration.ofSeconds(30)).orElseThrow();
            OutboxMessage second = outbox.claimNext(Duration.ofSeconds(30)).orElseThrow();
            assertThat(first.payload()).contains("\"sourceEventVersion\":1");
            assertThat(second.payload()).contains("\"sourceEventVersion\":2");
            assertThat(outbox.claimNext(Duration.ofSeconds(30))).isEmpty();

            outbox.markSent(first.id());
            assertThat(outboxFor()).filteredOn(d -> "SENT".equals(d.getString("status"))).hasSize(1);
        }

        @Test
        void unMensajeFallidoVuelveAEstarDisponibleTrasSuEspera() throws InterruptedException {
            store.save(approved(order("E1-" + orderId, 1)));
            OutboxMessage message = outbox.claimNext(Duration.ofSeconds(30)).orElseThrow();

            outbox.markFailed(message.id(), "broker caído", Duration.ofMillis(200));
            assertThat(outbox.claimNext(Duration.ofSeconds(30))).isEmpty();

            Thread.sleep(300);
            assertThat(outbox.claimNext(Duration.ofSeconds(30))).get()
                    .satisfies(m -> assertThat(m.attempts()).isEqualTo(1));
        }

        @Test
        void unaReservaVencidaPuedeTomarlaOtraInstancia() throws InterruptedException {
            store.save(approved(order("E1-" + orderId, 1)));
            outbox.claimNext(Duration.ofMillis(200)).orElseThrow();

            Thread.sleep(300);
            assertThat(outbox.claimNext(Duration.ofSeconds(30))).isPresent();
        }
    }

    @Nested
    class GuardiaDeEntregas {

        private final DeliveryGuard guard = new DeliveryGuard(db, 3);

        private DeliveryKey key() {
            return new DeliveryKey("orders.created.v1", 0, SEQUENCE.incrementAndGet());
        }

        @Test
        void losReintentosManejadosNoCuentanComoCaidas() {
            DeliveryKey key = key();
            for (int i = 0; i < 5; i++) {
                assertThat(guard.begin(key, Optional.of("E"), Optional.of(orderId), Instant.now()).poison()).isFalse();
                guard.finish(key, DeliveryGuard.RETRYING, Instant.now());
            }
        }

        @Test
        void tresCaidasSeguidasMarcanElMensajeComoVeneno() {
            DeliveryKey key = key();
            for (int crash = 0; crash < 3; crash++) {
                assertThat(guard.begin(key, Optional.empty(), Optional.empty(), Instant.now()).poison()).isFalse();
            }
            DeliveryGuard.Delivery delivery = guard.begin(key, Optional.empty(), Optional.empty(), Instant.now());
            assertThat(delivery.poison()).isTrue();
            assertThat(delivery.crashes()).isEqualTo(3);
        }
    }
}
