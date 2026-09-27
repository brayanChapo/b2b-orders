package com.b2b.orders.infrastructure.kafka;

import com.b2b.orders.domain.validation.OrderDraft;
import com.b2b.orders.domain.validation.OrderDraft.ItemDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class OrderEventParser {

    public record EventIds(Optional<String> eventId, Optional<String> orderId) {
    }

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private final int maxPayloadBytes;
    private final int maxItems;

    public OrderEventParser(int maxPayloadBytes, int maxItems) {
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxItems = maxItems;
    }

    public OrderDraft parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new MalformedEventException("EMPTY_PAYLOAD", "El mensaje está vacío");
        }
        int size = payload.getBytes(StandardCharsets.UTF_8).length;
        if (size > maxPayloadBytes) {
            throw new MalformedEventException("PAYLOAD_TOO_LARGE",
                    "El mensaje ocupa " + size + " bytes (máximo " + maxPayloadBytes + ")");
        }
        JsonNode root = readTree(payload);
        if (!root.isObject()) {
            throw new MalformedEventException("NOT_AN_OBJECT", "El mensaje debe ser un objeto JSON");
        }

        List<String> errors = new ArrayList<>();
        OrderDraft draft = new OrderDraft(
                text(root, "eventId", errors),
                integer(root, "eventVersion", errors),
                instant(root, "occurredAt", errors),
                text(root, "orderId", errors),
                text(root, "market", errors),
                text(root, "currency", errors),
                text(root, "clientId", errors),
                text(root, "channel", errors),
                items(root, errors));
        if (!errors.isEmpty()) {
            throw new MalformedEventException("INVALID_FIELD_TYPE", String.join("; ", errors));
        }
        return draft;
    }

    /** Identificadores para trazabilidad aunque el mensaje sea inválido. Nunca lanza excepciones. */
    public EventIds peekIds(String payload) {
        try {
            JsonNode root = mapper.readTree(payload);
            return new EventIds(textIfPresent(root, "eventId"), textIfPresent(root, "orderId"));
        } catch (JsonProcessingException | RuntimeException e) {
            return new EventIds(Optional.empty(), Optional.empty());
        }
    }

    private JsonNode readTree(String payload) {
        try {
            return mapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("MALFORMED_JSON", "JSON inválido en línea "
                    + e.getLocation().getLineNr() + ", columna " + e.getLocation().getColumnNr());
        }
    }

    private List<ItemDraft> items(JsonNode root, List<String> errors) {
        JsonNode node = root.get("items");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            errors.add("items: debe ser un arreglo");
            return null;
        }
        if (node.size() > maxItems) {
            throw new MalformedEventException("TOO_MANY_ITEMS",
                    "El pedido tiene " + node.size() + " líneas (máximo " + maxItems + ")");
        }
        List<ItemDraft> items = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            String path = "items[" + i + "]";
            if (item.isNull()) {
                items.add(null);
            } else if (!item.isObject()) {
                errors.add(path + ": debe ser un objeto");
                items.add(null);
            } else {
                items.add(new ItemDraft(
                        text(item, "productId", path + ".productId", errors),
                        longValue(item, "quantity", path + ".quantity", errors),
                        decimal(item, "unitPrice", path + ".unitPrice", errors)));
            }
        }
        return items;
    }

    private static String text(JsonNode node, String field, List<String> errors) {
        return text(node, field, field, errors);
    }

    private static String text(JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            errors.add(path + ": debe ser texto");
            return null;
        }
        return value.textValue();
    }

    private static Integer integer(JsonNode node, String field, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            errors.add(field + ": debe ser un entero");
            return null;
        }
        return value.intValue();
    }

    private static Long longValue(JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            errors.add(path + ": debe ser un entero");
            return null;
        }
        return value.longValue();
    }

    private static BigDecimal decimal(JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            errors.add(path + ": debe ser un número");
            return null;
        }
        return value.decimalValue();
    }

    private static Instant instant(JsonNode node, String field, List<String> errors) {
        String text = text(node, field, errors);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            errors.add(field + ": debe ser una fecha ISO-8601");
            return null;
        }
    }

    private static Optional<String> textIfPresent(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value != null && value.isTextual() ? Optional.of(value.textValue()) : Optional.empty();
    }
}
