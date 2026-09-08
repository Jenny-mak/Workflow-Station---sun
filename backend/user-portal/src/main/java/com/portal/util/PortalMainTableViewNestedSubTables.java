package com.portal.util;

import com.platform.common.jdbc.SubTableRowIdentity;
import com.platform.common.jdbc.SubTableRowKeySupport;
import com.platform.common.subtable.SubTableStoreKeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nested {@code __subTables__} for a SUB Main Table View detail row.
 *
 * <p>The detail form's child grids (e.g. ATM Correspondence on an ATM Transaction) read this
 * map. Child rows are often a sibling slice on the process instance, not nested under the
 * parent JSON; those rows are taken from the instance store and kept only when the binding's
 * configured {@code foreign_key_field} matches this parent's identity. Nested copies on the
 * parent row win when that canonical key is already present (including an empty array).
 */
public final class PortalMainTableViewNestedSubTables {

    public static final String STORE_KEY = "__subTables__";

    private PortalMainTableViewNestedSubTables() {
    }

    /**
     * One SUB binding of the view's detail form: designer table name and the FK field that
     * scopes sibling rows to this parent.
     */
    public record NestedBinding(String tableName, String foreignKeyField) {
    }

    /**
     * @return a store keyed by {@code dw:<table>}, one entry per binding that has a table name.
     *         Empty when there are no nested bindings. Instance-level rows without a configured
     *         FK are not dumped onto the parent.
     */
    public static Map<String, Object> forParentRow(
            Map<String, Object> parentRow,
            Map<String, Object> instanceVariables,
            List<NestedBinding> nestedBindings) {
        return forParentRow(parentRow, instanceVariables, nestedBindings, null);
    }

    /**
     * @param parentPrimaryKeyFields the PARENT table's designer primary key columns
     *                               ({@code dw_field_definitions.is_primary_key}). Child rows are
     *                               matched to this parent by its identity, and the parent's key
     *                               can be named anything — {@code correspondence_id},
     *                               {@code case_number}, … — so it has to come from configuration.
     *                               Null/empty falls back to the platform-generated key alone,
     *                               which is all a row of a table with no configured PK carries.
     */
    public static Map<String, Object> forParentRow(
            Map<String, Object> parentRow,
            Map<String, Object> instanceVariables,
            List<NestedBinding> nestedBindings,
            List<String> parentPrimaryKeyFields) {
        if (nestedBindings == null || nestedBindings.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> nestedOnParent = storeOf(parentRow);
        Map<String, Object> instanceStore = storeOf(instanceVariables);
        Set<String> parentIds = SubTableRowIdentity.identityValuesOf(parentRow, parentPrimaryKeyFields);

        Map<String, Object> out = new LinkedHashMap<>();
        for (NestedBinding binding : nestedBindings) {
            putSlice(out, binding, nestedOnParent, instanceStore, parentIds);
        }
        return out;
    }

    private static void putSlice(
            Map<String, Object> out,
            NestedBinding binding,
            Map<String, Object> nestedOnParent,
            Map<String, Object> instanceStore,
            Set<String> parentIds) {
        String canonical = SubTableStoreKeys.dwKey(binding.tableName());
        if (canonical == null) {
            return;
        }
        if (nestedOnParent.containsKey(canonical)) {
            out.put(canonical, asRowList(nestedOnParent.get(canonical)));
            return;
        }
        if (!instanceStore.containsKey(canonical)) {
            out.put(canonical, List.of());
            return;
        }
        out.put(canonical, filterByForeignKey(
                asRowList(instanceStore.get(canonical)),
                binding.foreignKeyField(),
                parentIds));
    }

    static List<Map<String, Object>> filterByForeignKey(
            List<Map<String, Object>> rows,
            String foreignKeyField,
            Set<String> parentIds) {
        if (foreignKeyField == null || foreignKeyField.isBlank()
                || parentIds == null || parentIds.isEmpty()
                || rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object fk = SubTableRowKeySupport.getRowValueIgnoreCase(row, foreignKeyField);
            String scalar = MainTableViewFkDisplaySupport.scalarString(fk);
            if (scalar != null && parentIds.contains(scalar)) {
                out.add(row);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> asRowList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    static Map<String, Object> storeOf(Map<String, Object> vars) {
        if (vars == null) {
            return Map.of();
        }
        Object raw = vars.get(STORE_KEY);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }
}
