package com.b2b.orders.domain.model;

import java.util.Objects;

public record Client(
        ClientId id,
        String name,
        ClientStatus status,
        Segment segment,
        TaxRegime taxRegime,
        String marketCode) {

    public Client {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(taxRegime, "taxRegime");
    }

    public boolean isActive() {
        return status == ClientStatus.ACTIVE;
    }

    public boolean belongsTo(Market market) {
        return market.name().equals(marketCode);
    }
}
