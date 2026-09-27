package com.b2b.orders.infrastructure.kafka;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Intentos fallidos en proceso por registro, para informarlos en la DLT si se agotan. */
public class FailureCounter {

    private final ConcurrentMap<String, Integer> failures = new ConcurrentHashMap<>();

    public int increment(DeliveryKey key) {
        return failures.merge(key.id(), 1, Integer::sum);
    }

    public int remove(DeliveryKey key) {
        Integer count = failures.remove(key.id());
        return count == null ? 1 : count;
    }

    public void reset(DeliveryKey key) {
        failures.remove(key.id());
    }
}
