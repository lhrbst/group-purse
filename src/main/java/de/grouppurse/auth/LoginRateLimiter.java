package de.grouppurse.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Einfache Bremse gegen Passwort-Raten: 5 Fehlversuche je E-Mail in 15 Minuten. */
@Component
public class LoginRateLimiter {
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public boolean isBlocked(String key) {
        Deque<Instant> d = failures.get(key);
        if (d == null) {
            return false;
        }
        synchronized (d) {
            prune(d);
            return d.size() >= MAX_FAILURES;
        }
    }

    public void recordFailure(String key) {
        Deque<Instant> d = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            prune(d);
            d.addLast(Instant.now());
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Instant> d) {
        Instant limit = Instant.now().minus(WINDOW);
        while (!d.isEmpty() && d.peekFirst().isBefore(limit)) {
            d.removeFirst();
        }
    }
}
