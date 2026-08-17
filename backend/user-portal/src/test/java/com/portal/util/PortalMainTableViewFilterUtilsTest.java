package com.portal.util;

import com.portal.dto.MainTableViewPortalDtos.MainTableViewColumnFilter;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewGroupCount;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalMainTableViewFilterUtilsTest {

    @Test
    void applyRuntimeFilters_containsAndEq() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Open", "Alpha"));
        rows.add(row("Closed", "Beta"));
        rows.add(row("Open", "Gamma"));

        PortalMainTableViewFilterUtils.applyRuntimeFilters(rows, List.of(
                MainTableViewColumnFilter.builder()
                        .fieldName("status")
                        .operator("eq")
                        .value("Open")
                        .build(),
                MainTableViewColumnFilter.builder()
                        .fieldName("name")
                        .operator("contains")
                        .value("mm")
                        .build()));

        assertEquals(1, rows.size());
        assertEquals("Gamma", rows.get(0).get("name"));
    }

    @Test
    void applyQuerySort_groupByThenRuntimeSort() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("B", "2"));
        rows.add(row("A", "9"));
        rows.add(row("A", "1"));
        rows.add(row("B", "1"));

        PortalMainTableViewFilterUtils.applyQuerySort(rows, "status", "name", "ASC", List.of());

        assertEquals("A", rows.get(0).get("status"));
        assertEquals("1", rows.get(0).get("name"));
        assertEquals("A", rows.get(1).get("status"));
        assertEquals("9", rows.get(1).get("name"));
        assertEquals("B", rows.get(2).get("status"));
        assertEquals("1", rows.get(2).get("name"));
    }

    @Test
    void buildGroupCounts_preservesEncounterOrder() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Open", "a"));
        rows.add(row("Open", "b"));
        rows.add(row("Closed", "c"));

        List<MainTableViewGroupCount> counts =
                PortalMainTableViewFilterUtils.buildGroupCounts(rows, "status");

        assertEquals(2, counts.size());
        assertEquals("Open", counts.get(0).label());
        assertEquals(2L, counts.get(0).count());
        assertEquals("Closed", counts.get(1).label());
        assertEquals(1L, counts.get(1).count());
    }

    @Test
    void groupLabel_blankBecomesEmDash() {
        assertEquals("—", PortalMainTableViewFilterUtils.groupLabel(null));
        assertEquals("—", PortalMainTableViewFilterUtils.groupLabel("  "));
        assertTrue(PortalMainTableViewFilterUtils.groupLabel(Map.of("name", "X")).equals("X"));
    }

    private static Map<String, Object> row(String status, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("name", name);
        return m;
    }
}
