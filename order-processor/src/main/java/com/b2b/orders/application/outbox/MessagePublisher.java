package com.b2b.orders.application.outbox;

public interface MessagePublisher {

    /** Bloquea hasta la confirmación del broker. @throws RuntimeException si no se pudo publicar. */
    void publish(OutboxMessage message);
}
