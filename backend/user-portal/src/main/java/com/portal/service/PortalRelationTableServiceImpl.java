package com.portal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.dto.RelationFieldDTO;
import com.platform.common.dto.RelationTableDTO;
import com.platform.common.enums.RelationDataType;
import com.platform.common.enums.RelationPermissionLevel;
import com.platform.common.enums.RelationTableStatus;
import com.platform.common.relationtable.RelationCsvValueFormatter;
import com.platform.common.relationtable.RelationRowValidator;
import com.platform.common.relationtable.RelationTableTemplateService;
import com.platform.common.relationtable.RowValidationResult;
import com.platform.security.util.SecurityContextUtils;
import com.portal.component.RoleAccessComponent;
import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.RelationTableDataPage;
import com.portal.dto.RelationTableQueryRequest;
import com.portal.util.ListFilterSql;
import com.portal.util.RelationTableColumnSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Portal implementation for Relation Table access. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalRelationTableServiceImpl implements PortalRelationTableService {

    private static final String DATA_ROWS_TABLE = "rt_table_data_rows";
    private static final Long SYSTEM_USER_TABLE_ID = -1_000_000_001L;
    private static final String SYSTEM_USER_TABLE_NAME = "sys_users";
    /** Design §10 #10 — deep pages slower than this emit a WARN without filter values or row payloads. */
    private static final long SLOW_PAGE_WARN_MS = 1_000L;
    private static final List<String> SYSTEM_USER_FIELD_NAMES = List.of(
            "id", "username", "display_name", "full_name", "email", "employee_id", "status", "language");
    private static final List<String> DEFAULT_SYSTEM_USER_SEARCH_FIELDS = List.of(
            "username", "display_name", "full_name", "email", "employee_id");
    /** Matches {@code chk_sys_user_status} and common Admin aliases stored as-is. */
    private static final List<PortalListColumnMeta.Option> SYSTEM_USER_STATUS_OPTIONS = List.of(
            new PortalListColumnMeta.Option("ACTIVE", "Active"),
            new PortalListColumnMeta.Option("INACTIVE", "Inactive"),
            new PortalListColumnMeta.Option("DISABLED", "Disabled"),
            new PortalListColumnMeta.Option("LOCKED", "Locked"),
            new PortalListColumnMeta.Option("PENDING", "Pending"));
    /** Covers underscore and hyphen locale codes seen in {@code sys_users.language}. */
    private static final List<PortalListColumnMeta.Option> SYSTEM_USER_LANGUAGE_OPTIONS = List.of(
            new PortalListColumnMeta.Option("en", "English"),
            new PortalListColumnMeta.Option("zh_CN", "简体中文 (zh_CN)"),
            new PortalListColumnMeta.Option("zh-CN", "简体中文 (zh-CN)"),
            new PortalListColumnMeta.Option("zh_TW", "繁體中文 (zh_TW)"),
            new PortalListColumnMeta.Option("zh-TW", "繁體中文 (zh-TW)"));

    private final JdbcTemplate jdbcTemplate;
    private final RoleAccessComponent roleAccessComponent;
    private final ObjectMapper objectMapper;
    private final com.platform.common.fk.PrimaryKeyAllocationService primaryKeyAllocationService;
    private final com.portal.component.RelationTableComputedFieldRecalculator computedFieldRecalculator;
    private final RelationTableTemplateService templateService = new RelationTableTemplateService();

    @Override
    @Transactional(readOnly = true)
    public List<RelationTableDTO> getVisibleTables(String userId) {
        // Get user's business role IDs
        Set<String> userRoleIds = getUserRoleIds(userId);

        List<RelationTableDTO> allVisible;
        try {
            // Query portal-visible, deployed, enabled tables
            String sql = "SELECT t.id, t.table_name, t.display_name, t.description, t.status, "
                    + "t.enabled, t.portal_visible, t.current_version "
                    + "FROM rt_table_definitions t "
                    + "WHERE t.status = ? AND t.enabled = true AND t.portal_visible = true";
            allVisible = jdbcTemplate.query(sql, (rs, rowNum) ->
                    RelationTableDTO.builder()
                            .id(rs.getLong("id"))
                            .tableName(rs.getString("table_name"))
                            .displayName(rs.getString("display_name"))
                            .description(rs.getString("description"))
                            .status(RelationTableStatus.fromCode(rs.getString("status")))
                            .enabled(rs.getBoolean("enabled"))
                            .portalVisible(rs.getBoolean("portal_visible"))
                            .currentVersion(rs.getInt("current_version"))
                            .build(),
                    RelationTableStatus.DEPLOYED.getCode());
        } catch (Exception e) {
            log.warn("Failed to query rt_table_definitions (table may not exist yet): {}", e.getMessage());
            return Collections.emptyList();
        }

        if (userRoleIds.isEmpty()) {
            return Collections.emptyList();
        }

        // Resolve the permission level per table against the user's ACTIVE role (single role the user
        // logged in / switched to). Tables with no grant for the active role are filtered out.
        String activeRoleId = SecurityContextUtils.getCurrentActiveRoleId().orElse(null);
        List<RelationTableDTO> result = allVisible.stream()
                .map(table -> {
                    try {
                        String level = resolvePermissionLevelForRoles(table.getId(), activeRoleId, userRoleIds);
                        table.setPermissionLevel(level);
                        return table;
                    } catch (Exception e) {
                        log.warn("Failed to check access for table {}: {}", table.getId(), e.getMessage());
                        table.setPermissionLevel(null);
                        return table;
                    }
                })
                .filter(table -> table.getPermissionLevel() != null)
                .collect(Collectors.toCollection(ArrayList::new));

        // Always surface the system User table as a built-in READ-ONLY table for any authenticated user.
        result.add(0, systemUserTableDto());
        return result;
    }

    /** The built-in System User virtual table — always READ-ONLY, not backed by rt_table_access grants. */
    private RelationTableDTO systemUserTableDto() {
        return RelationTableDTO.builder()
                .id(SYSTEM_USER_TABLE_ID)
                .tableName(SYSTEM_USER_TABLE_NAME)
                .displayName("User")
                .description("System users (read-only)")
                .status(RelationTableStatus.DEPLOYED)
                .enabled(true)
                .portalVisible(true)
                .currentVersion(1)
                .permissionLevel(RelationPermissionLevel.READONLY)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public RelationTableDataPage queryTableData(Long tableId, String userId,
                                                RelationTableQueryRequest request) {
        // The built-in System User table is readable by any authenticated user (no rt_table_access grant).
        if (SYSTEM_USER_TABLE_ID.equals(tableId)) {
            return querySystemUserTableData(request);
        }

        long startedNanos = System.nanoTime();
        Set<String> userRoleIds = getUserRoleIds(userId);
        if (!hasAccess(tableId, userRoleIds)) {
            throw new AccessDeniedException("Access denied for this table");
        }
        if (!isDeployedRelationTable(tableId)) {
            throw new IllegalArgumentException("Table not found or not deployed: " + tableId);
        }

        List<RelationFieldDTO> fields = loadFields(tableId);
        List<PortalListColumnMeta> columns = RelationTableColumnSpec.columnsFor(fields);
        Map<String, PortalListColumnMeta> byField = columns.stream()
                .collect(Collectors.toMap(PortalListColumnMeta::field, c -> c, (a, b) -> a, LinkedHashMap::new));
        ListFilterSql filterSql = ListFilterSql.orderedById(byField, ListFilterSql.JSON_ROW);

        List<Object> params = new ArrayList<>();
        params.add(tableId);
        StringBuilder where = new StringBuilder(" WHERE table_id = ?");
        // Keep the trgm GIN guard so keyword search stays index-friendly (V214).
        where.append(buildJsonDataSearchClause(
                columns.stream().map(PortalListColumnMeta::field).toList(),
                request.search(),
                params));
        where.append(filterSql.whereClause(request.filters(), params));

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + DATA_ROWS_TABLE + where,
                Long.class, params.toArray());
        if (total == null) {
            total = 0L;
        }

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(request.size());
        pageParams.add(request.page() * request.size());
        String orderBy = filterSql.orderBy(request.sortField(), request.sortDirection());
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT data, status FROM " + DATA_ROWS_TABLE + where + orderBy + " LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    Map<String, Object> row = parseJsonRow(rs.getString("data"));
                    // Surface the row-level status column so the UI can toggle Active/Inactive.
                    row.put("status", rs.getString("status"));
                    return row;
                },
                pageParams.toArray());

        logSlowPageIfNeeded("relation-tables", tableId, request, total, startedNanos);
        return new RelationTableDataPage(columns, rows, request.page(), request.size(), total);
    }

    @Override
    @Transactional(readOnly = true)
    public String exportCsv(Long tableId, String userId, int maxRows) {
        // The built-in System User table is exportable by any authenticated user (no rt_table_access grant).
        if (SYSTEM_USER_TABLE_ID.equals(tableId)) {
            return exportSystemUserCsv(userId, maxRows);
        }

        Set<String> userRoleIds = getUserRoleIds(userId);
        if (!hasAccess(tableId, userRoleIds)) {
            log.warn("Export CSV access denied for user {} on table {}", userId, tableId);
            return "";
        }

        if (!isDeployedRelationTable(tableId)) {
            log.warn("Export CSV table not found: {}", tableId);
            return "";
        }

        List<RelationFieldDTO> fields = loadFields(tableId);
        if (fields.isEmpty()) {
            return "";
        }
        List<String> fieldNames = fields.stream().map(RelationFieldDTO::getFieldName).collect(Collectors.toList());
        Map<String, RelationDataType> typeByField = fields.stream()
                .filter(f -> f.getFieldName() != null)
                .collect(Collectors.toMap(RelationFieldDTO::getFieldName, RelationFieldDTO::getDataType, (a, b) -> a));

        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    "SELECT data FROM " + DATA_ROWS_TABLE + " WHERE table_id = ? ORDER BY id LIMIT ?",
                    (rs, rowNum) -> parseJsonRow(rs.getString("data")),
                    tableId, maxRows);

            StringBuilder csv = new StringBuilder();
            csv.append(String.join(",", fieldNames)).append("\n");
            for (Map<String, Object> row : rows) {
                csv.append(fieldNames.stream()
                        .map(f -> escapeCsvValue(RelationCsvValueFormatter.format(row.get(f), typeByField.get(f))))
                        .collect(Collectors.joining(","))
                ).append("\n");
            }
            return csv.toString();
        } catch (Exception e) {
            log.warn("Failed to export CSV for tableId {}: {}", tableId, e.getMessage());
            return "";
        }
    }

    private String exportSystemUserCsv(String userId, int maxRows) {
        // Match querySystemUserTableData: any authenticated user may export User; there is no
        // rt_table_access row for the virtual tableId, so hasAccess() would always deny.
        String tableName = sanitizeIdentifier(SYSTEM_USER_TABLE_NAME);
        List<String> fieldNames = SYSTEM_USER_FIELD_NAMES.stream().map(this::sanitizeIdentifier).toList();
        try {
            String columns = String.join(", ", fieldNames);
            String sql = "SELECT " + columns + " FROM " + tableName + " LIMIT ?";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, maxRows);
            StringBuilder csv = new StringBuilder();
            csv.append(String.join(",", SYSTEM_USER_FIELD_NAMES)).append("\n");
            for (Map<String, Object> row : rows) {
                csv.append(SYSTEM_USER_FIELD_NAMES.stream()
                        .map(f -> escapeCsvValue(row.get(f)))
                        .collect(Collectors.joining(","))
                ).append("\n");
            }
            return csv.toString();
        } catch (Exception e) {
            log.warn("Failed to export system user CSV: {}", e.getMessage());
            return "";
        }
    }

    private RelationTableDataPage querySystemUserTableData(RelationTableQueryRequest request) {
        long startedNanos = System.nanoTime();
        String tableName = sanitizeIdentifier(SYSTEM_USER_TABLE_NAME);
        List<PortalListColumnMeta> columnMeta = systemUserColumns();
        Map<String, PortalListColumnMeta> byField = columnMeta.stream()
                .collect(Collectors.toMap(PortalListColumnMeta::field, c -> c, (a, b) -> a, LinkedHashMap::new));
        ListFilterSql filterSql = ListFilterSql.orderedById(byField, ListFilterSql.PHYSICAL_COLUMN);

        String selectColumns = SYSTEM_USER_FIELD_NAMES.stream()
                .map(this::sanitizeIdentifier)
                .collect(Collectors.joining(", "));

        List<Object> params = new ArrayList<>();
        // ListFilterSql / keyword clauses all start with " AND …"; seed WHERE so a filter-only
        // request is not "FROM sys_users AND col ILIKE ?" (PSQL syntax error near AND).
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        where.append(buildSystemUserSearchClause(request.search(), params));
        where.append(filterSql.whereClause(request.filters(), params));

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + where,
                Long.class, params.toArray());
        if (total == null) {
            total = 0L;
        }

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(request.size());
        pageParams.add(request.page() * request.size());
        String orderBy = filterSql.orderBy(request.sortField(), request.sortDirection());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + selectColumns + " FROM " + tableName + where + orderBy + " LIMIT ? OFFSET ?",
                pageParams.toArray());
        logSlowPageIfNeeded("relation-tables-user", SYSTEM_USER_TABLE_ID, request, total, startedNanos);
        return new RelationTableDataPage(columnMeta, rows, request.page(), request.size(), total);
    }

    private void logSlowPageIfNeeded(
            String listKey, Long tableId, RelationTableQueryRequest request, long total, long startedNanos) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (elapsedMs <= SLOW_PAGE_WARN_MS) {
            return;
        }
        log.warn(
                "Slow relation-table page listKey={} tableId={} page={} size={} total={} elapsedMs={}",
                listKey, tableId, request.page(), request.size(), total, elapsedMs);
    }

    /** Built-in sys_users columns for the virtual Relation Table User view.
     * Closed codes ({@code status}, {@code language}) are ENUM with options and
     * {@code groupable = false} — see shared-list-components.md §6.5.2.
     * Free-text columns stay TEXT. Do not use {@link PortalListColumnMeta#withOptions},
     * which defaults groupable to true. */
    private List<PortalListColumnMeta> systemUserColumns() {
        List<PortalListColumnMeta> columns = new ArrayList<>();
        for (String field : SYSTEM_USER_FIELD_NAMES) {
            columns.add(systemUserColumn(field));
        }
        return columns;
    }

    private PortalListColumnMeta systemUserColumn(String field) {
        return switch (field) {
            case "status" -> enumColumn(field, field, SYSTEM_USER_STATUS_OPTIONS);
            case "language" -> enumColumn(field, field, SYSTEM_USER_LANGUAGE_OPTIONS);
            default -> PortalListColumnMeta.of(field, field, PortalListColumnMeta.Kind.TEXT);
        };
    }

    /** ENUM filterable/sortable but never groupable on the RT endpoint. */
    private static PortalListColumnMeta enumColumn(
            String field, String label, List<PortalListColumnMeta.Option> options) {
        return new PortalListColumnMeta(
                field,
                label,
                PortalListColumnMeta.Kind.ENUM,
                true,
                true,
                false,
                PortalListColumnMeta.operatorsFor(PortalListColumnMeta.Kind.ENUM),
                options);
    }


    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchForLookup(Long tableId, String keyword,
                                                      List<String> searchFields, String displayField,
                                                      String filterConditions,
                                                      int limit, int offset) {
        try {
            int safeLimit = normalizeLimit(limit);
            int safeOffset = Math.max(0, offset);
            if (SYSTEM_USER_TABLE_ID.equals(tableId)) {
                List<LookupFilterCondition> filters = parseLookupFilterConditions(
                        filterConditions, SYSTEM_USER_FIELD_NAMES);
                return searchSystemUsersForLookup(keyword, searchFields, filters, safeLimit, safeOffset);
            }

            if (!isDeployedRelationTable(tableId)) {
                return Collections.emptyList();
            }

            // No per-role rt_table_access guard here: a lookup is a developer-configured data
            // source on a form, and backfill requires full rows for anyone who can open that
            // form — a table-level grant adds no protection there while making every lookup
            // return "No Data" until Admin Center grants each role. rt_table_access still
            // gates the Relation Tables browse/export/write endpoints.
            List<String> allowedFields = getFieldNames(tableId);
            List<LookupFilterCondition> filters = parseLookupFilterConditions(filterConditions, allowedFields);
            Map<String, String> fieldDataTypes = getFieldDataTypes(tableId);
            List<String> predicates = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            params.add(tableId);

            for (LookupFilterCondition filter : filters) {
                if (!allowedFields.contains(filter.fieldName())) {
                    continue;
                }
                appendLookupFilterPredicate(
                        predicates,
                        params,
                        "data->>'" + sanitizeIdentifier(filter.fieldName()) + "'",
                        filter,
                        fieldDataTypes.get(filter.fieldName()));
            }

            if (keyword != null && !keyword.isBlank() && searchFields != null && !searchFields.isEmpty()) {
                List<String> sanitizedFields = searchFields.stream()
                        .filter(allowedFields::contains)
                        .map(this::sanitizeIdentifier)
                        .toList();
                if (!sanitizedFields.isEmpty()) {
                    String keywordClause = sanitizedFields.stream()
                            .map(f -> "data->>'" + f + "' ILIKE ?")
                            .collect(Collectors.joining(" OR "));
                    String likePattern = "%" + keyword + "%";
                    // index-accelerated broad guard (trgm GIN, V214) before exact per-field filter
                    predicates.add("data::text ILIKE ?");
                    params.add(likePattern);
                    predicates.add("(" + keywordClause + ")");
                    sanitizedFields.forEach(ignored -> params.add(likePattern));
                }
            }

            params.add(safeLimit);
            params.add(safeOffset);
            String sql = "SELECT data FROM " + DATA_ROWS_TABLE + " WHERE table_id = ?"
                    + (predicates.isEmpty() ? "" : " AND " + String.join(" AND ", predicates))
                    + " ORDER BY id LIMIT ? OFFSET ?";
            return jdbcTemplate.query(sql, (rs, rowNum) -> parseJsonRow(rs.getString("data")), params.toArray());
        } catch (Exception e) {
            log.warn("Failed to search for lookup in tableId {}: {}", tableId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLookupConfigs(Long formId) {
        try {
            String sql = "SELECT lc.component_id, lc.table_id, lc.search_fields, lc.display_field, lc.view_config_id "
                    + "FROM rt_lookup_configs lc WHERE lc.form_id = ?";
            List<Map<String, Object>> configs = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> lc = new java.util.HashMap<>();
                lc.put("componentId", rs.getString("component_id"));
                lc.put("tableId", rs.getLong("table_id"));
                lc.put("searchFields", rs.getString("search_fields"));
                lc.put("displayField", rs.getString("display_field"));
                lc.put("viewConfigId", rs.getObject("view_config_id"));
                return lc;
            }, formId);

            // Load view fields for each config that has a viewConfigId
            for (Map<String, Object> config : configs) {
                Object vcId = config.get("viewConfigId");
                // If no viewConfigId in lookup config, try to find view config by tableId
                if (vcId == null) {
                    Object tableId = config.get("tableId");
                    if (tableId != null) {
                        try {
                            vcId = jdbcTemplate.queryForObject(
                                "SELECT id FROM rt_view_configs WHERE table_id = ? ORDER BY id DESC LIMIT 1",
                                Long.class, ((Number) tableId).longValue());
                        } catch (Exception ignored) {}
                    }
                }
                if (vcId != null) {
                    try {
                        String vfSql = "SELECT field_name, display_label, column_width, sort_order, visible "
                                + "FROM rt_view_fields WHERE view_config_id = ? ORDER BY sort_order";
                        List<Map<String, Object>> viewFields = jdbcTemplate.query(vfSql, (rs, rowNum) -> {
                            Map<String, Object> vf = new java.util.HashMap<>();
                            vf.put("fieldName", rs.getString("field_name"));
                            vf.put("displayLabel", rs.getString("display_label"));
                            vf.put("columnWidth", rs.getObject("column_width"));
                            vf.put("sortOrder", rs.getInt("sort_order"));
                            vf.put("visible", rs.getBoolean("visible"));
                            return vf;
                        }, ((Number) vcId).longValue());
                        config.put("viewFields", viewFields);
                    } catch (Exception e) {
                        log.warn("Failed to load view fields for viewConfigId {}: {}", vcId, e.getMessage());
                        config.put("viewFields", Collections.emptyList());
                    }
                } else {
                    config.put("viewFields", Collections.emptyList());
                }
            }
            return configs;
        } catch (Exception e) {
            log.warn("Failed to load lookup configs for formId {}: {}", formId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getViewFieldsByTableId(Long tableId) {
        try {
            if (SYSTEM_USER_TABLE_ID.equals(tableId)) {
                return systemUserViewFields();
            }

            // rt_field_definitions 是字段「显示名」的权威来源（Admin Center 维护）。
            // rt_view_fields 的 display_label 多为自动生成（常等于 field_name），且同一表可能
            // 关联多个 view_config 造成字段重复，因此一律以字段定义的 display_name 为表头标签，
            // 仅按字段名去重并按 sort_order 排序。
            String sql = "SELECT field_name, display_name, sort_order "
                    + "FROM rt_field_definitions WHERE table_id = ? ORDER BY sort_order ASC";
            List<Map<String, Object>> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            jdbcTemplate.query(sql, rs -> {
                String fieldName = rs.getString("field_name");
                if (fieldName == null || !seen.add(fieldName)) return;
                String displayName = rs.getString("display_name");
                Map<String, Object> vf = new java.util.HashMap<>();
                vf.put("fieldName", fieldName);
                vf.put("displayLabel", displayName != null && !displayName.isBlank() ? displayName : fieldName);
                vf.put("columnWidth", null);
                vf.put("sortOrder", rs.getInt("sort_order"));
                vf.put("visible", true);
                result.add(vf);
            }, tableId);
            return result;
        } catch (Exception e) {
            log.warn("Failed to load view fields for tableId {}: {}", tableId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== Helper methods ====================

    private List<Map<String, Object>> searchSystemUsersForLookup(String keyword,
                                                                 List<String> searchFields,
                                                                 List<LookupFilterCondition> filters,
                                                                 int limit, int offset) {
        String columns = SYSTEM_USER_FIELD_NAMES.stream()
                .map(this::sanitizeIdentifier)
                .collect(Collectors.joining(", "));

        List<String> predicates = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        predicates.add("deleted = false");
        predicates.add("status = 'ACTIVE'");

        for (LookupFilterCondition filter : filters) {
            // sys_users lookup columns are all text-typed; no relation-table field metadata applies.
            appendLookupFilterPredicate(
                    predicates,
                    params,
                    sanitizeIdentifier(filter.fieldName()),
                    filter,
                    null);
        }

        if (keyword == null || keyword.isBlank()) {
            String sql = "SELECT " + columns + " FROM " + SYSTEM_USER_TABLE_NAME
                    + " WHERE " + String.join(" AND ", predicates)
                    + " ORDER BY username LIMIT ? OFFSET ?";
            params.add(limit);
            params.add(offset);
            return jdbcTemplate.queryForList(sql, params.toArray());
        }

        List<String> sanitizedFields = systemUserSearchFields(searchFields).stream()
                .map(this::sanitizeIdentifier)
                .toList();
        String whereClause = sanitizedFields.stream()
                .map(f -> f + " ILIKE ?")
                .collect(Collectors.joining(" OR "));

        String likePattern = "%" + keyword + "%";
        sanitizedFields.forEach(ignored -> params.add(likePattern));
        params.add(limit);
        params.add(offset);

        String sql = "SELECT " + columns + " FROM " + SYSTEM_USER_TABLE_NAME
                + " WHERE " + String.join(" AND ", predicates) + " AND ("
                + whereClause + ") ORDER BY username LIMIT ? OFFSET ?";
        return jdbcTemplate.queryForList(sql, params.toArray());
    }

    private List<LookupFilterCondition> parseLookupFilterConditions(String raw, List<String> allowedFields) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        try {
            Set<String> allowed = new HashSet<>(allowedFields);
            return objectMapper.readValue(raw, new TypeReference<List<LookupFilterCondition>>() {})
                    .stream()
                    .filter(condition -> condition.fieldName() != null
                            && condition.value() != null
                            && !condition.value().isBlank()
                            && allowed.contains(condition.fieldName()))
                    .toList();
        } catch (Exception e) {
            log.warn("Ignoring invalid lookup filter conditions: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private record LookupFilterCondition(String fieldName, String value, String matchType) {}

    private static final java.util.regex.Pattern NUMERIC_FILTER_VALUE =
            java.util.regex.Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private void appendLookupFilterPredicate(List<String> predicates,
                                             List<Object> params,
                                             String fieldExpression,
                                             LookupFilterCondition filter,
                                             String dataType) {
        String matchType = normalizeLookupFilterMatchType(filter.matchType());
        // Boolean fields only support exact matching regardless of the configured matchType.
        if (isBooleanFilterDataType(dataType) || "eq".equals(matchType)) {
            appendLookupEqPredicate(predicates, params, fieldExpression, filter.value(), dataType);
            return;
        }
        String pattern = switch (matchType) {
            case "startsWith" -> filter.value() + "%";
            case "endsWith" -> "%" + filter.value();
            default -> "%" + filter.value() + "%";
        };
        predicates.add("CAST(" + fieldExpression + " AS TEXT) ILIKE ?");
        params.add(pattern);
    }

    /** Exact match honouring the field's declared data type (numeric 123 == 123.00, boolean case-insensitive). */
    private void appendLookupEqPredicate(List<String> predicates,
                                         List<Object> params,
                                         String fieldExpression,
                                         String value,
                                         String dataType) {
        if (isBooleanFilterDataType(dataType)) {
            predicates.add("LOWER(CAST(" + fieldExpression + " AS TEXT)) = ?");
            params.add(normalizeBooleanFilterValue(value));
            return;
        }
        if (isNumericFilterDataType(dataType) && NUMERIC_FILTER_VALUE.matcher(value.trim()).matches()) {
            // CASE guard inside the same expression keeps rows holding non-numeric legacy
            // values from failing the cast (AND predicates have no evaluation-order guarantee).
            predicates.add("CAST(CASE WHEN CAST(" + fieldExpression + " AS TEXT) ~ '^-?[0-9]+(\\.[0-9]+)?$'"
                    + " THEN CAST(" + fieldExpression + " AS TEXT) END AS NUMERIC) = CAST(? AS NUMERIC)");
            params.add(value.trim());
            return;
        }
        predicates.add(fieldExpression + " = ?");
        params.add(value);
    }

    private boolean isBooleanFilterDataType(String dataType) {
        return "BOOLEAN".equalsIgnoreCase(dataType);
    }

    private boolean isNumericFilterDataType(String dataType) {
        if (dataType == null) return false;
        return switch (dataType.toUpperCase()) {
            case "INTEGER", "BIGINT", "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE" -> true;
            default -> false;
        };
    }

    private String normalizeBooleanFilterValue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "1", "yes" -> "true";
            case "0", "no" -> "false";
            default -> normalized;
        };
    }

    private String normalizeLookupFilterMatchType(String matchType) {
        if (matchType == null || matchType.isBlank()) {
            return "eq";
        }
        return switch (matchType.trim()) {
            case "contains", "startsWith", "endsWith" -> matchType.trim();
            default -> "eq";
        };
    }

    private List<String> systemUserSearchFields(List<String> searchFields) {
        if (searchFields == null || searchFields.isEmpty()) {
            return DEFAULT_SYSTEM_USER_SEARCH_FIELDS;
        }
        List<String> allowedFields = searchFields.stream()
                .filter(SYSTEM_USER_FIELD_NAMES::contains)
                .distinct()
                .toList();
        return allowedFields.isEmpty() ? DEFAULT_SYSTEM_USER_SEARCH_FIELDS : allowedFields;
    }

    private List<Map<String, Object>> systemUserViewFields() {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (int i = 0; i < SYSTEM_USER_FIELD_NAMES.size(); i++) {
            String fieldName = SYSTEM_USER_FIELD_NAMES.get(i);
            Map<String, Object> field = new HashMap<>();
            field.put("fieldName", fieldName);
            field.put("displayLabel", systemUserFieldLabel(fieldName));
            field.put("columnWidth", null);
            field.put("sortOrder", i + 1);
            field.put("visible", true);
            fields.add(field);
        }
        return fields;
    }

    private String systemUserFieldLabel(String fieldName) {
        return switch (fieldName) {
            case "id" -> "User ID";
            case "username" -> "Username";
            case "display_name" -> "Display Name";
            case "full_name" -> "Full Name";
            case "email" -> "Email";
            case "employee_id" -> "Employee ID";
            case "status" -> "Status";
            case "language" -> "Language";
            default -> fieldName;
        };
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) return 50;
        return Math.min(limit, 200);
    }

    private Set<String> getUserRoleIds(String userId) {
        // Roles assigned directly or via virtual groups
        List<Map<String, Object>> roles = roleAccessComponent.getUserBusinessRoles(userId);
        Set<String> roleIds = roles.stream()
                .map(r -> String.valueOf(r.getOrDefault("id", r.getOrDefault("roleId", ""))))
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toSet());

        // Also include roles the user holds through BU membership (sys_user_business_unit_roles).
        // The admin-center user-roles API may not include these when called without profileContext.
        try {
            List<String> buRoleIds = jdbcTemplate.queryForList(
                    "SELECT DISTINCT role_id FROM sys_user_business_unit_roles WHERE user_id = ?",
                    String.class, userId);
            roleIds.addAll(buRoleIds);
        } catch (Exception e) {
            log.warn("Failed to fetch BU role IDs for user {}: {}", userId, e.getMessage());
        }

        return roleIds;
    }

    private boolean hasAccess(Long tableId, Set<String> userRoleIds) {
        if (userRoleIds.isEmpty()) return false;
        String sql = "SELECT COUNT(*) FROM rt_table_access WHERE table_id = ? AND target_type = 'ROLE' AND target_id IN ("
                + userRoleIds.stream().map(id -> "?").collect(Collectors.joining(",")) + ")";
        List<Object> params = new ArrayList<>();
        params.add(tableId);
        params.addAll(userRoleIds);
        Long count = jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
        return count != null && count > 0;
    }

    /**
     * Resolve the permission level for the given table, preferring the single active role.
     * Falls back to the union of all roles for READ-ONLY visibility when no active role is present
     * (legacy token) — the fallback never grants write.
     *
     * @return READONLY | READ_WRITE | null (no access)
     */
    private String resolvePermissionLevelForRoles(Long tableId, String activeRoleId, Set<String> allRoleIds) {
        if (activeRoleId != null && !activeRoleId.isBlank()) {
            String level = queryPermissionLevel(tableId, Collections.singletonList(activeRoleId));
            if (level != null) {
                return level;
            }
            // active role has no grant; do not silently widen to other roles for write — but allow
            // read visibility if any other held role grants access.
            return hasAccess(tableId, allRoleIds) ? RelationPermissionLevel.READONLY : null;
        }
        // No active role in token: read-only visibility based on any held role.
        return hasAccess(tableId, allRoleIds) ? RelationPermissionLevel.READONLY : null;
    }

    /** Highest-privilege level across the given roles for the table, or null when none grant access. */
    private String queryPermissionLevel(Long tableId, Collection<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return null;
        String placeholders = roleIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT permission_level FROM rt_table_access WHERE table_id = ? "
                + "AND target_type = 'ROLE' AND target_id IN (" + placeholders + ")";
        List<Object> params = new ArrayList<>();
        params.add(tableId);
        params.addAll(roleIds);
        List<String> levels = jdbcTemplate.query(sql, (rs, n) -> rs.getString("permission_level"), params.toArray());
        if (levels.isEmpty()) return null;
        return levels.stream().anyMatch(RelationPermissionLevel::canWrite)
                ? RelationPermissionLevel.READ_WRITE : RelationPermissionLevel.READONLY;
    }

    @Override
    @Transactional(readOnly = true)
    public String resolvePermissionLevel(Long tableId, String userId) {
        // The built-in System User table is always read-only for any authenticated user.
        if (SYSTEM_USER_TABLE_ID.equals(tableId)) {
            return RelationPermissionLevel.READONLY;
        }
        String activeRoleId = SecurityContextUtils.getCurrentActiveRoleId().orElse(null);
        return resolvePermissionLevelForRoles(tableId, activeRoleId, getUserRoleIds(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RelationFieldDTO> getFieldDefinitions(Long tableId, String userId) {
        if (resolvePermissionLevel(tableId, userId) == null) {
            throw new AccessDeniedException("No access to this table");
        }
        return loadFields(tableId);
    }

    @Override
    @Transactional
    public List<String> allocatePrimaryKeys(Long tableId, String userId, String fieldName, Integer count) {
        requireWriteAccess(tableId, userId);
        RelationFieldDTO pk = loadFields(tableId).stream()
                .filter(f -> f.getFieldName().equals(fieldName) && Boolean.TRUE.equals(f.getIsPrimaryKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Primary key field not found: " + fieldName));
        com.platform.common.dto.PkGenerationConfig config = (pk.getPkGeneration() == null || pk.getPkGeneration().isEmpty())
                ? com.platform.common.dto.PkGenerationConfig.builder().strategy("uuid").build()
                : objectMapper.convertValue(pk.getPkGeneration(), com.platform.common.dto.PkGenerationConfig.class);
        int n = (count != null && count > 0) ? count : 1;
        // Relation-table data lives in rt_* tables; pin the counter table to avoid DW id collisions.
        return primaryKeyAllocationService.allocate(tableId, fieldName, config, n, "rt-" + tableId, "rt_pk_sequences");
    }

    /** Ensure the current user (active role) may write to the table; throws 403 otherwise. */
    private void requireWriteAccess(Long tableId, String userId) {
        String level = resolvePermissionLevel(tableId, userId);
        if (!RelationPermissionLevel.canWrite(level)) {
            throw new AccessDeniedException("Write access denied for this table");
        }
    }

    /** Load full field definitions for a deployed relation table (for validation / import / edit form). */
    private List<RelationFieldDTO> loadFields(Long tableId) {
        String sql = "SELECT field_name, data_type, length, precision_value, scale, nullable, "
                + "is_primary_key, default_value, display_name, sort_order, pk_generation_json::text AS pk_json, "
                + "lookup_config::text AS lookup_json, is_foreign_key, ref_table_id, "
                + "ref_primary_key_fields::text AS ref_pk_json, fk_display_mode, "
                + "is_computed, computed_field_json::text AS computed_json "
                + "FROM rt_field_definitions WHERE table_id = ? ORDER BY sort_order ASC";
        return jdbcTemplate.query(sql, (rs, n) -> RelationFieldDTO.builder()
                .fieldName(rs.getString("field_name"))
                .dataType(RelationDataType.fromCode(rs.getString("data_type")))
                .length((Integer) rs.getObject("length"))
                .precision((Integer) rs.getObject("precision_value"))
                .scale((Integer) rs.getObject("scale"))
                .nullable(rs.getObject("nullable") == null || rs.getBoolean("nullable"))
                .isPrimaryKey(rs.getBoolean("is_primary_key"))
                .defaultValue(rs.getString("default_value"))
                .displayName(rs.getString("display_name"))
                .sortOrder((Integer) rs.getObject("sort_order"))
                .pkGeneration(parseFieldConfigJson(rs.getString("pk_json")))
                .lookupConfig(parseFieldConfigJson(rs.getString("lookup_json")))
                .isForeignKey((Boolean) rs.getObject("is_foreign_key"))
                .refTableId((Long) rs.getObject("ref_table_id"))
                .refPrimaryKeyFields(parseStringList(rs.getString("ref_pk_json")))
                .fkDisplayMode(rs.getString("fk_display_mode"))
                .isComputed(rs.getBoolean("is_computed"))
                .computedField(parseFieldConfigJson(rs.getString("computed_json")))
                .build(), tableId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFieldConfigJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            // Corrupt column config must not fail the whole field load, but silently treating it as
            // "no config" would change PK generation / lookup / formula behavior — leave a trace.
            log.warn("Failed to parse field config JSON, treating as unset: json={}", json, e);
            return null;
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JSON string list config, treating as unset: json={}", json, e);
            return null;
        }
    }

    private String findPrimaryKeyField(List<RelationFieldDTO> fields) {
        return fields.stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsPrimaryKey()))
                .map(RelationFieldDTO::getFieldName)
                .findFirst().orElse(null);
    }

    /** True when a value is null or an empty/whitespace string (used to detect a missing PK). */
    private boolean isBlankValue(Object value) {
        return value == null || value.toString().trim().isEmpty();
    }

    @Override
    @Transactional
    public Map<String, Object> addData(Long tableId, String userId, Map<String, Object> data) {
        requireWriteAccess(tableId, userId);
        if (!isDeployedRelationTable(tableId)) {
            throw new IllegalArgumentException("Table not found or not deployed: " + tableId);
        }
        List<RelationFieldDTO> fields = loadFields(tableId);
        return insertRow(tableId, fields, data, userId);
    }

    /** Build + insert one row into rt_table_data_rows; shared by addData and importData. */
    private Map<String, Object> insertRow(Long tableId, List<RelationFieldDTO> fields,
                                          Map<String, Object> data, String userId) {
        Set<String> validFieldNames = fields.stream()
                .map(RelationFieldDTO::getFieldName).collect(Collectors.toSet());
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (validFieldNames.contains(e.getKey())) row.put(e.getKey(), e.getValue());
        }

        Timestamp now = Timestamp.from(Instant.now());
        if (validFieldNames.contains("created_at")) row.put("created_at", now.toString());
        if (validFieldNames.contains("created_by")) row.put("created_by", userId);
        if (validFieldNames.contains("updated_at")) row.put("updated_at", now.toString());
        if (validFieldNames.contains("updated_by")) row.put("updated_by", userId);

        // Auto-generate the primary key per its strategy when the caller did not supply one
        // (manual-strategy PKs must be provided). Guarantees a populated PK regardless of client.
        String pkField = findPrimaryKeyField(fields);
        boolean autoPk = pkField != null && fields.stream()
                .anyMatch(f -> pkField.equals(f.getFieldName()) && !RelationRowValidator.isManualPk(f));
        if (autoPk && isBlankValue(row.get(pkField))) {
            List<String> values = allocatePrimaryKeys(tableId, userId, pkField, 1);
            if (!values.isEmpty()) row.put(pkField, values.get(0));
        }

        Object pkVal = pkField != null ? row.get(pkField) : null;
        String rowId = pkVal != null ? String.valueOf(pkVal) : UUID.randomUUID().toString();

        // Runs last: formulas may read the audit fields and the freshly allocated primary key, and
        // whatever the client sent for a computed column is discarded in favour of this result.
        computedFieldRecalculator.recalculate(tableId, row);

        jdbcTemplate.update(
                "INSERT INTO " + DATA_ROWS_TABLE
                        + " (table_id, row_id, data, status, created_at, created_by, updated_at, updated_by)"
                        + " VALUES (?, ?, ?::jsonb, 'ACTIVE', ?, ?, ?, ?)",
                tableId, rowId, writeJson(row), now, userId, now, userId);
        return row;
    }

    @Override
    @Transactional
    public Map<String, Object> updateData(Long tableId, String userId, String rowId, Map<String, Object> data) {
        requireWriteAccess(tableId, userId);
        List<RelationFieldDTO> fields = loadFields(tableId);
        String pkField = findPrimaryKeyField(fields);
        Set<String> validFieldNames = fields.stream()
                .map(RelationFieldDTO::getFieldName).collect(Collectors.toSet());

        Map<String, Object> oldData = loadRow(tableId, rowId);
        if (oldData == null) {
            throw new IllegalArgumentException("Row not found: " + rowId);
        }
        Map<String, Object> merged = new LinkedHashMap<>(oldData);
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (validFieldNames.contains(e.getKey()) && !e.getKey().equals(pkField)) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        Timestamp now = Timestamp.from(Instant.now());
        if (validFieldNames.contains("updated_at")) merged.put("updated_at", now.toString());
        if (validFieldNames.contains("updated_by")) merged.put("updated_by", userId);

        // Recomputed from the merged row, not the submitted delta: a formula may read a column this
        // request never touched.
        computedFieldRecalculator.recalculate(tableId, merged);

        jdbcTemplate.update(
                "UPDATE " + DATA_ROWS_TABLE + " SET data = ?::jsonb, updated_at = ?, updated_by = ? "
                        + "WHERE table_id = ? AND row_id = ?",
                writeJson(merged), now, userId, tableId, rowId);
        return merged;
    }

    @Override
    @Transactional
    public Map<String, Object> changeStatus(Long tableId, String userId, String rowId, String status) {
        requireWriteAccess(tableId, userId);
        String normalized = "INACTIVE".equalsIgnoreCase(status) ? "INACTIVE" : "ACTIVE";
        Map<String, Object> oldData = loadRow(tableId, rowId);
        if (oldData == null) {
            throw new IllegalArgumentException("Row not found: " + rowId);
        }
        Map<String, Object> merged = new LinkedHashMap<>(oldData);
        if (merged.containsKey("status")) merged.put("status", normalized);
        Timestamp now = Timestamp.from(Instant.now());
        merged.put("updated_at", now.toString());
        merged.put("updated_by", userId);
        jdbcTemplate.update(
                "UPDATE " + DATA_ROWS_TABLE + " SET status = ?, data = ?::jsonb, updated_at = ?, updated_by = ? "
                        + "WHERE table_id = ? AND row_id = ?",
                normalized, writeJson(merged), now, userId, tableId, rowId);
        return merged;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateTemplate(Long tableId, String userId, String format) {
        // Any role with access (read or write) may download the template; readers can inspect columns.
        if (resolvePermissionLevel(tableId, userId) == null) {
            throw new AccessDeniedException("No access to this table");
        }
        return templateService.generateTemplate(loadFields(tableId), format);
    }

    @Override
    @Transactional
    public Map<String, Object> importData(Long tableId, String userId, byte[] fileBytes, String format, boolean dryRun) {
        requireWriteAccess(tableId, userId);
        List<RelationFieldDTO> fields = loadFields(tableId);
        List<Map<String, Object>> rawRows = templateService.parseImport(fileBytes, format);
        if (rawRows.size() > com.platform.common.relationtable.RelationTableTemplateService.MAX_IMPORT_ROWS) {
            throw new IllegalArgumentException(
                    "Too many rows: " + rawRows.size() + ". A single import is limited to "
                            + com.platform.common.relationtable.RelationTableTemplateService.MAX_IMPORT_ROWS + " rows.");
        }
        List<RowValidationResult> results = RelationRowValidator.validateRows(rawRows, fields);

        List<Map<String, Object>> errors = new ArrayList<>();
        for (RowValidationResult r : results) {
            if (!r.isValid()) {
                r.getErrors().forEach(err -> errors.add(Map.of(
                        "row", err.getRow(),
                        "field", err.getField() == null ? "" : err.getField(),
                        "message", err.getMessage())));
            }
        }
        long validCount = results.stream().filter(RowValidationResult::isValid).count();

        // Dry run: validate only, do not write. Used by the two-step "preview then confirm" flow.
        if (dryRun) {
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("dryRun", true);
            preview.put("validCount", validCount);
            preview.put("failed", results.size() - (int) validCount);
            preview.put("errors", errors);
            return preview;
        }

        int inserted = 0;
        for (RowValidationResult r : results) {
            if (r.isValid()) {
                // insertRow auto-generates the PK per strategy when a row carries none.
                insertRow(tableId, fields, new LinkedHashMap<>(r.getValues()), userId);
                inserted++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inserted", inserted);
        summary.put("failed", results.size() - inserted);
        summary.put("errors", errors);
        return summary;
    }

    private Map<String, Object> loadRow(Long tableId, String rowId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT data FROM " + DATA_ROWS_TABLE + " WHERE table_id = ? AND row_id = ?",
                (rs, n) -> parseJsonRow(rs.getString("data")), tableId, rowId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String writeJson(Map<String, Object> row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize row JSON: " + e.getMessage(), e);
        }
    }

    private boolean isDeployedRelationTable(Long tableId) {
        if (tableId == null || SYSTEM_USER_TABLE_ID.equals(tableId)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rt_table_definitions WHERE id = ? AND status = ? AND enabled = true",
                Integer.class, tableId, RelationTableStatus.DEPLOYED.getCode());
        return count != null && count > 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonRow(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse relation table row JSON: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private List<String> getFieldNames(Long tableId) {
        if (SYSTEM_USER_TABLE_ID.equals(tableId)) {
            return SYSTEM_USER_FIELD_NAMES;
        }
        String sql = "SELECT field_name FROM rt_field_definitions WHERE table_id = ? ORDER BY sort_order ASC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("field_name"), tableId);
    }

    /** field_name -> declared data type (e.g. INTEGER/BOOLEAN/VARCHAR) for lookup filter typing. */
    private Map<String, String> getFieldDataTypes(Long tableId) {
        Map<String, String> types = new java.util.HashMap<>();
        jdbcTemplate.query(
                "SELECT field_name, data_type FROM rt_field_definitions WHERE table_id = ?",
                rs -> { types.put(rs.getString("field_name"), rs.getString("data_type")); },
                tableId);
        return types;
    }

    /**
     * @return SQL fragment like {@code  AND data::text ILIKE ? AND (data->>'f1' ILIKE ? OR ...)}
     *         or empty when search is blank
     *
     * <p>The leading {@code data::text ILIKE ?} guard lets the pg_trgm GIN index
     * {@code idx_rt_data_rows_data_trgm} serve the leading-wildcard search; the per-field clause
     * then filters whole-row false positives, keeping the result identical. See migration V214.
     */
    private String buildJsonDataSearchClause(List<String> fieldNames, String search, List<Object> outParams) {
        if (search == null || search.isBlank() || fieldNames == null || fieldNames.isEmpty()) {
            return "";
        }
        List<String> sanitizedFields = fieldNames.stream()
                .map(this::sanitizeIdentifier)
                .toList();
        if (sanitizedFields.isEmpty()) {
            return "";
        }
        String keywordClause = sanitizedFields.stream()
                .map(f -> "data->>'" + f + "' ILIKE ?")
                .collect(Collectors.joining(" OR "));
        String likePattern = "%" + search + "%";
        outParams.add(likePattern); // index-accelerated broad guard (trgm GIN)
        sanitizedFields.forEach(ignored -> outParams.add(likePattern));
        return " AND data::text ILIKE ? AND (" + keywordClause + ")";
    }

    private String buildSystemUserSearchClause(String search, List<Object> outParams) {
        if (search == null || search.isBlank()) {
            return "";
        }
        // Include `id` so a lookup drill-down filtered by the referenced user id resolves the row.
        List<String> searchableFields = new ArrayList<>();
        searchableFields.add("id");
        searchableFields.addAll(DEFAULT_SYSTEM_USER_SEARCH_FIELDS);
        List<String> sanitizedFields = searchableFields.stream()
                .map(this::sanitizeIdentifier)
                .toList();
        String keywordClause = sanitizedFields.stream()
                .map(f -> f + " ILIKE ?")
                .collect(Collectors.joining(" OR "));
        String likePattern = "%" + search + "%";
        sanitizedFields.forEach(ignored -> outParams.add(likePattern));
        return " AND (" + keywordClause + ")";
    }

    private String sanitizeIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
        return identifier;
    }

    private String escapeCsvValue(Object value) {
        if (value == null) return "";
        String str = value.toString();
        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }
}
