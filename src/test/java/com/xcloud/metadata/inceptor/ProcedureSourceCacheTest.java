package com.xcloud.metadata.inceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The source cache is what keeps a second "查看源码 / 分析" of the same procedure from paying the
 * 4-5 second {@code full_text} read again.
 */
class ProcedureSourceCacheTest {

    private static final String KEY = "ads.p_demo";

    private static InceptorProcedureRow row(String text, String createTime) {
        return new InceptorProcedureRow("p_demo", "string", text, "root", "USER", createTime, "ads", text.length());
    }

    @Test
    void returnsCachedTextWhenTheVersionMarkerIsUnchanged() {
        InceptorProperties properties = new InceptorProperties();
        ProcedureSourceCache cache = new ProcedureSourceCache(properties);

        cache.put(KEY, "2025-08-28 15:31:12.0", row("BEGIN NULL; END;", "2025-08-28 15:31:12.0"));

        ProcedureSourceCache.CachedProcedure cached = cache.get(KEY, "2025-08-28 15:31:12.0");
        assertNotNull(cached);
        assertEquals("BEGIN NULL; END;", cached.row().fullText());
        assertEquals(1, cache.stats().hits());
        assertEquals(0, cache.stats().misses());
        assertEquals(100, cache.stats().hitPercent());
    }

    @Test
    void dropsTheEntryWhenCreateTimeChanged() {
        ProcedureSourceCache cache = new ProcedureSourceCache(new InceptorProperties());

        cache.put(KEY, "2025-08-28 15:31:12.0", row("BEGIN NULL; END;", "2025-08-28 15:31:12.0"));

        assertNull(cache.get(KEY, "2025-09-01 09:00:00.0"));
        assertEquals(0, cache.stats().entries());
    }

    @Test
    void versionCheckCanBeDisabled() {
        InceptorProperties properties = new InceptorProperties();
        properties.setCacheVerifyVersion(false);
        ProcedureSourceCache cache = new ProcedureSourceCache(properties);

        cache.put(KEY, "2025-08-28 15:31:12.0", row("BEGIN NULL; END;", "2025-08-28 15:31:12.0"));

        assertNotNull(cache.get(KEY, "2025-09-01 09:00:00.0"));
    }

    @Test
    void expiresEntriesAfterTheTtl() throws Exception {
        InceptorProperties properties = new InceptorProperties();
        properties.setCacheTtlSeconds(1);
        ProcedureSourceCache cache = new ProcedureSourceCache(properties);

        cache.put(KEY, "v1", row("BEGIN NULL; END;", "v1"));
        Thread.sleep(1100);

        assertNull(cache.get(KEY, "v1"));
        assertEquals(0, cache.stats().entries());
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryAboveMaxEntries() {
        InceptorProperties properties = new InceptorProperties();
        properties.setCacheMaxEntries(2);
        ProcedureSourceCache cache = new ProcedureSourceCache(properties);

        cache.put("ads.p_1", "v", row("one", "v"));
        cache.put("ads.p_2", "v", row("two", "v"));
        // Touch p_1 so p_2 becomes the least recently used entry.
        assertNotNull(cache.get("ads.p_1", "v"));
        cache.put("ads.p_3", "v", row("three", "v"));

        assertNull(cache.get("ads.p_2", "v"));
        assertNotNull(cache.get("ads.p_1", "v"));
        assertNotNull(cache.get("ads.p_3", "v"));
        assertEquals(2, cache.stats().entries());
        assertEquals(1, cache.stats().evictions());
    }

    @Test
    void disabledCacheNeverStoresAnything() {
        InceptorProperties properties = new InceptorProperties();
        properties.setCacheEnabled(false);
        ProcedureSourceCache cache = new ProcedureSourceCache(properties);

        cache.put(KEY, "v", row("BEGIN NULL; END;", "v"));

        assertNull(cache.get(KEY, "v"));
        assertEquals(0, cache.stats().entries());
        assertFalse(cache.stats().enabled());
    }

    @Test
    void clearDropsEveryEntry() {
        ProcedureSourceCache cache = new ProcedureSourceCache(new InceptorProperties());
        cache.put(KEY, "v", row("BEGIN NULL; END;", "v"));

        cache.clear();

        assertEquals(0, cache.stats().entries());
        assertEquals(0, cache.stats().bytes());
    }
}
