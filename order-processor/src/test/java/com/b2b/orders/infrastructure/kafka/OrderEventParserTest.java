package com.b2b.orders.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.validation.InvalidOrderException;
import com.b2b.orders.domain.validation.OrderDraft;
import com.b2b.orders.domain.validation.OrderValidator;
import com.b2b.orders.infrastructure.Contracts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OrderEventParserTest {

    private final OrderEventParser parser = new OrderEventParser(262_144, 200);
    private final OrderValidator validator = new OrderValidator();

    @Test
    void todosLosFixturesValidosDelContratoProducenUnPedido() throws IOException {
        for (Path file : Contracts.examples("orders.created.v1/valid")) {
            OrderRequest order = validator.validate(parser.parse(Files.readString(file)));
            assertThat(order.items()).as(file.getFileName().toString()).isNotEmpty();
        }
    }

    @Test
    void losFixturesInvalidosPorEsquemaSeRechazan() throws IOException {
        for (Path file : Contracts.examples("orders.created.v1/invalid-schema")) {
            Throwable error = catchThrowable(() -> validator.validate(parser.parse(Files.readString(file))));
            assertThat(error).as(file.getFileName().toString())
                    .isInstanceOfAny(MalformedEventException.class, InvalidOrderException.class);
        }
    }

    @Test
    void losFixturesInvalidosPorNegocioPasanElParserYLosRechazaElValidador() throws IOException {
        for (Path file : Contracts.examples("orders.created.v1/invalid-semantic")) {
            OrderDraft draft = parser.parse(Files.readString(file));
            assertThatThrownBy(() -> validator.validate(draft)).as(file.getFileName().toString())
                    .isInstanceOf(InvalidOrderException.class);
        }
    }

    @Test
    void losDecimalesSeLeenExactos() {
        OrderDraft draft = parser.parse("""
                {"eventId":"E","eventVersion":1,"orderId":"O","market":"MX","currency":"MXN","clientId":"C",
                 "items":[{"productId":"P","quantity":3,"unitPrice":0.1},{"productId":"Q","quantity":1,"unitPrice":35.50}]}""");
        assertThat(draft.items().get(0).unitPrice()).hasToString("0.1");
        assertThat(draft.items().get(1).unitPrice()).hasToString("35.50");
    }

    @Test
    void reportaTodosLosErroresDeTipo() {
        assertThatThrownBy(() -> parser.parse("""
                {"eventId":1,"eventVersion":"1","orderId":"O","occurredAt":"ayer",
                 "items":[{"productId":"P","quantity":2.5,"unitPrice":"10"}]}"""))
                .isInstanceOfSatisfying(MalformedEventException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_FIELD_TYPE");
                    assertThat(e.getMessage()).contains("eventId", "eventVersion", "occurredAt",
                            "items[0].quantity", "items[0].unitPrice");
                });
    }

    @Test
    void jsonMalFormado() {
        assertThatThrownBy(() -> parser.parse("{\"eventId\": "))
                .isInstanceOfSatisfying(MalformedEventException.class, e -> assertThat(e.code()).isEqualTo("MALFORMED_JSON"));
    }

    @Test
    void noEsUnObjeto() {
        assertThatThrownBy(() -> parser.parse("[1,2]"))
                .isInstanceOfSatisfying(MalformedEventException.class, e -> assertThat(e.code()).isEqualTo("NOT_AN_OBJECT"));
    }

    @Test
    void limitesDeTamano() {
        OrderEventParser small = new OrderEventParser(50, 1);
        assertThatThrownBy(() -> small.parse("{\"x\":\"" + "a".repeat(100) + "\"}"))
                .isInstanceOfSatisfying(MalformedEventException.class, e -> assertThat(e.code()).isEqualTo("PAYLOAD_TOO_LARGE"));
        assertThatThrownBy(() -> small.parse("{\"items\":[{},{}]}"))
                .isInstanceOfSatisfying(MalformedEventException.class, e -> assertThat(e.code()).isEqualTo("TOO_MANY_ITEMS"));
    }

    @Test
    void extraeIdentificadoresAunqueElMensajeSeaInvalido() {
        OrderEventParser.EventIds ids = parser.peekIds("{\"eventId\":\"E1\",\"orderId\":\"O1\",\"items\":\"x\"}");
        assertThat(ids.eventId()).contains("E1");
        assertThat(ids.orderId()).contains("O1");
        assertThat(parser.peekIds("no es json").eventId()).isEmpty();
    }
}
