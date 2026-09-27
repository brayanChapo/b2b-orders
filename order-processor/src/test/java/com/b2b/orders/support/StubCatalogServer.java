package com.b2b.orders.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** clients-api y products-api falsos: respuestas por ruta y fallos programables por ruta. */
public final class StubCatalogServer implements AutoCloseable {

    public record Reply(int status, String body, Map<String, String> headers, long delayMillis) {
        public static Reply json(String body) {
            return new Reply(200, body, Map.of(), 0);
        }

        public static Reply status(int status) {
            return new Reply(status, "{\"code\":\"X\",\"message\":\"x\",\"status\":" + status + ",\"timestamp\":\"2026-01-01T00:00:00Z\"}", Map.of(), 0);
        }

        public static Reply slow(long delayMillis, String body) {
            return new Reply(200, body, Map.of(), delayMillis);
        }
    }

    private final HttpServer server;
    private final Map<String, Reply> routes = new ConcurrentHashMap<>();
    private final Map<String, Deque<Reply>> failures = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final List<String> requestIds = new CopyOnWriteArrayList<>();

    public StubCatalogServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubCatalogServer client(String id, String market, String status, String segment, String regime) {
        routes.put("/clients/" + id, Reply.json("""
                {"clientId":"%s","name":"Cliente %s","status":"%s","segment":"%s","taxRegime":"%s","market":"%s"}"""
                .formatted(id, id, status, segment, regime, market)));
        return this;
    }

    public StubCatalogServer product(String id, String market, String status, String taxCategory) {
        routes.put("/products/" + id + "?market=" + market, Reply.json("""
                {"productId":"%s","name":"Producto %s","sku":"SKU-%s","status":"%s","taxCategory":"%s"}"""
                .formatted(id, id, id, status, taxCategory)));
        return this;
    }

    public StubCatalogServer route(String pathAndQuery, Reply reply) {
        routes.put(pathAndQuery, reply);
        return this;
    }

    /** Las próximas solicitudes a la ruta reciben estas respuestas, en orden; luego, la normal. */
    public StubCatalogServer failNext(String pathAndQuery, Reply... replies) {
        failures.computeIfAbsent(pathAndQuery, k -> new ArrayDeque<>()).addAll(List.of(replies));
        return this;
    }

    public int hits(String pathAndQuery) {
        AtomicInteger count = hits.get(pathAndQuery);
        return count == null ? 0 : count.get();
    }

    public List<String> requestIds() {
        return requestIds;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestURI().getPath()
                + (exchange.getRequestURI().getQuery() == null ? "" : "?" + exchange.getRequestURI().getQuery());
        hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (requestId != null) {
            requestIds.add(requestId);
        }
        Deque<Reply> queued = failures.get(key);
        Reply reply = queued == null ? null : queued.poll();
        if (reply == null) {
            reply = routes.getOrDefault(key, Reply.status(404));
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
        try {
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException e) {
            // el cliente cortó la conexión (timeout)
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
