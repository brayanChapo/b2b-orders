package com.b2b.orders.infrastructure;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class Contracts {

    public static final Path ROOT = Path.of("..", "contracts");

    private Contracts() {
    }

    public static String read(String relative) {
        Path file = ROOT.resolve(relative);
        assumeTrue(Files.exists(file), "contratos no disponibles: " + file);
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Path> examples(String folder) {
        Path dir = ROOT.resolve("events/examples").resolve(folder);
        assumeTrue(Files.isDirectory(dir), "contratos no disponibles: " + dir);
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
