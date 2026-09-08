package com.admin.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Table binding DTO.
 * Describes the binding relationship between a form and a data table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableBindingDTO {
    private Long bindingId;
    /** Physical or relation-bound table id (dw_table_definitions.id for SUB/PRIMARY). */
    private Long tableId;
    private String bindingType;    // "PRIMARY", "SUB", "RELATED", "ACTION"
    private String bindingMode;    // "EDITABLE", "READONLY"
    /** When bindingType is SUB: FULL (default) or FORM_ONLY (inline / link-form detail only, not standalone list). */
    private String subMode;
    private String foreignKeyField;
    private Integer sortOrder;
    private String tableName;
    /**
     * Designer-configured display name (dw_table_definitions.table_display_name for SUB/PRIMARY,
     * rt_table_definitions.display_name for RELATED). Portals MUST prefer this over
     * {@link #tableName} when rendering sub-table headers / tab labels, so the User Portal
     * matches the Developer Workstation Form Preview (portal-design-parity).
     * May be {@code null} when the designer left it blank — callers should fall back to
     * {@link #tableName}.
     */
    private String tableDisplayName;
    private String tableType;
    private String tableDescription;
    /**
     * The columns that identify a row of this table, for portals to merge / identify sub-table rows
     * without hardcoding {@code id} / {@code rowId}.
     *
     * <p>Normally the designer's primary key from {@code dw_field_definitions} ({@code is_primary_key},
     * ordered by {@code sort_order}). When the table declares none — the majority of them, including
     * every RELATED binding — {@code FormTableBindingLoader} substitutes the platform's generated
     * identity key ({@code SubTableRowIdentity.CANONICAL_FIELD}), since rows of a PK-less table each
     * still receive a UUID on persist. Empty only when the table is genuinely unidentifiable.
     *
     * <p>So read this as "how to identify a row here", not as "what the user declared as the primary
     * key": the two differ precisely for PK-less tables.
     */
    private List<String> primaryKeyFields;
    /**
     * When bindingType is SUB: {@code structuralFk} (default) or {@code miParticipantRow} for MI slice bindings.
     */
    private String bindingLinkMode;
    /**
     * Table field metadata (FK/PK) for Portal / Preview row-add runtime (PRD S5).
     */
    private List<TableFieldDefinitionDTO> fieldDefinitions;
}
