package com.portal.component;

import java.util.List;
import java.util.Map;

record ChangeHistoryBindingAliases(
        Map<String, String> aliasToBinding,
        Map<String, String> bindingToHistoryName,
        Map<String, Integer> aliasPriorities,
        /**
         * Per binding, the columns that identify a row of that table
         * ({@code dw_field_definitions.is_primary_key}).
         *
         * <p>Sub-table audit pairs a submitted row with its enriched counterpart by identity, and a
         * table's key can be named anything the designer chose — {@code correspondence_id},
         * {@code case_number}. Without this the pairing falls back to the platform-generated key
         * alone, which rows saved through other paths may not carry. An absent or empty entry means
         * "this table declares no primary key", not "use a likely column name".
         */
        Map<String, List<String>> primaryKeyFieldsByBinding) {
}
