package com.portal.util;

import com.platform.common.audit.SystemAuditFields;
import com.platform.common.dto.RelationFieldDTO;
import com.platform.common.enums.RelationDataType;
import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the shared-list column declaration for a relation table from its field
 * definitions ({@code rt_field_definitions} is the authority for both column set and
 * display labels — see {@code getViewFieldsByTableId}). The row-level {@code status}
 * column is a toggle, not a data column, and is excluded here like in the grid.
 *
 * <p>Grouping is declared {@code false} for every column: the relation-table query
 * endpoint does not execute GROUP BY, and the declaration must never promise a
 * capability the endpoint cannot deliver.
 */
public final class RelationTableColumnSpec {

    private RelationTableColumnSpec() {
    }

    public static List<PortalListColumnMeta> columnsFor(List<RelationFieldDTO> fields) {
        List<PortalListColumnMeta> columns = new ArrayList<>();
        for (RelationFieldDTO field : fields) {
            if (field.getFieldName() == null || "status".equals(field.getFieldName())) {
                continue;
            }
            columns.add(columnFor(field));
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("relation table declares no displayable fields");
        }
        return columns;
    }

    private static PortalListColumnMeta columnFor(RelationFieldDTO field) {
        String label = field.getDisplayName() != null && !field.getDisplayName().isBlank()
                ? field.getDisplayName()
                : field.getFieldName();
        RelationDataType dataType = field.getDataType();
        Kind kind = SystemAuditFields.isTimestamp(field.getFieldName())
                ? Kind.DATETIME
                : SystemAuditFields.isUser(field.getFieldName())
                        ? Kind.USER
                        : kindFor(dataType);
        if (kind == null) {
            // JSON / TIME / BYTEA / FILE payloads render but have no meaningful SQL predicate.
            return PortalListColumnMeta.displayOnly(field.getFieldName(), label, Kind.TEXT);
        }
        List<PortalListColumnMeta.Option> options = kind == Kind.BOOLEAN
                ? PortalListColumnMeta.booleanOptions()
                : List.of();
        return new PortalListColumnMeta(
                field.getFieldName(), label, kind,
                true, true, false,
                PortalListColumnMeta.operatorsFor(kind), options);
    }

    /** @return the filterable kind for a data type, or null when the type is display-only */
    private static Kind kindFor(RelationDataType dataType) {
        if (dataType == null) {
            return null;
        }
        return switch (dataType) {
            case VARCHAR, TEXT -> Kind.TEXT;
            // A LOOKUP stores the referenced row's PK as text (or a JSON array when multiple),
            // so text operators over the stored value are the honest capability.
            case LOOKUP -> Kind.TEXT;
            case INTEGER, BIGINT, DECIMAL -> Kind.NUMBER;
            case BOOLEAN -> Kind.BOOLEAN;
            case DATE, TIMESTAMP -> Kind.DATETIME;
            case JSON, TIME, BYTEA, FILE -> null;
        };
    }
}
