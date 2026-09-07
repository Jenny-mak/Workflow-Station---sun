package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.i18n.I18nService;
import com.platform.common.subtable.SubTableStoreKeys;
import com.portal.exception.PortalException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@code type:"owner"} declarations from {@code dw_form_definitions}.
 */
final class OwnerFieldMetadataCatalog {

    private static final long EXISTENCE_TTL_MS = 30_000L;
    private static final long METADATA_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_CACHE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final I18nService i18nService;
    private final PortalPrimaryKeyAllocationComponent portalPrimaryKeyAllocationComponent;

    private final Map<Long, CachedMetadata> metadataCache = Collections.synchronizedMap(
            new LinkedHashMap<Long, CachedMetadata>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CachedMetadata> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    private volatile boolean anyOwnerField;
    private volatile long anyOwnerFieldCheckedAt;

    OwnerFieldMetadataCatalog(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              I18nService i18nService,
                              PortalPrimaryKeyAllocationComponent portalPrimaryKeyAllocationComponent) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.i18nService = i18nService;
        this.portalPrimaryKeyAllocationComponent = portalPrimaryKeyAllocationComponent;
    }

    FuOwnerFields loadIfPresent(String functionUnitIdOrCode, Map<String, Object> variables) {
        if (variables == null || functionUnitIdOrCode == null || functionUnitIdOrCode.isBlank()) {
            return FuOwnerFields.EMPTY;
        }
        if (!hasAnyOwnerField()) {
            return FuOwnerFields.EMPTY;
        }
        Long functionUnitId = portalPrimaryKeyAllocationComponent
                .resolveFunctionUnitIdForAllocation(functionUnitIdOrCode);
        return metadataFor(functionUnitId);
    }

    FuOwnerFields loadIfPresent(String functionUnitIdOrCode) {
        return loadIfPresent(functionUnitIdOrCode, Map.of());
    }

    static boolean shouldReuseExistenceCache(boolean lastResult, long checkedAt, long now, long ttlMs) {
        return lastResult && checkedAt > 0 && now - checkedAt < ttlMs;
    }

    private boolean hasAnyOwnerField() {
        long now = System.currentTimeMillis();
        if (shouldReuseExistenceCache(anyOwnerField, anyOwnerFieldCheckedAt, now, EXISTENCE_TTL_MS)) {
            return true;
        }
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM dw_form_definitions WHERE config_json::text LIKE '%\"owner\"%')",
                Boolean.class);
        anyOwnerField = Boolean.TRUE.equals(exists);
        anyOwnerFieldCheckedAt = now;
        return anyOwnerField;
    }

    private FuOwnerFields metadataFor(Long functionUnitId) {
        CachedMetadata cached = metadataCache.get(functionUnitId);
        if (cached != null && !cached.isExpired()) {
            return cached.metadata();
        }
        FuOwnerFields loaded = loadMetadata(functionUnitId);
        metadataCache.put(functionUnitId, new CachedMetadata(loaded, System.currentTimeMillis()));
        return loaded;
    }

    private FuOwnerFields loadMetadata(Long functionUnitId) {
        List<String> configs = jdbcTemplate.query(
                "SELECT config_json::text FROM dw_form_definitions WHERE function_unit_id = ?",
                (rs, rowNum) -> rs.getString(1),
                functionUnitId);
        if (configs.isEmpty()) {
            return FuOwnerFields.EMPTY;
        }
        Map<String, List<String>> sliceAliasesByBindingId = loadSliceAliases(functionUnitId);
        Map<String, OwnerFieldMeta> mainByField = new LinkedHashMap<>();
        Map<String, Map<String, OwnerFieldMeta>> subByAlias = new LinkedHashMap<>();
        for (String configText : configs) {
            collectFromConfig(configText, mainByField, subByAlias, sliceAliasesByBindingId);
        }
        Map<String, List<OwnerFieldMeta>> subFields = new LinkedHashMap<>();
        subByAlias.forEach((alias, byField) -> subFields.put(alias, List.copyOf(byField.values())));
        return new FuOwnerFields(List.copyOf(mainByField.values()), subFields);
    }

    @SuppressWarnings("unchecked")
    private void collectFromConfig(String configText, Map<String, OwnerFieldMeta> mainByField,
                                   Map<String, Map<String, OwnerFieldMeta>> subByAlias,
                                   Map<String, List<String>> sliceAliasesByBindingId) {
        Map<String, Object> config = parseConfig(configText);
        if (config == null) {
            return;
        }
        for (OwnerFieldMeta meta : collectOwners(config.get("rule"))) {
            mainByField.putIfAbsent(meta.field(), meta);
        }
        if (!(config.get("subForms") instanceof Map<?, ?> subForms)) {
            return;
        }
        for (Map.Entry<?, ?> sub : subForms.entrySet()) {
            if (!(sub.getValue() instanceof Map<?, ?> subEntry)) {
                continue;
            }
            List<OwnerFieldMeta> owners = collectOwners(subEntry.get("rule"));
            if (owners.isEmpty()) {
                continue;
            }
            List<String> aliases = sliceAliasesByBindingId
                    .getOrDefault(String.valueOf(sub.getKey()), List.of(String.valueOf(sub.getKey())));
            for (String alias : aliases) {
                Map<String, OwnerFieldMeta> byField = subByAlias.computeIfAbsent(alias, k -> new LinkedHashMap<>());
                owners.forEach(meta -> byField.putIfAbsent(meta.field(), meta));
            }
        }
    }

    private Map<String, List<String>> loadSliceAliases(Long functionUnitId) {
        Map<String, List<String>> aliases = new HashMap<>();
        RowCallbackHandler collect = rs -> {
            String bindingId = String.valueOf(rs.getLong("binding_id"));
            String tableName = rs.getString("table_name");
            List<String> keys = new ArrayList<>();
            keys.add(bindingId);
            if (tableName != null && !tableName.isBlank()) {
                keys.add(tableName);
                keys.add(tableName.toLowerCase(Locale.ROOT));
                String dwKey = SubTableStoreKeys.dwKey(tableName);
                if (dwKey != null) {
                    keys.add(dwKey);
                }
            }
            aliases.put(bindingId, keys);
        };
        jdbcTemplate.query(
                """
                SELECT ftb.id AS binding_id, td.table_name
                FROM dw_form_table_bindings ftb
                INNER JOIN dw_form_definitions fd ON fd.id = ftb.form_id
                INNER JOIN dw_table_definitions td ON td.id = ftb.table_id
                WHERE fd.function_unit_id = ? AND ftb.table_id IS NOT NULL
                """,
                collect,
                functionUnitId);
        return aliases;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String configText) {
        if (configText == null || configText.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(configText, Map.class);
        } catch (Exception e) {
            throw ownerError("form.owner.config_invalid", "config_json");
        }
    }

    private List<OwnerFieldMeta> collectOwners(Object ruleNode) {
        List<OwnerFieldMeta> owners = new ArrayList<>();
        walkOwners(ruleNode, owners);
        return owners;
    }

    @SuppressWarnings("unchecked")
    private void walkOwners(Object ruleNode, List<OwnerFieldMeta> owners) {
        if (ruleNode instanceof List<?> list) {
            list.forEach(n -> walkOwners(n, owners));
            return;
        }
        if (!(ruleNode instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> node = (Map<String, Object>) raw;
        if ("owner".equals(node.get("type")) && node.get("field") instanceof String field && !field.isBlank()) {
            Map<String, Object> props = node.get("props") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p
                    : Map.of();
            owners.add(new OwnerFieldMeta(field, parseSource(props.get("ownerConfig"), field)));
        }
        if (node.get("children") instanceof List<?> children) {
            children.forEach(c -> walkOwners(c, owners));
        }
    }

    @SuppressWarnings("unchecked")
    private String parseSource(Object ownerConfig, String field) {
        if (ownerConfig == null) {
            return OwnerCaseHandlerCalculator.SOURCE_CREATOR;
        }
        Map<String, Object> parsed;
        if (ownerConfig instanceof Map<?, ?> map) {
            parsed = (Map<String, Object>) map;
        } else if (ownerConfig instanceof String s) {
            if (s.isBlank()) {
                return OwnerCaseHandlerCalculator.SOURCE_CREATOR;
            }
            try {
                parsed = objectMapper.readValue(s, Map.class);
            } catch (Exception e) {
                throw ownerError("form.owner.config_invalid", field);
            }
        } else {
            throw ownerError("form.owner.config_invalid", field);
        }
        Object source = parsed.get("source");
        if (source == null) {
            return OwnerCaseHandlerCalculator.SOURCE_CREATOR;
        }
        if (!(source instanceof String raw) || raw.isBlank()) {
            throw ownerError("form.owner.config_invalid", field);
        }
        String normalized = OwnerCaseHandlerCalculator.normalizeSource(raw);
        if (OwnerCaseHandlerCalculator.knownSource(normalized)) {
            return normalized;
        }
        throw ownerError("form.owner.config_invalid", field);
    }

    private PortalException ownerError(String messageKey, String arg) {
        return new PortalException("400", i18nService.getMessage(messageKey, arg));
    }

    /**
     * Designer primary-key columns for a {@code dw:<name>} / {@code rt:<name>} / binding-id slice.
     * Empty when unresolved — callers then match on {@code platformRowUuid} only.
     */
    List<String> designerPrimaryKeyFieldsForSlice(String sliceKey) {
        if (sliceKey == null || sliceKey.isBlank() || jdbcTemplate == null) {
            return List.of();
        }
        String tableName = SubTableStoreKeys.tableNameOf(sliceKey);
        try {
            if (tableName != null && !tableName.isBlank()) {
                return jdbcTemplate.queryForList("""
                        SELECT f.field_name
                        FROM dw_field_definitions f
                        JOIN dw_table_definitions t ON t.id = f.table_id
                        WHERE lower(t.table_name) = lower(?)
                          AND COALESCE(f.is_primary_key, false) = true
                        ORDER BY f.sort_order NULLS LAST, f.id
                        """, String.class, tableName);
            }
            if (sliceKey.chars().allMatch(Character::isDigit)) {
                return jdbcTemplate.queryForList("""
                        SELECT f.field_name
                        FROM dw_form_table_bindings b
                        JOIN dw_field_definitions f ON f.table_id = b.table_id
                        WHERE b.id = ? AND COALESCE(f.is_primary_key, false) = true
                        ORDER BY f.sort_order NULLS LAST, f.id
                        """, String.class, Long.valueOf(sliceKey));
            }
        } catch (RuntimeException ex) {
            // FALLBACK(external): origin PK identity — unresolved key matches platformRowUuid only
            return List.of();
        }
        return List.of();
    }

    private record CachedMetadata(FuOwnerFields metadata, long cachedAt) {

        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > METADATA_TTL_MS;
        }
    }
}
