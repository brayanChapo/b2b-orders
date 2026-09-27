package com.b2b.orders.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    private final InMemoryOutbox store = new InMemoryOutbox();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final OutboxRelay relay = new OutboxRelay(store, publisher,
            Duration.ofSeconds(30), Duration.ofMillis(500), Duration.ofSeconds(60));

    private static OutboxMessage message(String id) {
        return new OutboxMessage(id, "orders.processed.v1", "ORD-" + id, "{}", Map.of(), 0);
    }

    @Test
    void publicaEnOrdenYMarcaComoEnviados() {
        store.pending.add(message("1"));
        store.pending.add(message("2"));

        assertThat(relay.relay(10)).isEqualTo(2);
        assertThat(publisher.published).containsExactly("1", "2");
        assertThat(store.sent).containsExactly("1", "2");
    }

    @Test
    void respetaElTamanoDelLote() {
        store.pending.add(message("1"));
        store.pending.add(message("2"));

        assertThat(relay.relay(1)).isEqualTo(1);
        assertThat(store.pending).hasSize(1);
    }

    @Test
    void siFallaLaPublicacionLoReprogramaYDetieneElLoteParaNoAlterarElOrden() {
        store.pending.add(message("1"));
        store.pending.add(message("2"));
        publisher.failing = true;

        assertThat(relay.relay(10)).isZero();
        assertThat(store.failed).containsEntry("1", Duration.ofMillis(500));
        assertThat(publisher.published).isEmpty();
        assertThat(store.pending).extracting(OutboxMessage::id).containsExactly("2");
    }

    @Test
    void elBackoffCreceHastaElMaximo() {
        assertThat(relay.backoff(0)).isEqualTo(Duration.ofMillis(500));
        assertThat(relay.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(relay.backoff(20)).isEqualTo(Duration.ofSeconds(60));
    }

    private static final class InMemoryOutbox implements OutboxStore {
        final Deque<OutboxMessage> pending = new ArrayDeque<>();
        final List<String> sent = new ArrayList<>();
        final Map<String, Duration> failed = new HashMap<>();

        @Override
        public Optional<OutboxMessage> claimNext(Duration lease) {
            return Optional.ofNullable(pending.poll());
        }

        @Override
        public void markSent(String id) {
            sent.add(id);
        }

        @Override
        public void markFailed(String id, String error, Duration retryIn) {
            failed.put(id, retryIn);
        }
    }

    private static final class RecordingPublisher implements MessagePublisher {
        final List<String> published = new ArrayList<>();
        boolean failing;

        @Override
        public void publish(OutboxMessage message) {
            if (failing) {
                throw new IllegalStateException("broker no disponible");
            }
            published.add(message.id());
        }
    }
}
