package com.portal.util;

import com.platform.common.dto.RelationFieldDTO;
import com.platform.common.enums.RelationDataType;
import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationTableColumnSpecTest {

    private RelationFieldDTO field(String name, RelationDataType type, String displayName) {
        return RelationFieldDTO.builder()
                .fieldName(name)
                .dataType(type)
                .displayName(displayName)
                .build();
    }

    @Test
    void mapsDataTypesToKinds() {
        List<PortalListColumnMeta> columns = RelationTableColumnSpec.columnsFor(List.of(
                field("name", RelationDataType.VARCHAR, "Name"),
                field("notes", RelationDataType.TEXT, "Notes"),
                field("ref_user", RelationDataType.LOOKUP, "User"),
                field("qty", RelationDataType.INTEGER, "Qty"),
                field("amount", RelationDataType.DECIMAL, "Amount"),
                field("active", RelationDataType.BOOLEAN, "Active"),
                field("due_date", RelationDataType.DATE, "Due"),
                field("created_at", RelationDataType.TIMESTAMP, "Created")));
        assertEquals(Kind.TEXT, byField(columns, "name").kind());
        assertEquals(Kind.TEXT, byField(columns, "notes").kind());
        assertEquals(Kind.TEXT, byField(columns, "ref_user").kind());
        assertEquals(Kind.NUMBER, byField(columns, "qty").kind());
        assertEquals(Kind.NUMBER, byField(columns, "amount").kind());
        assertEquals(Kind.BOOLEAN, byField(columns, "active").kind());
        assertEquals(Kind.DATETIME, byField(columns, "due_date").kind());
        assertEquals(Kind.DATETIME, byField(columns, "created_at").kind());
    }

    @Test
    void payloadTypesAreDisplayOnly() {
        List<PortalListColumnMeta> columns = RelationTableColumnSpec.columnsFor(List.of(
                field("cfg", RelationDataType.JSON, "Config"),
                field("at", RelationDataType.TIME, "At"),
                field("blob", RelationDataType.BYTEA, "Blob"),
                field("doc", RelationDataType.FILE, "Doc"),
                field("name", RelationDataType.VARCHAR, "Name")));
        for (String f : List.of("cfg", "at", "blob", "doc")) {
            PortalListColumnMeta col = byField(columns, f);
            assertFalse(col.filterable(), f);
            assertFalse(col.sortable(), f);
            assertTrue(col.operators().isEmpty(), f);
        }
        assertTrue(byField(columns, "name").filterable());
    }

    @Test
    void statusIsExcludedAndGroupingNeverDeclared() {
        List<PortalListColumnMeta> columns = RelationTableColumnSpec.columnsFor(List.of(
                field("status", RelationDataType.VARCHAR, "Status"),
                field("active", RelationDataType.BOOLEAN, "Active"),
                field("name", RelationDataType.VARCHAR, "Name")));
        assertEquals(List.of("active", "name"), columns.stream().map(PortalListColumnMeta::field).toList());
        // The endpoint has no GROUP BY execution — even closed-value kinds must not promise it.
        assertTrue(columns.stream().noneMatch(PortalListColumnMeta::groupable));
    }

    @Test
    void auditTimestampDeclaredAsVarcharIsStillADateFilter() {
        PortalListColumnMeta created = byField(RelationTableColumnSpec.columnsFor(List.of(
                field("created_at", RelationDataType.VARCHAR, "Created At"),
                field("name", RelationDataType.VARCHAR, "Name"))), "created_at");
        assertEquals(Kind.DATETIME, created.kind());
        assertEquals("today", created.operators().get(0));
    }

    @Test
    void auditUserDeclaredAsVarcharIsStillAPeopleFilter() {
        PortalListColumnMeta createdBy = byField(RelationTableColumnSpec.columnsFor(List.of(
                field("created_by", RelationDataType.VARCHAR, "Created By"),
                field("name", RelationDataType.VARCHAR, "Name"))), "created_by");
        assertEquals(Kind.USER, createdBy.kind());
        assertEquals(List.of("eq", "ne", "isNull", "isNotNull"), createdBy.operators());
        assertFalse(createdBy.groupable());
    }

    @Test
    void booleanColumnCarriesTrueFalseOptions() {
        PortalListColumnMeta active = byField(RelationTableColumnSpec.columnsFor(List.of(
                field("active", RelationDataType.BOOLEAN, "Active"))), "active");
        assertEquals(List.of("true", "false"),
                active.options().stream().map(PortalListColumnMeta.Option::value).toList());
    }

    @Test
    void labelFallsBackToFieldNameAndUnknownTypeIsDisplayOnly() {
        List<PortalListColumnMeta> columns = RelationTableColumnSpec.columnsFor(List.of(
                field("raw_code", RelationDataType.VARCHAR, "  "),
                field("legacy", null, "Legacy")));
        assertEquals("raw_code", byField(columns, "raw_code").label());
        assertFalse(byField(columns, "legacy").filterable());
    }

    @Test
    void rejectsTablesWithoutDisplayableFields() {
        assertThrows(IllegalStateException.class, () -> RelationTableColumnSpec.columnsFor(List.of(
                field("status", RelationDataType.VARCHAR, "Status"))));
    }

    private PortalListColumnMeta byField(List<PortalListColumnMeta> columns, String field) {
        return columns.stream().filter(c -> c.field().equals(field)).findFirst().orElseThrow();
    }
}
