package com.b2b.orders.domain.validation;

import java.io.Serial;
import java.util.List;
import java.util.stream.Collectors;

public class InvalidOrderException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<Violation> violations;

    public InvalidOrderException(List<Violation> violations) {
        super(violations.stream()
                .map(v -> v.field() + ": " + v.message())
                .collect(Collectors.joining("; ")));
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("se requiere al menos una violación");
        }
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }

    /** Primera violación en orden determinista: es la que se informa como código principal. */
    public Violation primary() {
        return violations.getFirst();
    }
}
