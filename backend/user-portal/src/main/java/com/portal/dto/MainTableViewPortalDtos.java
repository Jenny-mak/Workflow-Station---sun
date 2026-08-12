package com.portal.dto;

import lombok.Builder;

import java.util.List;
import java.util.Map;

public final class MainTableViewPortalDtos {

    private MainTableViewPortalDtos() {}

    @Builder
    public record FunctionUnitViewMenuItem(
            String functionUnitId,
            String functionUnitCode,
            String functionUnitName,
            int viewCount
    ) {}

    @Builder
    public record MainTableViewSummary(
            Long id,
            String viewName,
            Boolean isDefault,
            // Owning table — lets the portal group the view selector by table (parity with View Design).
            Long tableId,
            String tableLabel,
            Boolean enableExport,
            Boolean enableImport
    ) {}

    @Builder
    public record MainTableViewFieldColumn(
            String fieldName,
            String displayLabel,
            Integer columnWidth,
            Boolean systemField,
            // FK drill-down hints: when isForeignKey, the portal renders a link to the referenced
            // table's published default view (refViewId), pre-filtered by this column's value.
            Boolean isForeignKey,
            Long refViewId,
            String refFunctionUnitCode,
            List<String> refPrimaryKeyFields,
            // Lookup drill-down: when isLookup, the portal links to the referenced Relation Table's data
            // (lookupTableId), pre-filtered by this cell's value. Resolved from the form's lookupConfig.
            Boolean isLookup,
            Long lookupTableId,
            /** {@code field}, {@code lookup_display}, or {@code fk_display}. */
            String columnType,
            /** For lookup_display / fk_display: source field on the owning table. */
            String lookupSourceField,
            /** For lookup_display / fk_display: attribute on the related row. */
            String lookupDisplayField,
            /** From form lookupConfig.selectedDisplayField — used to label the source lookup column. */
            String lookupSelectedDisplayField,
            /** From form lookupConfig.searchFields — PK hydration hint for the portal. */
            List<String> lookupSearchFields,
            /** For fk_display: referenced DW table id (from FieldDefinition.refTableId). */
            Long fkRefTableId
    ) {}

    @Builder
    public record MainTableViewDataRow(
            String processInstanceId,
            Map<String, Object> values
    ) {}

    /**
     * Runtime column filter from the portal grid header (AND across fields).
     * Operators mirror designer/portal client: eq/ne/contains/notContains/startsWith/endsWith/isNull/isNotNull.
     */
    @Builder
    public record MainTableViewColumnFilter(
            String fieldName,
            String operator,
            String value
    ) {}

    /** Full-set group size for a group-by label (pagination is still by data rows). */
    @Builder
    public record MainTableViewGroupCount(
            String label,
            long count
    ) {}

    @Builder
    public record MainTableViewDataPage(
            List<MainTableViewFieldColumn> columns,
            List<MainTableViewDataRow> rows,
            long total,
            int page,
            int size,
            /** Present when {@code groupBy} was requested; full filtered-set counts keyed by label. */
            List<MainTableViewGroupCount> groupCounts
    ) {}
}
