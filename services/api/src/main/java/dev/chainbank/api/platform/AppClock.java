package dev.chainbank.api.platform;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Single source of application time. Security decisions (TOTP steps, session and challenge expiry)
 * use this rather than the database clock so tests can move time deterministically.
 */
@ApplicationScoped
public class AppClock {

    /** Microsecond precision, matching PostgreSQL timestamptz, so stored values round-trip exactly. */
    public Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
