package com.b2b.orders.infrastructure.http;

public record ClientResponse(String clientId, String name, String status, String segment, String taxRegime, String market) {
}
