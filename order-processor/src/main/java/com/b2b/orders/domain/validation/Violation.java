package com.b2b.orders.domain.validation;

public record Violation(ViolationCode code, String field, String message) {
}
