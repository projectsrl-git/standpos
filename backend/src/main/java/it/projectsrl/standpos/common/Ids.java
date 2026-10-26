package it.projectsrl.standpos.common;

import java.security.SecureRandom;
import java.util.UUID;

/** Generates time-ordered UUIDs, version 7 (RFC 9562): 48-bit Unix milliseconds followed by random bits. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long randB = RANDOM.nextLong();
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
