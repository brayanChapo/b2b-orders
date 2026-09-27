package com.b2b.orders.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UlidGeneratorTest {

    @Test
    void generaUlidsValidosYUnicos() {
        UlidGenerator generator = new UlidGenerator(Clock.systemUTC());
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = generator.get();
            assertThat(id).matches("^[0-9A-HJKMNP-TV-Z]{26}$");
            ids.add(id);
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    void elPrefijoTemporalOrdenaPorFecha() {
        String before = new UlidGenerator(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)).get();
        String after = new UlidGenerator(Clock.fixed(Instant.parse("2026-09-18T15:42:11Z"), ZoneOffset.UTC)).get();
        assertThat(before.substring(0, 10)).isLessThan(after.substring(0, 10));
    }
}
