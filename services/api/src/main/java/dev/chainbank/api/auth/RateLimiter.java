package dev.chainbank.api.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.AppClock;

/**
 * Sliding-window failure counter. In-memory, so limits are per API instance and reset on restart.
 * That is acceptable for the single-instance simulator; a multi-instance deployment would move this
 * to a shared store (PostgreSQL or Redis).
 */
@ApplicationScoped
public class RateLimiter {

    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    @Inject
    AppClock clock;

    /** Throws 429 if {@code key} already has {@code max} failures inside {@code window}. */
    public void check(String key, int max, Duration window) {
        Deque<Instant> q = failures.get(key);
        if (q == null) {
            return;
        }
        synchronized (q) {
            prune(q, window);
            if (q.size() >= max) {
                throw new ApiException(429, "RATE_LIMITED", "Too many failed attempts; try again later");
            }
        }
    }

    public void recordFailure(String key, Duration window) {
        Deque<Instant> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, window);
            q.addLast(clock.now());
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Instant> q, Duration window) {
        Instant cutoff = clock.now().minus(window);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
            q.pollFirst();
        }
    }
}
