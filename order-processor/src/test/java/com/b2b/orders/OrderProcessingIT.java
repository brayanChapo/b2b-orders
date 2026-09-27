package com.b2b.orders;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.b2b.orders.support.StubCatalogServer;
import com.b2b.orders.support.StubCatalogServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Flujo completo: Kafka → order-processor → APIs (stub) → MongoDB → outbox → Kafka. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderProcessingIT {

    private static final String INPUT = "orders.created.v1";
    private static final String OUTPUT = "orders.processed.v1";
    private static final String DLT = "orders.processing.dlt";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

    static final StubCatalogServer APIS = startApis();

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private MongoDatabase db;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("orders") + "?directConnection=true");
        registry.add("app.clients-api.base-url", APIS::baseUrl);
        registry.add("app.products-api.base-url", APIS::baseUrl);
        registry.add("app.http.initial-backoff", () -> "20ms");
        registry.add("app.outbox.poll-interval-millis", () -> "100");
    }

    @BeforeAll
    static void createTopics() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(INPUT, 3, (short) 1),
                    new NewTopic(OUTPUT, 3, (short) 1),
                    new NewTopic(DLT, 1, (short) 1))).all().get();
        }
    }

    @AfterAll
    static void stopApis() {
        APIS.close();
    }

    private static StubCatalogServer startApis() {
        try {
            return new StubCatalogServer()
                    .client("CLI-99821", "MX", "ACTIVE", "WHOLESALE", "GENERAL")
                    .client("CLI-BLOCKED", "MX", "BLOCKED", "RETAIL", "GENERAL")
                    .product("PRD-001", "MX", "ACTIVE", "STANDARD")
                    .product("PRD-008", "MX", "ACTIVE", "STANDARD")
                    .product("PRD-FLAKY", "MX", "ACTIVE", "STANDARD")
                    .route("/products/PRD-DOWN?market=MX", Reply.status(503));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String newOrderId() {
        return "ORD-E2E-" + SEQUENCE.incrementAndGet();
    }

    private static String event(String eventId, String orderId, int version, String clientId, String currency,
            String secondProduct) {
        return """
                {"eventId":"%s","eventVersion":%d,"occurredAt":"2026-09-18T15:42:10Z","orderId":"%s",
                 "market":"MX","currency":"%s","clientId":"%s","channel":"C1",
                 "items":[{"productId":"PRD-001","quantity":24,"unitPrice":35.5},
                          {"productId":"%s","quantity":12,"unitPrice":82.0}]}"""
                .formatted(eventId, version, orderId, currency, clientId, secondProduct);
    }

    private static String golden(String eventId, String orderId, int version) {
        return event(eventId, orderId, version, "CLI-99821", "MXN", "PRD-008");
    }

    private void publish(String orderId, String payload) throws Exception {
        kafka.send(INPUT, orderId, payload).get();
    }

    private static List<ConsumerRecord<String, String>> recordsFor(String topic, String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            ConsumerRecords<String, String> batch;
            while (!(batch = consumer.poll(Duration.ofSeconds(1))).isEmpty()) {
                for (ConsumerRecord<String, String> record : batch) {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
            return found;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static JsonNode json(ConsumerRecord<String, String> record) throws IOException {
        return JSON.readTree(record.value());
    }

    private Document order(String orderId) {
        return db.getCollection("orders").find(eq("_id", orderId)).first();
    }

    @Test
    void apruebaPersisteYPublicaElResultado() throws Exception {
        String orderId = newOrderId();
        publish(orderId, golden("E-" + orderId, orderId, 1));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(recordsFor(OUTPUT, orderId)).hasSize(1));
        JsonNode event = json(recordsFor(OUTPUT, orderId).getFirst());
        assertThat(event.get("status").asText()).isEqualTo("APPROVED");
        assertThat(event.get("sourceEventId").asText()).isEqualTo("E-" + orderId);
        assertThat(event.get("totals").get("grandTotal").decimalValue()).isEqualByComparingTo("2100.11");
        assertThat(event.get("totals").get("discount").decimalValue()).isEqualByComparingTo("25.56");

        Document stored = order(orderId);
        assertThat(stored.getString("status")).isEqualTo("APPROVED");
        assertThat(db.getCollection("outbox").countDocuments(eq("key", orderId))).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                db.getCollection("outbox").find(eq("key", orderId)).first().getString("status")).isEqualTo("SENT"));
    }

    @Test
    void elMismoEventoRepetidoNoDuplicaEfectos() throws Exception {
        String orderId = newOrderId();
        String payload = golden("E-" + orderId, orderId, 1);
        publish(orderId, payload);
        publish(orderId, payload);
        publish(orderId, payload);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Document ledger = db.getCollection("order_events").find(eq("_id", "E-" + orderId)).first();
            assertThat(ledger).isNotNull();
            assertThat(ledger.getInteger("deliveries")).isEqualTo(3);
        });
        assertThat(recordsFor(OUTPUT, orderId)).hasSize(1);
        assertThat(db.getCollection("outbox").countDocuments(eq("key", orderId))).isEqualTo(1);
    }

    @Test
    void unaVersionAnteriorQueLlegaTardeNoSobrescribeElResultado() throws Exception {
        String orderId = newOrderId();
        publish(orderId, golden("E2-" + orderId, orderId, 2));
        publish(orderId, golden("E1-" + orderId, orderId, 1));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Document ledger = db.getCollection("order_events").find(eq("_id", "E1-" + orderId)).first();
            assertThat(ledger).isNotNull();
            assertThat(ledger.getString("outcome")).isEqualTo("STALE");
        });
        assertThat(order(orderId).getInteger("eventVersion")).isEqualTo(2);
        assertThat(recordsFor(OUTPUT, orderId)).hasSize(1);
    }

    @Test
    void unClienteBloqueadoSeRechazaConRazonExplicita() throws Exception {
        String orderId = newOrderId();
        publish(orderId, event("E-" + orderId, orderId, 1, "CLI-BLOCKED", "MXN", "PRD-008"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(recordsFor(OUTPUT, orderId)).hasSize(1));
        JsonNode event = json(recordsFor(OUTPUT, orderId).getFirst());
        assertThat(event.get("status").asText()).isEqualTo("REJECTED");
        assertThat(event.get("reason").asText()).isEqualTo("CLIENT_NOT_ACTIVE");
        assertThat(event.get("totals").isNull()).isTrue();
    }

    @Test
    void unEventoInvalidoVaALaDltSinPersistirseComoPedido() throws Exception {
        String orderId = newOrderId();
        publish(orderId, event("E-" + orderId, orderId, 1, "CLI-99821", "COP", "PRD-008"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(recordsFor(DLT, orderId)).hasSize(1));
        ConsumerRecord<String, String> letter = recordsFor(DLT, orderId).getFirst();
        assertThat(header(letter, "dlt-error-category")).isEqualTo("VALIDATION_ERROR");
        assertThat(header(letter, "dlt-error-code")).isEqualTo("CURRENCY_MARKET_MISMATCH");
        assertThat(header(letter, "dlt-event-id")).isEqualTo("E-" + orderId);
        assertThat(header(letter, "dlt-original-topic")).isEqualTo(INPUT);
        assertThat(letter.value()).contains("\"currency\":\"COP\"");
        assertThat(order(orderId)).isNull();
    }

    @Test
    void unaDependenciaCaidaTerminaEnTechnicalFailureYDlt() throws Exception {
        String orderId = newOrderId();
        publish(orderId, event("E-" + orderId, orderId, 1, "CLI-99821", "MXN", "PRD-DOWN"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(recordsFor(DLT, orderId)).hasSize(1));
        ConsumerRecord<String, String> letter = recordsFor(DLT, orderId).getFirst();
        assertThat(header(letter, "dlt-error-category")).isEqualTo("TRANSIENT_DEPENDENCY_EXHAUSTED");
        assertThat(header(letter, "dlt-error-code")).isEqualTo("PRODUCTS_API_503");
        assertThat(header(letter, "dlt-attempts")).isEqualTo("3");
        assertThat(order(orderId).getString("status")).isEqualTo("TECHNICAL_FAILURE");
        assertThat(recordsFor(OUTPUT, orderId)).isEmpty();
    }

    @Test
    void unFalloTransitorioAisladoSeRecuperaConReintentos() throws Exception {
        String orderId = newOrderId();
        APIS.failNext("/products/PRD-FLAKY?market=MX", Reply.status(503));
        publish(orderId, event("E-" + orderId, orderId, 1, "CLI-99821", "MXN", "PRD-FLAKY"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(recordsFor(OUTPUT, orderId)).hasSize(1));
        assertThat(json(recordsFor(OUTPUT, orderId).getFirst()).get("status").asText()).isEqualTo("APPROVED");
    }
}
