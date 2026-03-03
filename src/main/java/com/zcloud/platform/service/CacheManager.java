package com.zcloud.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Hand-rolled caching layer used across the platform.
 * Anti-patterns:
 * - Static mutable maps shared across all instances (defeats Spring singleton scoping)
 * - No TTL enforcement — isExpired() exists but nothing calls it automatically
 * - Memory leak: entries are never evicted
 * - Thread-safety issues: check-then-act race conditions between containsKey + put
 * - Unsafe casts in getTyped()
 * - Object values lose type information
 * - No max-size limit — can grow unbounded until OOM
 * - Reinvents what Spring Cache / Caffeine / Redis already do
 */
@Component
public class CacheManager {

    private static final Logger log = LoggerFactory.getLogger(CacheManager.class);

    // Anti-pattern: static mutable state — shared across tests, context reloads, etc.
    private static final Map<String, Object> cache = new ConcurrentHashMap<>();
    private static final Map<String, Long> timestamps = new ConcurrentHashMap<>();

    // Anti-pattern: tracking stats with static mutable counters — not thread-safe increments
    private static long hitCount = 0;
    private static long missCount = 0;

    /**
     * Store a value in the cache. Overwrites any existing entry.
     */
    public void put(String key, Object value) {
        if (key == null) {
            log.warn("Attempted to cache null key — ignoring");
            return;
        }
        // Anti-pattern: no size check, no eviction policy
        cache.put(key, value);
        timestamps.put(key, System.currentTimeMillis());
        log.debug("Cached key: {} (total entries: {})", key, cache.size());
    }

    /**
     * Retrieve a value from the cache.
     * Returns null if missing — caller must handle null (anti-pattern: no Optional).
     */
    public Object get(String key) {
        Object value = cache.get(key);
        if (value != null) {
            hitCount++; // Anti-pattern: non-atomic increment
            log.debug("Cache HIT for key: {}", key);
        } else {
            missCount++; // Anti-pattern: non-atomic increment
            log.debug("Cache MISS for key: {}", key);
        }
        return value;
    }

    /**
     * Retrieve a value with type casting.
     * Anti-pattern: unchecked cast — ClassCastException at runtime if types mismatch.
     */
    @SuppressWarnings("unchecked")
    public <T> T getTyped(String key, Class<T> type) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        // Anti-pattern: no instanceof check before cast — just pray it works
        try {
            return (T) value;
        } catch (ClassCastException e) {
            log.error("Cache type mismatch for key: {} — expected {} but got {}",
                    key, type.getSimpleName(), value.getClass().getSimpleName());
            // Anti-pattern: silently remove the bad entry and return null
            cache.remove(key);
            timestamps.remove(key);
            return null;
        }
    }

    /**
     * Check whether a cached entry has exceeded the given TTL.
     * Anti-pattern: this method exists but is NEVER called automatically.
     * Callers must manually check expiration — which nobody does consistently.
     */
    public boolean isExpired(String key, int ttlSeconds) {
        Long ts = timestamps.get(key);
        if (ts == null) {
            return true; // no timestamp means treat as expired
        }
        long elapsed = System.currentTimeMillis() - ts;
        return elapsed > (ttlSeconds * 1000L);
    }

    /**
     * Check if a key exists in the cache.
     * Anti-pattern: check-then-act — key could be removed between containsKey and get.
     */
    public boolean containsKey(String key) {
        return cache.containsKey(key);
    }

    /**
     * Remove all entries whose keys start with the given prefix.
     * Used for bulk invalidation (e.g., "client:" prefix clears all client entries).
     * Anti-pattern: iterates entire keySet — O(n) every time.
     */
    public void invalidate(String keyPrefix) {
        // Anti-pattern: collect to list then remove — race condition between collect and remove
        Set<String> keysToRemove = cache.keySet().stream()
                .filter(k -> k.startsWith(keyPrefix))
                .collect(Collectors.toSet());

        int removed = keysToRemove.size();
        keysToRemove.forEach(k -> {
            cache.remove(k);
            timestamps.remove(k);
        });

        log.info("Invalidated {} cache entries with prefix: {}", removed, keyPrefix);
    }

    /**
     * Remove a single key from the cache.
     */
    public void remove(String key) {
        cache.remove(key);
        timestamps.remove(key);
    }

    /**
     * Nuke everything.
     */
    public void clearAll() {
        int size = cache.size();
        cache.clear();
        timestamps.clear();
        hitCount = 0;
        missCount = 0;
        log.warn("Cleared entire cache ({} entries destroyed)", size);
    }

    /**
     * Returns current number of entries. No synchronization guarantee with concurrent puts.
     */
    public int getSize() {
        return cache.size();
    }

    /**
     * Returns cache hit/miss stats as a formatted string.
     * Anti-pattern: returns a formatted string instead of a typed stats object.
     */
    public String getStats() {
        long total = hitCount + missCount;
        double hitRate = total > 0 ? (double) hitCount / total * 100.0 : 0.0;
        return String.format("CacheStats[size=%d, hits=%d, misses=%d, hitRate=%.1f%%]",
                cache.size(), hitCount, missCount, hitRate);
    }
}
