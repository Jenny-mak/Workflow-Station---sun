package com.portal.component;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MiBpmnNameMapCacheTest {

    @Test
    void loadsOnceWithinTtl() {
        MiBpmnNameMapCache cache = new MiBpmnNameMapCache();
        AtomicInteger loads = new AtomicInteger();
        Map<String, String> first = cache.getOrLoad("atm", () -> {
            loads.incrementAndGet();
            return Map.of("sub form1", "multi");
        });
        Map<String, String> second = cache.getOrLoad("atm", () -> {
            loads.incrementAndGet();
            return Map.of();
        });
        assertThat(loads.get()).isEqualTo(1);
        assertThat(second).isSameAs(first);
    }
}
