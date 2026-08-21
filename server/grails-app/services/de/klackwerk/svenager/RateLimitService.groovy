package de.klackwerk.svenager

import groovy.transform.CompileStatic

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-key token bucket for the public endpoints. Buckets refill
 * continuously to their per-minute rate; the map is pruned so an address
 * scan cannot grow it without bound.
 */
@CompileStatic
class RateLimitService {

    private static final int MAX_BUCKETS = 10_000
    private static final long IDLE_EVICT_NANOS = 10L * 60 * 1_000_000_000

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>()

    /** True when the caller identified by key may proceed; false = throttled. */
    boolean allow(String key, int perMinute) {
        if (perMinute <= 0) {
            return true
        }
        pruneIfCrowded()
        Bucket bucket = buckets.computeIfAbsent(key) { new Bucket(perMinute) }
        bucket.tryTake(perMinute)
    }

    private void pruneIfCrowded() {
        if (buckets.size() < MAX_BUCKETS) {
            return
        }
        long cutoff = System.nanoTime() - IDLE_EVICT_NANOS
        buckets.entrySet().removeIf { it.value.lastRefill < cutoff }
        // Under active flooding everything is fresh — drop arbitrary entries
        // rather than let the map grow unbounded (refused callers just get a
        // fresh, full bucket; memory safety wins over strictness here).
        if (buckets.size() >= MAX_BUCKETS) {
            Iterator<String> it = buckets.keySet().iterator()
            for (int i = 0; i < MAX_BUCKETS / 10 && it.hasNext(); i++) {
                it.next()
                it.remove()
            }
        }
    }

    @CompileStatic
    private static class Bucket {
        double tokens
        volatile long lastRefill = System.nanoTime()

        Bucket(int perMinute) {
            tokens = perMinute
        }

        synchronized boolean tryTake(int perMinute) {
            long now = System.nanoTime()
            double refill = (now - lastRefill) / 60_000_000_000d * perMinute
            tokens = Math.min((double) perMinute, tokens + refill)
            lastRefill = now
            if (tokens >= 1d) {
                tokens -= 1d
                return true
            }
            false
        }
    }
}
