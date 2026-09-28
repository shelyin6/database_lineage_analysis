package com.xcloud.metadata.inceptor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory cache of procedure source text.
 *
 * <p>Reading {@code full_text} costs ~4-5 s per query on the intranet (dblink backed view), while a
 * metadata-only query costs ~0.2 s. The cache is therefore keyed by database + procedure and stores
 * the {@code create_time} value as a version marker: a cheap metadata query decides whether the
 * cached text can be reused, so "preview then analyse" does not read the same source twice.
 *
 * <p>Bounded by entry count and estimated bytes (LRU eviction) and by a TTL, because
 * {@code create_time} does not change when a procedure is updated in place.
 */
@Component
public class ProcedureSourceCache {

    private final InceptorProperties properties;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    private long bytes;
    private long hits;
    private long misses;
    private long evictions;

    public ProcedureSourceCache(InceptorProperties properties) {
        this.properties = properties;
    }

    public synchronized CachedProcedure get(String key, String version) {
        if (!properties.isCacheEnabled()) {
            misses++;
            return null;
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            misses++;
            return null;
        }
        if (isExpired(entry) || versionChanged(entry, version)) {
            remove(key);
            misses++;
            return null;
        }
        hits++;
        return new CachedProcedure(entry.version(), entry.row());
    }

    public synchronized void put(String key, String version, InceptorProcedureRow row) {
        if (!properties.isCacheEnabled() || row == null || row.fullText() == null) {
            return;
        }
        remove(key);
        int size = estimateBytes(row);
        entries.put(key, new Entry(version, row, System.currentTimeMillis(), size));
        bytes += size;
        evictIfNeeded();
    }

    public synchronized void invalidate(String key) {
        remove(key);
    }

    public synchronized void clear() {
        entries.clear();
        bytes = 0;
    }

    public synchronized CacheStats stats() {
        long total = hits + misses;
        return new CacheStats(
                properties.isCacheEnabled(),
                entries.size(),
                bytes,
                hits,
                misses,
                evictions,
                total == 0 ? 0 : (int) Math.round(hits * 100.0 / total));
    }

    private void evictIfNeeded() {
        int maxEntries = Math.max(1, properties.getCacheMaxEntries());
        long maxBytes = Math.max(1024L, properties.getCacheMaxBytes());
        while ((entries.size() > maxEntries || bytes > maxBytes) && entries.size() > 1) {
            Iterator<String> iterator = entries.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            remove(iterator.next());
            evictions++;
        }
    }

    private boolean isExpired(Entry entry) {
        long ttlMillis = Math.max(1, properties.getCacheTtlSeconds()) * 1000L;
        return System.currentTimeMillis() - entry.storedAt() > ttlMillis;
    }

    private boolean versionChanged(Entry entry, String version) {
        if (!properties.isCacheVerifyVersion() || version == null) {
            return false;
        }
        return !version.equals(entry.version());
    }

    private void remove(String key) {
        Entry removed = entries.remove(key);
        if (removed != null) {
            bytes = Math.max(0, bytes - removed.size());
        }
    }

    /** Rough estimate: source text plus column overhead. */
    private int estimateBytes(InceptorProcedureRow row) {
        int textBytes = row.fullText() == null ? 0 : row.fullText().length() * 2;
        return textBytes + 256;
    }

    private record Entry(String version, InceptorProcedureRow row, long storedAt, int size) {
    }

    public record CachedProcedure(String version, InceptorProcedureRow row) {
    }

    public record CacheStats(
            boolean enabled,
            int entries,
            long bytes,
            long hits,
            long misses,
            long evictions,
            int hitPercent
    ) {
    }
}
