package com.portal.component;

import com.platform.common.subtable.SubTableStoreKeys;

import java.util.List;
import java.util.Locale;
import java.util.Map;

record OwnerFieldMeta(String field, String source) {
}

record FuOwnerFields(List<OwnerFieldMeta> mainFields,
                     Map<String, List<OwnerFieldMeta>> subFieldsBySliceKey) {

    static final FuOwnerFields EMPTY = new FuOwnerFields(List.of(), Map.of());

    boolean isEmpty() {
        return mainFields.isEmpty() && subFieldsBySliceKey.isEmpty();
    }

    /**
     * Runtime {@code __subTables__} keys are canonical {@code dw:<table>} / {@code rt:<table>}.
     * Catalog aliases are binding id and table name; resolve the table name from the store key
     * instead of missing the slice (Participants never got Creator / Case Handler).
     */
    List<OwnerFieldMeta> identify(String sliceKey) {
        if (sliceKey == null || sliceKey.isBlank()) {
            return null;
        }
        List<OwnerFieldMeta> metas = subFieldsBySliceKey.get(sliceKey);
        if (metas != null) {
            return metas;
        }
        metas = subFieldsBySliceKey.get(sliceKey.toLowerCase(Locale.ROOT));
        if (metas != null) {
            return metas;
        }
        String tableName = SubTableStoreKeys.tableNameOf(sliceKey);
        return tableName == null ? null : subFieldsBySliceKey.get(tableName);
    }
}
