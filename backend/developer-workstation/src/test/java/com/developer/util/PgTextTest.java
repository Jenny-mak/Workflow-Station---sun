package com.developer.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PgTextTest {

    private static final String NUL = String.valueOf((char) 0);

    @Test
    void removesNulFromTextAndNestedJson() {
        assertNull(PgText.clean(null));
        String plain = "no nul here";
        assertSame(plain, PgText.clean(plain));
        assertEquals("ab", PgText.clean("a" + NUL + "b"));

        Map<String, Object> json = Map.of(
                "k" + NUL, List.of("x" + NUL, 3, Map.of("deep", NUL + "y")),
                "flag", true);
        Map<String, Object> cleaned = PgText.cleanJson(json);
        assertEquals(Map.of("k", List.of("x", 3, Map.of("deep", "y")), "flag", true), cleaned);
        assertNull(PgText.cleanJson((Map<String, Object>) null));
    }
}
