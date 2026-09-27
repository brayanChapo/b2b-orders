package com.b2b.orders.infrastructure.kafka;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

public class KafkaDeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaDeadLetterPublisher.class);

    private final KafkaTemplate<String, String> template;
    private final String topic;
    private final String component;
    private final Duration timeout;

    public KafkaDeadLetterPublisher(KafkaTemplate<String, String> template, String topic, String component, Duration timeout) {
        this.template = template;
        this.topic = topic;
        this.component = component;
        this.timeout = timeout;
    }

    public void publish(DeadLetter letter) {
        KafkaSender.sendAndWait(template, topic, letter.key(), letter.payload(),
                DeadLetterHeaders.of(letter, component), timeout);
        log.warn("dead_lettered category={} code={} attempts={}", letter.category(), letter.code(), letter.attempts());
    }
}
