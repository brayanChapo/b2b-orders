package com.b2b.orders.infrastructure.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

final class KafkaSender {

    private KafkaSender() {
    }

    static void sendAndWait(KafkaTemplate<String, String> template, String topic, String key, String value,
            Map<String, String> headers, Duration timeout) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        headers.forEach((name, header) -> record.headers().add(name, header.getBytes(StandardCharsets.UTF_8)));
        try {
            template.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicación interrumpida en " + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("No se pudo publicar en " + topic, e);
        }
    }
}
