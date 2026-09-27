package com.b2b.orders.application;

public record TechnicalError(ErrorCategory category, String code, String summary, int attempts) {
}
