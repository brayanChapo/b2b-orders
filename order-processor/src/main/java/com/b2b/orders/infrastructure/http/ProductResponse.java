package com.b2b.orders.infrastructure.http;

public record ProductResponse(String productId, String name, String sku, String status, String taxCategory) {
}
