package com.admin.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight Function Unit grouping entry for the Relation Tables nav sidebar: one Function Unit
 * <em>code</em> that has at least one deployed relation table, with a distinct table count.
 * A code spans every published version of the unit, so there is no single {@code sys_function_units.id}
 * to expose here — the nav identifies the entry by code.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionUnitTableGroupResponse {

    private String functionUnitCode;
    private String functionUnitName;
    private long tableCount;
}
