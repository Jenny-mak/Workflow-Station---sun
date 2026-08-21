package com.portal.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationTableQueryRequestTest {

    @Test
    void rejectsNonBlankGroupBy() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new RelationTableQueryRequest(0, 20, null, List.of(), null, null, "status"));
        assertTrue(ex.getMessage().contains("groupBy"));
    }

    @Test
    void blankOrNullGroupByIsNormalizedAway() {
        assertNull(new RelationTableQueryRequest(0, 20, null, List.of(), null, null, null).groupBy());
        assertNull(new RelationTableQueryRequest(0, 20, null, List.of(), null, null, "  ").groupBy());
        assertDoesNotThrow(() -> RelationTableQueryRequest.of(0, 20, null, List.of(), null, null));
    }
}
