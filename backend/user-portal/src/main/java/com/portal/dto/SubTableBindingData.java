package com.portal.dto;

import lombok.Data;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Sub-table binding payload DTO.
 */
@Data
@Builder
public class SubTableBindingData {
    private Long bindingId;
    private String tableName;
    /**
     * Designer-configured display name (dw_table_definitions.table_display_name for SUB/PRIMARY,
     * rt_table_definitions.display_name for RELATED). Portals MUST prefer this over
     * {@link #tableName} when rendering — keeps User Portal aligned with the Developer Workstation
     * Form Preview (portal-design-parity).
     */
    private String tableDisplayName;
    private String bindingType;
    private String bindingMode;
    private Long subListViewId;
    private List<Map<String, Object>> columns;
    private List<Map<String, Object>> data;
    /**
     * The columns that identify a row of this table.
     *
     * <p>Normally the designer's primary key ({@code dw_field_definitions.is_primary_key}). When the
     * table declares none — the majority of them — this carries the platform's generated identity
     * key instead ({@code SubTableRowIdentity.CANONICAL_FIELD}), because such rows still each get a
     * UUID on persist and consumers must be able to tell them apart. It is empty only when the table
     * is genuinely unidentifiable.
     *
     * <p>So read this as "how to identify a row here", not as "what the user declared as the primary
     * key": the two differ precisely for PK-less tables.
     */
    private List<String> primaryKeyFields;
    /**
     * {@code dw_form_table_bindings.relation_table_id} when this binding targets a RELATION table
     * (including the platform's virtual {@code sys_users}), else {@code null}.
     *
     * <p>Without it the portal cannot tell a relation table from a designer sub-table: it filed both
     * under the same {@code dw:<name>} canonical key, and MI row isolation then treated a relation
     * table's rows as one participant's — failing every Save on that task. A relation table is not a
     * designer sub-table: it has no participant FK and legitimately no primary key.
     */
    private Long relationTableId;
    /** Relation table's own name ({@code rt_table_definitions.table_name}); null for designer tables. */
    private String relationTableName;
    /** MI assignment contract (allowUser/allowRole/assigneeField/roleField/buField) parsed from BPMN, keyed by tableName. */
    private Map<String, Object> assignmentConfig;
}
