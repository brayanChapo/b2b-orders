package com.b2b.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.b2b.orders.domain.model.EventId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VersionPolicyTest {

    @ParameterizedTest(name = "vigente {0}/v{1}/{2} + entrante {3}/v{4} → {5}")
    @CsvSource(nullValues = "WRITE", value = {
            "E1, 1, APPROVED,          E1, 1, DUPLICATE",
            "E1, 1, REJECTED,          E1, 1, DUPLICATE",
            "E1, 1, TECHNICAL_FAILURE, E1, 1, WRITE",
            "E1, 1, APPROVED,          E2, 1, CONFLICT",
            "E1, 2, APPROVED,          E0, 1, STALE",
            "E1, 1, APPROVED,          E2, 2, WRITE",
            "E1, 1, TECHNICAL_FAILURE, E2, 2, WRITE"})
    void clasifica(String currentId, int currentVersion, ProcessingStatus status,
            String incomingId, int incomingVersion, StoreOutcome expected) {
        StoredOrderState current = new StoredOrderState(new EventId(currentId), currentVersion, status);
        assertThat(VersionPolicy.classify(current, new EventId(incomingId), incomingVersion))
                .isEqualTo(java.util.Optional.ofNullable(expected));
    }
}
