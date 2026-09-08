package com.portal.component;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Shared TTL cache for the inner-task → outer MI box map. One bean for My Request
 * Current Step / Current Assignee and Owner Case Handler writes.
 */
@Component
final class MiBpmnNameMapCache {

    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_SIZE = 64;

    private final Map<String, Cached> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > MAX_SIZE;
                }
            });

    Map<String, String> getIfPresent(String processDefinitionKey) {
        Cached cached = cache.get(processDefinitionKey);
        if (cached == null || System.currentTimeMillis() - cached.cachedAt >= TTL_MS) {
            return null;
        }
        return cached.map;
    }

    Map<String, String> getOrLoad(String processDefinitionKey, Supplier<Map<String, String>> load) {
        Map<String, String> present = getIfPresent(processDefinitionKey);
        if (present != null) {
            return present;
        }
        Map<String, String> loaded = load.get();
        cache.put(processDefinitionKey, new Cached(loaded, System.currentTimeMillis()));
        return loaded;
    }

    private record Cached(Map<String, String> map, long cachedAt) {
    }
}
