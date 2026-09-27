package com.b2b.orders.infrastructure.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

public record DeliveryKey(String topic, int partition, long offset) {

    public static DeliveryKey of(ConsumerRecord<?, ?> record) {
        return new DeliveryKey(record.topic(), record.partition(), record.offset());
    }

    public String id() {
        return topic + ":" + partition + ":" + offset;
    }
}
