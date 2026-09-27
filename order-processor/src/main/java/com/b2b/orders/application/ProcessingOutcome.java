package com.b2b.orders.application;

import java.util.Optional;

/** status está vacío cuando el evento no se procesó (duplicado, obsoleto o en conflicto). */
public record ProcessingOutcome(Optional<ProcessingStatus> status, StoreOutcome storeOutcome) {

    public static ProcessingOutcome skipped(StoreOutcome outcome) {
        return new ProcessingOutcome(Optional.empty(), outcome);
    }

    public static ProcessingOutcome stored(ProcessingStatus status, StoreOutcome outcome) {
        return new ProcessingOutcome(Optional.of(status), outcome);
    }
}
