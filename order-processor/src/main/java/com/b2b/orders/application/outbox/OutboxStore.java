package com.b2b.orders.application.outbox;

import java.time.Duration;
import java.util.Optional;

public interface OutboxStore {

    /** Reserva el siguiente mensaje pendiente durante lease, para que otra instancia no lo publique a la vez. */
    Optional<OutboxMessage> claimNext(Duration lease);

    void markSent(String id);

    void markFailed(String id, String error, Duration retryIn);
}
