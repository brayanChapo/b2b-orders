package com.b2b.orders.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DomainIndependenceTest {

    private static final Path DOMAIN = Path.of("src/main/java/com/b2b/orders/domain");

    private static final List<String> FORBIDDEN = List.of(
            "org.springframework",
            "org.apache.kafka",
            "com.mongodb",
            "org.bson",
            "com.fasterxml.jackson",
            "jakarta.",
            "javax.",
            "java.net.http",
            "org.slf4j",
            "io.github.resilience4j",
            "lombok",
            "com.b2b.orders.application",
            "com.b2b.orders.infrastructure");

    @Test
    void elDominioSoloDependeDeJavaYDeSiMismo() throws IOException {
        assertThat(DOMAIN).isDirectory();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DOMAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ") && FORBIDDEN.stream().anyMatch(trimmed::contains)) {
                        violations.add(DOMAIN.relativize(file) + ": " + trimmed);
                    }
                }
            }
        }
        assertThat(violations).as("imports prohibidos en el dominio").isEmpty();
    }
}
