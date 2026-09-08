package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.i18n.I18nService;
import com.platform.common.jdbc.SubTableRowIdentity;
import com.platform.common.subtable.SubTableStoreKeys;
import com.portal.exception.PortalException;
import com.portal.service.ProcessAssigneeSnapshot;
import com.portal.service.UserDisplayNameResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server-side authority for Owner fields ({@code type:"owner"}, see
 * {@code docs/design/owner-field-component.md} §3.2/§3.3/§6.2/§6.3).
 *
 * <p>{@code CREATOR}: empty → {@code user:<startUserId>} (main) or row creator (sub);
 * a non-empty person is never overwritten. {@code CURRENT_ASSIGNEE}: written
 * from the current-task assignee rule (claimed person or unclaimed candidate
 * pool) onto this Owner column on MAIN and SUB rows.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OwnerFieldComponent {

    static final String SUB_TABLES_KEY = "__subTables__";
    public static final String DISPLAY_SUFFIX = "__display";
    static final String USER_PREFIX = "user:";
    static final String GROUP_PREFIX = "group:";
    static final String GROUP_SEPARATOR = "|";
    static final String GROUP_DISPLAY_SEPARATOR = " / ";
    static final String SOURCE_CREATOR = "CREATOR";
    static final String SOURCE_CURRENT_ASSIGNEE = "CURRENT_ASSIGNEE";

    private static final long EXISTENCE_TTL_MS = 30_000L;
    private static final long METADATA_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_CACHE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final UserDisplayNameResolver userDisplayNameResolver;
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

    /**
     * Submit-path context. {@code previousVariables} is the map before client merge
     * (null on process start) so Creator ignores client person changes.
     */
    public record OwnerWriteContext(
            String actorUserId,
            String startUserId,
            String assigneeUserId,
            String candidateUserIds,
            Map<String, Object> previousVariables
    ) {
    }

    /** MAIN-table Owner field names grouped by source, for View live projection. */
    public record OwnerViewHints(Set<String> creatorFields, Set<String> assigneeFields) {
        public static final OwnerViewHints EMPTY = new OwnerViewHints(Set.of(), Set.of());

        public boolean isEmpty() {
            return creatorFields.isEmpty() && assigneeFields.isEmpty();
        }
    }

    /**
     * Validates and fills Owner fields in {@code variables} (mutated in place).
     */
    public void applyOnSubmit(String functionUnitIdOrCode, OwnerWriteContext ctx, Map<String, Object> variables) {
        FuOwnerFields metadata = metadataOrEmpty(functionUnitIdOrCode, variables);
        if (metadata.isEmpty() || ctx == null) {
            return;
        }
        for (OwnerFieldMeta meta : metadata.mainFields()) {
            applyToRecord(variables, meta, ctx, true, ctx.previousVariables());
        }
        applyToSubTableRows(variables, metadata, ctx);
    }

    /**
     * Write-path for task lifecycle: overwrite MAIN and SUB {@code CURRENT_ASSIGNEE}
     * columns from the current-task assignee rule. Does not touch Creator columns.
     */
    public void applyAssigneeSnapshot(String functionUnitIdOrCode, Map<String, Object> variables,
                                      String assigneeUserId, String candidateUserIds) {
        FuOwnerFields metadata = metadataOrEmpty(functionUnitIdOrCode, variables);
        if (metadata.isEmpty()) {
            return;
        }
        OwnerWriteContext ctx = new OwnerWriteContext(null, null, assigneeUserId, candidateUserIds, null);
        for (OwnerFieldMeta meta : metadata.mainFields()) {
            if (SOURCE_CURRENT_ASSIGNEE.equals(meta.source())) {
                writeAssignee(variables, meta, ctx);
            }
        }
        applyAssigneeToSubRows(variables, metadata, ctx);
    }

    /**
     * Read-path: refresh {@code __display} from this Owner column's stored IDs.
     * Does not overlay system Current Assignee columns. Does not persist.
     */
    public void projectForRead(String functionUnitIdOrCode, OwnerWriteContext ctx, Map<String, Object> variables) {
        FuOwnerFields metadata = metadataOrEmpty(functionUnitIdOrCode, variables);
        if (metadata.isEmpty() || ctx == null) {
            return;
        }
        for (OwnerFieldMeta meta : metadata.mainFields()) {
            refreshStoredDisplay(variables, meta);
        }
        projectSubRowDisplays(variables, metadata);
    }

    public OwnerViewHints viewHints(String functionUnitIdOrCode) {
        FuOwnerFields metadata = loadMetadataIfPresent(functionUnitIdOrCode);
        if (metadata.isEmpty()) {
            return OwnerViewHints.EMPTY;
        }
        Set<String> creator = new LinkedHashSet<>();
        Set<String> assignee = new LinkedHashSet<>();
        for (OwnerFieldMeta meta : metadata.mainFields()) {
            if (SOURCE_CURRENT_ASSIGNEE.equals(meta.source())) {
                assignee.add(meta.field());
            } else {
                creator.add(meta.field());
            }
        }
        return new OwnerViewHints(Set.copyOf(creator), Set.copyOf(assignee));
    }

    @SuppressWarnings("unchecked")
    private void applyToSubTableRows(Map<String, Object> variables, FuOwnerFields metadata, OwnerWriteContext ctx) {
        if (metadata.subFieldsBySliceKey().isEmpty()
                || !(variables.get(SUB_TABLES_KEY) instanceof Map<?, ?> rawSlices)) {
            return;
        }
        Map<String, Object> previousSlices = previousSubSlices(ctx.previousVariables());
        for (Map.Entry<String, Object> slice : ((Map<String, Object>) rawSlices).entrySet()) {
            List<OwnerFieldMeta> metas = metadata.identify(slice.getKey());
            if (metas == null || metas.isEmpty() || !(slice.getValue() instanceof List<?> rows)) {
                continue;
            }
            List<Map<String, Object>> previousRows = previousSliceRows(previousSlices, slice.getKey());
            // Matching a row to its previous version needs that table's identity columns. They come
            // from Table Design, not from a list of likely names — a table keyed by
            // `correspondence_id` matches no such list, and a business column called `id` is not an
            // identity just because of its name.
            List<String> pkFields = designerPrimaryKeyFieldsForSlice(slice.getKey());
            for (Object row : rows) {
                if (row instanceof Map<?, ?> rowMap) {
                    Map<String, Object> typed = (Map<String, Object>) rowMap;
                    Map<String, Object> previousRow = matchPreviousRow(previousRows, typed, pkFields);
                    for (OwnerFieldMeta meta : metas) {
                        applyToRecord(typed, meta, ctx, false, previousRow);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyAssigneeToSubRows(Map<String, Object> variables, FuOwnerFields metadata,
                                        OwnerWriteContext ctx) {
        if (metadata.subFieldsBySliceKey().isEmpty()
                || !(variables.get(SUB_TABLES_KEY) instanceof Map<?, ?> rawSlices)) {
            return;
        }
        for (Map.Entry<String, Object> slice : ((Map<String, Object>) rawSlices).entrySet()) {
            List<OwnerFieldMeta> metas = metadata.identify(slice.getKey());
            if (metas == null || metas.isEmpty() || !(slice.getValue() instanceof List<?> rows)) {
                continue;
            }
            for (Object row : rows) {
                if (row instanceof Map<?, ?> rowMap) {
                    Map<String, Object> typed = (Map<String, Object>) rowMap;
                    for (OwnerFieldMeta meta : metas) {
                        if (SOURCE_CURRENT_ASSIGNEE.equals(meta.source())) {
                            writeAssignee(typed, meta, ctx);
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void projectSubRowDisplays(Map<String, Object> variables, FuOwnerFields metadata) {
        if (metadata.subFieldsBySliceKey().isEmpty()
                || !(variables.get(SUB_TABLES_KEY) instanceof Map<?, ?> rawSlices)) {
            return;
        }
        for (Map.Entry<String, Object> slice : ((Map<String, Object>) rawSlices).entrySet()) {
            List<OwnerFieldMeta> metas = metadata.identify(slice.getKey());
            if (metas == null || metas.isEmpty() || !(slice.getValue() instanceof List<?> rows)) {
                continue;
            }
            for (Object row : rows) {
                if (row instanceof Map<?, ?> rowMap) {
                    for (OwnerFieldMeta meta : metas) {
                        refreshStoredDisplay((Map<String, Object>) rowMap, meta);
                    }
                }
            }
        }
    }

    private void applyToRecord(Map<String, Object> record, OwnerFieldMeta meta, OwnerWriteContext ctx,
                               boolean mainTable, Map<String, Object> previousRecord) {
        if (SOURCE_CURRENT_ASSIGNEE.equals(meta.source())) {
            writeAssignee(record, meta, ctx);
            return;
        }
        writeCreator(record, meta, fillUserId(ctx, mainTable), previousRecord);
    }

    private static String fillUserId(OwnerWriteContext ctx, boolean mainTable) {
        if (mainTable) {
            return ctx.startUserId() != null && !ctx.startUserId().isBlank()
                    ? ctx.startUserId() : ctx.actorUserId();
        }
        return ctx.actorUserId();
    }

    private void writeCreator(Map<String, Object> record, OwnerFieldMeta meta, String fillUserId,
                              Map<String, Object> previousRecord) {
        String previous = scalar(previousRecord, meta.field());
        if (isStoredOwnerValue(previous)) {
            record.put(meta.field(), previous);
            record.put(meta.field() + DISPLAY_SUFFIX, displayForStored(meta, previous));
            return;
        }
        if (fillUserId == null || fillUserId.isBlank()) {
            return;
        }
        String display = userDisplayNameResolver.resolveIfExists(fillUserId)
                .orElseThrow(() -> ownerError("portal.owner.user_not_found", fillUserId));
        record.put(meta.field(), USER_PREFIX + fillUserId);
        record.put(meta.field() + DISPLAY_SUFFIX, display);
    }

    private void refreshStoredDisplay(Map<String, Object> record, OwnerFieldMeta meta) {
        String value = scalar(record, meta.field());
        if (value.startsWith(GROUP_PREFIX)) {
            record.put(meta.field() + DISPLAY_SUFFIX, displayForLeftoverGroup(value));
            return;
        }
        List<String> ids = parseStoredUserIds(value);
        if (ids.isEmpty()) {
            return;
        }
        Map<String, String> cache = userDisplayNameResolver.resolveBatch(Set.copyOf(ids));
        String display = namesForUserIds(ids, cache);
        if (!display.isBlank()) {
            record.put(meta.field() + DISPLAY_SUFFIX, display);
        }
    }

    private void writeAssignee(Map<String, Object> record, OwnerFieldMeta meta, OwnerWriteContext ctx) {
        List<String> ids = assigneeUserIds(ctx);
        if (ids.isEmpty()) {
            record.put(meta.field(), "");
            record.remove(meta.field() + DISPLAY_SUFFIX);
            return;
        }
        Map<String, String> cache = userDisplayNameResolver.resolveBatch(Set.copyOf(ids));
        record.put(meta.field(), joinStoredUserValues(ids));
        String display = namesForUserIds(ids, cache);
        if (!display.isBlank()) {
            record.put(meta.field() + DISPLAY_SUFFIX, display);
        } else {
            record.remove(meta.field() + DISPLAY_SUFFIX);
        }
    }

    private String namesForUserIds(List<String> ids, Map<String, String> cache) {
        return ids.stream()
                .map(id -> userDisplayNameResolver.resolveCached(id, cache))
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(UserDisplayNameResolver.MULTI_ASSIGNEE_DISPLAY_SEPARATOR));
    }

    private static List<String> assigneeUserIds(OwnerWriteContext ctx) {
        List<String> assigneeKeys = ctx.assigneeUserId() == null || ctx.assigneeUserId().isBlank()
                ? List.of()
                : ProcessAssigneeSnapshot.parseDelimitedUserKeys(ctx.assigneeUserId());
        if (assigneeKeys.size() == 1) {
            return assigneeKeys;
        }
        return List.copyOf(ProcessAssigneeSnapshot.collectUserKeys(ctx.assigneeUserId(), ctx.candidateUserIds()));
    }

    static String joinStoredUserValues(List<String> ids) {
        return ids.stream().map(id -> USER_PREFIX + id).collect(Collectors.joining(","));
    }

    /**
     * Parses {@code user:<id>} or {@code user:<id1>,user:<id2>} into user ids.
     * Group leftovers and blank values return an empty list.
     */
    public static List<String> parseStoredUserIds(String value) {
        if (value == null || value.isBlank() || value.startsWith(GROUP_PREFIX)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(USER_PREFIX) && trimmed.length() > USER_PREFIX.length()) {
                String id = trimmed.substring(USER_PREFIX.length()).trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private String displayForStored(OwnerFieldMeta meta, String value) {
        if (value.startsWith(GROUP_PREFIX)) {
            return displayForLeftoverGroup(value);
        }
        List<String> ids = parseStoredUserIds(value);
        if (ids.isEmpty()) {
            throw ownerError("portal.owner.invalid_format", meta.field());
        }
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            names.add(userDisplayNameResolver.resolveIfExists(id)
                    .orElseThrow(() -> ownerError("portal.owner.user_not_found", id)));
        }
        return String.join(UserDisplayNameResolver.MULTI_ASSIGNEE_DISPLAY_SEPARATOR, names);
    }

    private String displayForLeftoverGroup(String value) {
        String rest = value.substring(GROUP_PREFIX.length());
        int sep = rest.indexOf(GROUP_SEPARATOR);
        if (sep <= 0 || sep != rest.lastIndexOf(GROUP_SEPARATOR) || sep == rest.length() - 1) {
            return value;
        }
        String buName = queryNameByCode("sys_business_units", rest.substring(0, sep).trim());
        String roleName = queryNameByCode("sys_roles", rest.substring(sep + 1).trim());
        if (buName == null || roleName == null) {
            return value;
        }
        return buName + GROUP_DISPLAY_SEPARATOR + roleName;
    }

    private static boolean isStoredOwnerValue(String value) {
        return value != null && !value.isBlank()
                && (value.startsWith(USER_PREFIX) || value.startsWith(GROUP_PREFIX));
    }

    private static String scalar(Map<String, Object> record, String field) {
        if (record == null) {
            return "";
        }
        Object raw = record.get(field);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> previousSubSlices(Map<String, Object> previousVariables) {
        if (previousVariables == null || !(previousVariables.get(SUB_TABLES_KEY) instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> previousSliceRows(Map<String, Object> previousSlices, String sliceKey) {
        if (previousSlices == null) {
            return List.of();
        }
        Object rows = previousSlices.get(sliceKey);
        if (rows == null) {
            rows = previousSlices.get(sliceKey.toLowerCase(Locale.ROOT));
        }
        if (!(rows instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> typed = new ArrayList<>();
        for (Object row : list) {
            if (row instanceof Map<?, ?> map) {
                typed.add((Map<String, Object>) map);
            }
        }
        return typed;
    }

    /**
     * The designer primary key of the table a {@code dw:<name>} / {@code rt:<name>} slice belongs
     * to. Empty when the key cannot be resolved — the caller then matches on the platform-generated
     * row key alone, which is what a table with no configured primary key carries anyway.
     */
    private List<String> designerPrimaryKeyFieldsForSlice(String sliceKey) {
        if (sliceKey == null || sliceKey.isBlank() || jdbcTemplate == null) {
            return List.of();
        }
        // Slices arrive keyed either canonically (`dw:<name>` / `rt:<name>`) or by bare binding id,
        // so both are resolvable back to the table whose primary key we need.
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
            log.warn("Could not resolve primary key for slice {}: {}", sliceKey, ex.getMessage());
        }
        return List.of();
    }

    private static Map<String, Object> matchPreviousRow(List<Map<String, Object>> previousRows,
                                                        Map<String, Object> row,
                                                        List<String> designerPrimaryKeyFields) {
        if (previousRows == null || previousRows.isEmpty()) {
            return null;
        }
        Set<String> identities = SubTableRowIdentity.identityValuesOf(row, designerPrimaryKeyFields);
        if (identities.isEmpty()) {
            return null;
        }
        for (Map<String, Object> previous : previousRows) {
            Set<String> previousIds =
                    SubTableRowIdentity.identityValuesOf(previous, designerPrimaryKeyFields);
            previousIds.retainAll(identities);
            if (!previousIds.isEmpty()) {
                return previous;
            }
        }
        return null;
    }

    private String queryNameByCode(String table, String code) {
        List<String> names = jdbcTemplate.query(
                "SELECT name FROM " + table + " WHERE code = ?",
                (rs, rowNum) -> rs.getString(1),
                code);
        return names.isEmpty() ? null : names.get(0);
    }

    private FuOwnerFields metadataOrEmpty(String functionUnitIdOrCode, Map<String, Object> variables) {
        if (variables == null) {
            return FuOwnerFields.EMPTY;
        }
        return loadMetadataIfPresent(functionUnitIdOrCode);
    }

    private FuOwnerFields loadMetadataIfPresent(String functionUnitIdOrCode) {
        if (functionUnitIdOrCode == null || functionUnitIdOrCode.isBlank()) {
            return FuOwnerFields.EMPTY;
        }
        if (!hasAnyOwnerField()) {
            return FuOwnerFields.EMPTY;
        }
        Long functionUnitId = portalPrimaryKeyAllocationComponent
                .resolveFunctionUnitIdForAllocation(functionUnitIdOrCode);
        return metadataFor(functionUnitId);
    }

    private boolean hasAnyOwnerField() {
        long now = System.currentTimeMillis();
        if (now - anyOwnerFieldCheckedAt < EXISTENCE_TTL_MS) {
            return anyOwnerField;
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
            log.error("Unparseable dw_form_definitions.config_json encountered: {}", e.getMessage());
            return null;
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
            return SOURCE_CREATOR;
        }
        Map<String, Object> parsed;
        if (ownerConfig instanceof Map<?, ?> map) {
            parsed = (Map<String, Object>) map;
        } else if (ownerConfig instanceof String s) {
            if (s.isBlank()) {
                return SOURCE_CREATOR;
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
            return SOURCE_CREATOR;
        }
        if (!(source instanceof String raw) || raw.isBlank()) {
            throw ownerError("form.owner.config_invalid", field);
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (SOURCE_CREATOR.equals(normalized) || SOURCE_CURRENT_ASSIGNEE.equals(normalized)) {
            return normalized;
        }
        throw ownerError("form.owner.config_invalid", field);
    }

    private PortalException ownerError(String messageKey, String arg) {
        return new PortalException("400", i18nService.getMessage(messageKey, arg));
    }

    record OwnerFieldMeta(String field, String source) {
    }

    private record FuOwnerFields(List<OwnerFieldMeta> mainFields,
                                 Map<String, List<OwnerFieldMeta>> subFieldsBySliceKey) {

        static final FuOwnerFields EMPTY = new FuOwnerFields(List.of(), Map.of());

        boolean isEmpty() {
            return mainFields.isEmpty() && subFieldsBySliceKey.isEmpty();
        }

        List<OwnerFieldMeta> identify(String sliceKey) {
            List<OwnerFieldMeta> metas = subFieldsBySliceKey.get(sliceKey);
            return metas != null ? metas
                    : subFieldsBySliceKey.get(sliceKey.toLowerCase(Locale.ROOT));
        }
    }

    private record CachedMetadata(FuOwnerFields metadata, long cachedAt) {

        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > METADATA_TTL_MS;
        }
    }
}
