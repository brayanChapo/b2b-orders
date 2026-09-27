package com.b2b.orders.infrastructure.messaging;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.function.Supplier;

public final class UlidGenerator implements Supplier<String> {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UlidGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String get() {
        char[] out = new char[26];
        long time = clock.millis();
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        byte[] bytes = new byte[10];
        random.nextBytes(bytes);
        long high = ((bytes[0] & 0xFFL) << 8) | (bytes[1] & 0xFFL);
        long low = 0;
        for (int i = 2; i < 10; i++) {
            low = (low << 8) | (bytes[i] & 0xFFL);
        }
        for (int i = 25; i >= 10; i--) {
            out[i] = ALPHABET[(int) (low & 31)];
            low = (low >>> 5) | ((high & 31) << 59);
            high >>>= 5;
        }
        return new String(out);
    }
}
