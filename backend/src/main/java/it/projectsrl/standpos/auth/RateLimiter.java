package it.projectsrl.standpos.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window limiter, per key (client IP, device id). One backend instance is the v1 deployment; a
 * shared store is only needed when the backend is scaled out.
 */
public class RateLimiter {

    private final int maxEvents;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Long>> events = new ConcurrentHashMap<>();

    public RateLimiter(int maxEvents, Duration window, Clock clock) {
        this.maxEvents = maxEvents;
        this.window = window;
        this.clock = clock;
    }

    /** Records an attempt for {@code key}; returns false when the key is over its limit. */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        long cutoff = now - window.toMillis();
        boolean[] allowed = {false};
        events.compute(key, (k, deque) -> {
            Deque<Long> d = deque == null ? new ArrayDeque<>() : deque;
            while (!d.isEmpty() && d.peekFirst() <= cutoff) {
                d.pollFirst();
            }
            if (d.size() < maxEvents) {
                d.addLast(now);
                allowed[0] = true;
            }
            return d;
        });
        if (events.size() > 10_000) {
            events.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().peekLast() <= cutoff);
        }
        return allowed[0];
    }

    public void reset() {
        events.clear();
    }
}
