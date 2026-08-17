package com.portal.util;

import com.portal.dto.MainTableViewPortalDtos.MainTableViewColumnFilter;
import com.portal.util.MainTableViewSqlQueryCompiler.FieldMeta;
import com.portal.util.MainTableViewSqlQueryCompiler.RowSource;
import com.portal.util.MainTableViewSqlQueryCompiler.SqlFragment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTableViewSqlQueryCompilerTest {

    private MainTableViewSqlQueryCompiler mainCompiler() {
        return new MainTableViewSqlQueryCompiler(RowSource.MAIN, List.of(
                new FieldMeta("case_number", false, "field", null),
                new FieldMeta("process_status", true, "field", null),
                new FieldMeta("lookup_name", false, "lookup_display", "customer_id")));
    }

    @Test
    void columnFilter_eq_usesBindParam() {
        MainTableViewSqlQueryCompiler c = mainCompiler();
        SqlFragment f = c.compileColumnFilters(List.of(
                MainTableViewColumnFilter.builder()
                        .fieldName("case_number")
                        .operator("eq")
                        .value("ATM-1")
                        .build()));
        assertTrue(f.sql().contains("variables ->> 'case_number'"));
        assertTrue(f.sql().contains("?"));
        assertEquals(List.of("ATM-1"), f.params());
    }

    @Test
    void lookupDisplay_filtersOnSourceField() {
        MainTableViewSqlQueryCompiler c = mainCompiler();
        SqlFragment f = c.compileColumnFilters(List.of(
                MainTableViewColumnFilter.builder()
                        .fieldName("lookup_name")
                        .operator("contains")
                        .value("acme")
                        .build()));
        assertTrue(f.sql().contains("variables ->> 'customer_id'"));
        assertFalse(f.sql().contains("lookup_name"));
    }

    @Test
    void systemField_usesRealColumn() {
        MainTableViewSqlQueryCompiler c = mainCompiler();
        SqlFragment f = c.compileColumnFilters(List.of(
                MainTableViewColumnFilter.builder()
                        .fieldName("process_status")
                        .operator("eq")
                        .value("RUNNING")
                        .build()));
        assertTrue(f.sql().contains("pi.status"));
    }

    @Test
    void designerFilter_andLogic() {
        MainTableViewSqlQueryCompiler c = mainCompiler();
        SqlFragment f = c.compileDesignerFilter(Map.of(
                "logic", "and",
                "conditions", List.of(
                        Map.of("fieldName", "case_number", "operator", "contains", "value", "ATM"),
                        Map.of("fieldName", "process_status", "operator", "eq", "value", "RUNNING"))));
        assertTrue(f.sql().contains(" AND "));
        assertEquals(2, f.params().size());
    }

    @Test
    void involvement_threePredicates() {
        MainTableViewSqlQueryCompiler c = mainCompiler();
        SqlFragment f = c.compileInvolvement("user-dev");
        assertTrue(f.sql().contains("ACT_HI_TASKINST"));
        assertTrue(f.sql().contains("__subTables__"));
        assertEquals(3, f.params().size());
    }

    @Test
    void orderBy_groupThenSort() {
        MainTableViewSqlQueryCompiler c = mainCompiler();
        SqlFragment f = c.compileOrderBy("process_status", "case_number", "DESC", List.of());
        assertTrue(f.sql().contains("pi.status ASC"));
        assertTrue(f.sql().contains("case_number"));
        assertTrue(f.sql().contains("DESC"));
    }

    @Test
    void search_orAcrossFields() {
        MainTableViewSqlQueryCompiler c = mainCompiler();
        SqlFragment f = c.compileSearch("x", Set.of("case_number", "process_status"));
        assertTrue(f.sql().contains(" OR "));
        assertTrue(f.params().size() >= 2);
    }

    @Test
    void subRow_usesSubElemPath() {
        MainTableViewSqlQueryCompiler c = new MainTableViewSqlQueryCompiler(RowSource.SUB, List.of(
                new FieldMeta("item_name", false, "field", null)));
        SqlFragment f = c.compileColumnFilters(List.of(
                MainTableViewColumnFilter.builder()
                        .fieldName("item_name")
                        .operator("eq")
                        .value("a")
                        .build()));
        assertTrue(f.sql().contains("pi.sub_elem ->> 'item_name'"));
    }
}
