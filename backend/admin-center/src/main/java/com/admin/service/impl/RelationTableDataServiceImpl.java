package com.admin.service.impl;

import com.admin.component.RelationTableFunctionUnitResolver;
import com.admin.dto.response.RelationTableResponse;
import com.admin.entity.FunctionUnit;
import com.admin.entity.RelationFieldDefinition;
import com.admin.entity.RelationTableDefinition;
import com.admin.entity.RelationTableFunctionUnit;
import com.admin.entity.RelationTableVersion;
import com.admin.exception.RelationTableNotFoundException;
import com.admin.repository.RelationTableDefinitionRepository;
import com.admin.repository.RelationTableVersionRepository;
import com.admin.service.RelationTableAccessService;
import com.admin.service.RelationTableAuditService;
import com.admin.service.RelationTableDataService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.dto.RelationFieldDTO;
import com.platform.common.dto.RelationTableDataRowDTO;
import com.platform.common.enums.RelationDataType;
import com.platform.common.enums.RelationPermissionLevel;
import com.platform.common.enums.RelationTableStatus;
import com.platform.common.jdbc.SqlIdentifiers;
import com.platform.common.relationtable.RelationCsvValueFormatter;
import com.platform.common.relationtable.RelationRowValidator;
import com.platform.common.relationtable.RelationTableTemplateService;
import com.platform.common.relationtable.RowValidationResult;
import com.platform.security.util.SecurityContextUtils;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Relation Table row data management; rows are stored in rt_table_data_rows (JSONB), not per-table physical tables.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationTableDataServiceImpl implements RelationTableDataService {

    private static final String DATA_ROWS_TABLE = "rt_table_data_rows";

    private final RelationTableDefinitionRepository tableDefinitionRepository;
    private final RelationTableVersionRepository versionRepository;
    private final RelationTableFunctionUnitResolver relationTableFunctionUnitResolver;
    private final RelationTableAuditService auditService;
    private final RelationTableAccessService accessService;
    private final com.admin.service.RelationTablePrimaryKeyAllocationService primaryKeyAllocationService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RelationTableTemplateService templateService = new RelationTableTemplateService();

    /**
     * Resolve the current admin's permission level on a table for the Table Data page.
     * Admin Center is default-open: super admins and users with no explicit grant for their active
     * role are treated as READ_WRITE. Only an explicit READONLY grant restricts to view+export.
     */
    private String resolveCurrentLevel(Long tableId) {
        if (SecurityContextUtils.isSuperAdmin()) {
            return RelationPermissionLevel.READ_WRITE;
        }
        String activeRoleId = SecurityContextUtils.getCurrentActiveRoleId().orElse(null);
        java.util.Collection<String> roles = activeRoleId != null
                ? java.util.List.of(activeRoleId)
                : SecurityContextUtils.getCurrentUser()
                        .map(u -> (java.util.Collection<String>) u.getRoles())
                        .orElse(java.util.Collections.emptyList());
        String level = accessService.resolvePermissionLevel(tableId, roles);
        // No explicit grant in admin context -> default-open (READ_WRITE).
        return level == null ? RelationPermissionLevel.READ_WRITE : level;
    }

    /** Throw 403 when the current admin holds an explicit READONLY grant on the table. */
    private void requireWriteAccess(Long tableId) {
        if (!RelationPermissionLevel.canWrite(resolveCurrentLevel(tableId))) {
            throw new AccessDeniedException("Write access denied: role is read-only for this table");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<RelationTableResponse> getDeployedTables() {
        List<RelationTableDefinition> tables = tableDefinitionRepository.findByStatusInAndEnabledTrue(
                List.of(RelationTableStatus.DEPLOYED, RelationTableStatus.UPDATED, RelationTableStatus.ROLLBACK));
        Map<Long, List<RelationTableFunctionUnit>> linksByTable = relationTableFunctionUnitResolver.loadLinksByTable(
                tables.stream().map(RelationTableDefinition::getId).toList());
        Map<String, FunctionUnit> functionUnitsById = relationTableFunctionUnitResolver.loadFunctionUnitsById(linksByTable);
        return tables.stream()
                .map(this::toDeployedTableResponse)
                .peek(r -> r.setPermissionLevel(resolveCurrentLevel(r.getId())))
                .peek(r -> r.applyFunctionUnits(relationTableFunctionUnitResolver.resolve(linksByTable.get(r.getId()), functionUnitsById)))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.admin.dto.response.FunctionUnitTableGroupResponse> getDeployedTableFunctionUnitGroups() {
        List<RelationTableDefinition> tables = tableDefinitionRepository.findByStatusInAndEnabledTrue(
                List.of(RelationTableStatus.DEPLOYED, RelationTableStatus.UPDATED, RelationTableStatus.ROLLBACK));
        Map<Long, List<RelationTableFunctionUnit>> linksByTable = relationTableFunctionUnitResolver.loadLinksByTable(
                tables.stream().map(RelationTableDefinition::getId).toList());
        Map<String, FunctionUnit> functionUnitsById = relationTableFunctionUnitResolver.loadFunctionUnitsById(linksByTable);
        // A table linked to multiple Function Units counts toward each of their groups.
        return relationTableFunctionUnitResolver.groupByFunctionUnitCode(linksByTable, functionUnitsById);
    }

    /**
     * Maps table definition entity to response DTO for Table Data.
     * Field list and table display name come from the latest deployed version snapshot / deployed_display_name,
     * not draft rt_field_definitions or display_name while status is UPDATED/ROLLBACK.
     */
    private RelationTableResponse toDeployedTableResponse(RelationTableDefinition entity) {
        String deployedDisplayName = resolveDeployedDisplayName(entity);

        if (entity.getCurrentVersion() != null && entity.getCurrentVersion() > 0) {
            Optional<RelationTableVersion> latestVersion =
                    versionRepository.findLatestVersion(entity.getId());
            if (latestVersion.isPresent()) {
                try {
                    List<RelationFieldDTO> snapshotFields =
                            parseSnapshotData(latestVersion.get().getSnapshotData());
                    if (!snapshotFields.isEmpty()) {
                        return buildResponseWithSnapshotFields(entity, snapshotFields, deployedDisplayName);
                    }
                } catch (Exception e) {
                    // FALLBACK(external): the snapshot mirrors the DEPLOYED schema; falling back to
                    // live JPA field definitions keeps the page working but may show design-time
                    // columns not yet deployed (accuracy degradation, not data corruption).
                    log.warn("Cannot parse snapshot for table {}, falling back to JPA fields",
                            entity.getId());
                }
            }
        }

        RelationTableResponse response = RelationTableResponse.fromEntity(entity);
        response.setDisplayName(deployedDisplayName);
        return response;
    }

    private String resolveDeployedDisplayName(RelationTableDefinition entity) {
        if (entity.getStatus() == RelationTableStatus.UPDATED
                || entity.getStatus() == RelationTableStatus.ROLLBACK) {
            if (entity.getDeployedDisplayName() != null
                    && !entity.getDeployedDisplayName().isBlank()) {
                return entity.getDeployedDisplayName();
            }
            // Legacy rows: avoid exposing draft display_name before next deploy
            return entity.getTableName();
        }
        return entity.getDisplayName();
    }

    private RelationTableResponse buildResponseWithSnapshotFields(
            RelationTableDefinition entity, List<RelationFieldDTO> snapshotFields, String displayName) {
        List<RelationTableResponse.FieldDefinitionResponse> fields = snapshotFields.stream()
                .map(f -> RelationTableResponse.FieldDefinitionResponse.builder()
                        .id(f.getId())
                        .fieldName(f.getFieldName())
                        .dataType(f.getDataType())
                        .length(f.getLength())
                        .precision(f.getPrecision())
                        .scale(f.getScale())
                        .nullable(f.getNullable())
                        .isPrimaryKey(f.getIsPrimaryKey())
                        .defaultValue(f.getDefaultValue())
                        .displayName(f.getDisplayName())
                        .sortOrder(f.getSortOrder())
                        .pkGeneration(f.getPkGeneration())
                        .lookupConfig(f.getLookupConfig())
                        .isForeignKey(f.getIsForeignKey())
                        .refTableId(f.getRefTableId())
                        .refPrimaryKeyFields(f.getRefPrimaryKeyFields())
                        .fkDisplayMode(f.getFkDisplayMode())
                        .build())
                .collect(Collectors.toList());
        return RelationTableResponse.builder()
                .id(entity.getId())
                .tableName(entity.getTableName())
                .displayName(displayName)
                .description(entity.getDescription())
                .status(entity.getStatus())
                .enabled(entity.getEnabled())
                .portalVisible(entity.getPortalVisible())
                .currentVersion(entity.getCurrentVersion())
                .fieldDefinitions(fields)
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RelationTableDataRowDTO> queryData(Long tableId, String search, Pageable pageable) {
        RelationTableDefinition tableDef = getDeployedTableDefinition(tableId);
        List<RelationFieldDTO> fields = getDeployedFields(tableDef);

        List<Object> params = new ArrayList<>();
        params.add(tableId);
        String searchClause = buildJsonSearchWhereClause(fields, search, params);

        String countSql = "SELECT COUNT(*) FROM " + DATA_ROWS_TABLE + " WHERE table_id = ?" + searchClause;
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        if (total == null) total = 0L;

        List<Object> dataParams = new ArrayList<>(params);
        dataParams.add(pageable.getPageSize());
        dataParams.add(pageable.getOffset());
        String dataSql = "SELECT row_id, data, status FROM " + DATA_ROWS_TABLE
                + " WHERE table_id = ?" + searchClause
                + " ORDER BY id LIMIT ? OFFSET ?";

        List<RelationTableDataRowDTO> dtoList = jdbcTemplate.query(dataSql, (rs, rowNum) -> {
                    Map<String, Object> data = parseRowData(rs.getString("data"));
                    // Surface the row-level status column so the UI can toggle Active/Inactive.
                    data.put("status", rs.getString("status"));
                    return RelationTableDataRowDTO.builder()
                            .rowId(rs.getString("row_id"))
                            .tableId(tableId)
                            .data(data)
                            .build();
                },
                dataParams.toArray());

        return new PageImpl<>(dtoList, pageable, total);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RelationFieldDTO> loadDeployedFieldsForQuery(Long tableId) {
        return getDeployedFields(getDeployedTableDefinition(tableId));
    }

    private static final int LOOKUP_MAX_LIMIT = 200;

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchForLookup(Long tableId, String keyword,
                                                     List<String> searchFields, String displayField,
                                                     String filterConditions, int limit, int offset) {
        try {
            int safeLimit = Math.min(Math.max(1, limit), LOOKUP_MAX_LIMIT);
            int safeOffset = Math.max(0, offset);

            RelationTableDefinition tableDef = getDeployedTableDefinition(tableId);
            List<RelationFieldDTO> fields = getDeployedFields(tableDef);
            Set<String> allowedFields = fields.stream()
                    .map(RelationFieldDTO::getFieldName)
                    .filter(this::isSafeFieldName)
                    .collect(Collectors.toSet());
            Map<String, String> fieldDataTypes = fields.stream()
                    .filter(f -> f.getDataType() != null)
                    .collect(Collectors.toMap(RelationFieldDTO::getFieldName, f -> f.getDataType().name(), (a, b) -> a));

            List<String> predicates = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            params.add(tableId);

            for (LookupFilterCondition filter : parseLookupFilterConditions(filterConditions, allowedFields)) {
                appendLookupFilterPredicate(predicates, params,
                        "data->>'" + filter.fieldName() + "'", filter, fieldDataTypes.get(filter.fieldName()));
            }

            if (keyword != null && !keyword.isBlank() && searchFields != null && !searchFields.isEmpty()) {
                List<String> sanitized = searchFields.stream()
                        .filter(allowedFields::contains)
                        .collect(Collectors.toList());
                if (!sanitized.isEmpty()) {
                    String keywordClause = sanitized.stream()
                            .map(f -> "data->>'" + f + "' ILIKE ?")
                            .collect(Collectors.joining(" OR "));
                    String likePattern = "%" + keyword + "%";
                    predicates.add("data::text ILIKE ?");
                    params.add(likePattern);
                    predicates.add("(" + keywordClause + ")");
                    sanitized.forEach(ignored -> params.add(likePattern));
                }
            }

            params.add(safeLimit);
            params.add(safeOffset);
            String sql = "SELECT data FROM " + DATA_ROWS_TABLE + " WHERE table_id = ?"
                    + (predicates.isEmpty() ? "" : " AND " + String.join(" AND ", predicates))
                    + " ORDER BY id LIMIT ? OFFSET ?";
            return jdbcTemplate.query(sql, (rs, rowNum) -> parseRowData(rs.getString("data")), params.toArray());
        } catch (Exception e) {
            log.warn("Failed to search for lookup in tableId {}: {}", tableId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getViewFields(Long tableId) {
        try {
            RelationTableDefinition tableDef = getDeployedTableDefinition(tableId);
            List<RelationFieldDTO> fields = getDeployedFields(tableDef);
            List<Map<String, Object>> out = new ArrayList<>();
            int order = 0;
            for (RelationFieldDTO f : fields) {
                if (SYSTEM_ROW_FIELDS.contains(f.getFieldName())) continue;
                Map<String, Object> vf = new LinkedHashMap<>();
                vf.put("fieldName", f.getFieldName());
                vf.put("displayLabel", f.getDisplayName() != null && !f.getDisplayName().isBlank()
                        ? f.getDisplayName() : f.getFieldName());
                vf.put("sortOrder", order++);
                vf.put("visible", true);
                out.add(vf);
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to load view fields for tableId {}: {}", tableId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static final Set<String> SYSTEM_ROW_FIELDS =
            Set.of("created_at", "created_by", "updated_at", "updated_by", "status");

    private record LookupFilterCondition(String fieldName, String value, String matchType) {}

    private static final java.util.regex.Pattern NUMERIC_FILTER_VALUE =
            java.util.regex.Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private List<LookupFilterCondition> parseLookupFilterConditions(String raw, Set<String> allowedFields) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<LookupFilterCondition>>() {})
                    .stream()
                    .filter(c -> c.fieldName() != null && c.value() != null && !c.value().isBlank()
                            && allowedFields.contains(c.fieldName()))
                    .toList();
        } catch (Exception e) {
            log.warn("Ignoring invalid lookup filter conditions: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void appendLookupFilterPredicate(List<String> predicates, List<Object> params,
                                             String fieldExpression, LookupFilterCondition filter, String dataType) {
        String matchType = filter.matchType() == null ? "eq" : filter.matchType();
        boolean isBoolean = "BOOLEAN".equalsIgnoreCase(dataType);
        if (isBoolean || "eq".equals(matchType)) {
            if (isBoolean) {
                predicates.add("LOWER(CAST(" + fieldExpression + " AS TEXT)) = ?");
                params.add(normalizeBooleanFilterValue(filter.value()));
                return;
            }
            if (isNumericFilterDataType(dataType) && NUMERIC_FILTER_VALUE.matcher(filter.value().trim()).matches()) {
                predicates.add("CAST(CASE WHEN CAST(" + fieldExpression + " AS TEXT) ~ '^-?[0-9]+(\\.[0-9]+)?$'"
                        + " THEN CAST(" + fieldExpression + " AS TEXT) END AS NUMERIC) = CAST(? AS NUMERIC)");
                params.add(filter.value().trim());
                return;
            }
            predicates.add(fieldExpression + " = ?");
            params.add(filter.value());
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

    @Override
    @Transactional
    public RelationTableDataRowDTO addData(Long tableId, Map<String, Object> data) {
        requireWriteAccess(tableId);
        RelationTableDefinition tableDef = getDeployedTableDefinition(tableId);
        List<RelationFieldDTO> fields = getDeployedFields(tableDef);
        String tableName = tableDef.getTableName();
        String pkField = findPrimaryKeyField(fields);

        Set<String> validFieldNames = fields.stream()
                .map(RelationFieldDTO::getFieldName)
                .collect(Collectors.toSet());
        Map<String, Object> filteredData = data.entrySet().stream()
                .filter(e -> validFieldNames.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new));

        // Auto-generate the primary key per its generation strategy when the caller did not supply one
        // (manual-strategy PKs must be provided by the caller). This guarantees a PK regardless of how
        // the request was made (UI, import, or direct API), instead of relying on the client to allocate.
        boolean autoPk = pkField != null && fields.stream()
                .anyMatch(f -> pkField.equals(f.getFieldName()) && !RelationRowValidator.isManualPk(f));
        if (autoPk && isBlank(filteredData.get(pkField))) {
            List<String> values = primaryKeyAllocationService
                    .allocate(tableId, pkField, 1, "rt-" + tableId).getValues();
            if (values != null && !values.isEmpty()) {
                filteredData.put(pkField, values.get(0));
            }
        }

        String currentUser = SecurityContextUtils.getCurrentUsername().orElse("system");
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        if (validFieldNames.contains("created_at")) filteredData.put("created_at", now);
        if (validFieldNames.contains("created_by")) filteredData.put("created_by", currentUser);
        if (validFieldNames.contains("updated_at")) filteredData.put("updated_at", now);
        if (validFieldNames.contains("updated_by")) filteredData.put("updated_by", currentUser);

        String rowId = resolveRowId(pkField, filteredData);
        String json = writeRowJson(filteredData);

        jdbcTemplate.update(
                "INSERT INTO " + DATA_ROWS_TABLE
                        + " (table_id, row_id, data, status, created_at, created_by, updated_at, updated_by)"
                        + " VALUES (?, ?, ?::jsonb, 'ACTIVE', ?, ?, ?, ?)",
                tableId, rowId, json, now, currentUser, now, currentUser);

        auditService.logAdd(tableId, tableName, rowId, filteredData);

        return RelationTableDataRowDTO.builder()
                .rowId(rowId)
                .tableId(tableId)
                .data(filteredData)
                .build();
    }

    @Override
    @Transactional
    public RelationTableDataRowDTO updateData(Long tableId, String rowId, Map<String, Object> data) {
        requireWriteAccess(tableId);
        RelationTableDefinition tableDef = getDeployedTableDefinition(tableId);
        List<RelationFieldDTO> fields = getDeployedFields(tableDef);
        String tableName = tableDef.getTableName();
        String pkField = findPrimaryKeyField(fields);

        Map<String, Object> oldData = getRowData(tableId, rowId);
        if (oldData == null) {
            throw new RelationTableNotFoundException("Row not found: " + rowId);
        }

        Set<String> validFieldNames = fields.stream()
                .map(RelationFieldDTO::getFieldName)
                .collect(Collectors.toSet());
        Map<String, Object> filteredData = data.entrySet().stream()
                .filter(e -> validFieldNames.contains(e.getKey()))
                .filter(e -> !e.getKey().equals(pkField))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        String currentUser = SecurityContextUtils.getCurrentUsername().orElse("system");
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        if (validFieldNames.contains("updated_at")) filteredData.put("updated_at", now);
        if (validFieldNames.contains("updated_by")) filteredData.put("updated_by", currentUser);

        if (filteredData.isEmpty()) {
            return RelationTableDataRowDTO.builder()
                    .rowId(rowId)
                    .tableId(tableId)
                    .data(oldData)
                    .build();
        }

        Map<String, Object> newData = new LinkedHashMap<>(oldData);
        newData.putAll(filteredData);
        jdbcTemplate.update(
                "UPDATE " + DATA_ROWS_TABLE
                        + " SET data = ?::jsonb, updated_at = ?, updated_by = ?"
                        + " WHERE table_id = ? AND row_id = ?",
                writeRowJson(newData), now, currentUser, tableId, rowId);

        auditService.logUpdate(tableId, tableName, rowId, oldData, newData);

        return RelationTableDataRowDTO.builder()
                .rowId(rowId)
                .tableId(tableId)
                .data(newData)
                .build();
    }

    @Override
    @Transactional
    public void deleteData(Long tableId, String rowId) {
        requireWriteAccess(tableId);
        RelationTableDefinition tableDef = getDeployedTableDefinition(tableId);
        String tableName = tableDef.getTableName();

        Map<String, Object> oldData = getRowData(tableId, rowId);
        if (oldData == null) {
            throw new RelationTableNotFoundException("Row not found: " + rowId);
        }

        jdbcTemplate.update(
                "DELETE FROM " + DATA_ROWS_TABLE + " WHERE table_id = ? AND row_id = ?",
                tableId, rowId);

        auditService.logDelete(tableId, tableName, rowId, oldData);
    }

    @Override
    @Transactional
    public RelationTableDataRowDTO changeStatus(Long tableId, String rowId, String status) {
        requireWriteAccess(tableId);
        RelationTableDefinition tableDef = getDeployedTableDefinition(tableId);
        List<RelationFieldDTO> fields = getDeployedFields(tableDef);
        String tableName = tableDef.getTableName();

        boolean hasStatusField = fields.stream()
                .anyMatch(f -> "status".equalsIgnoreCase(f.getFieldName()));

        Map<String, Object> oldData = getRowData(tableId, rowId);
        if (oldData == null) {
            throw new RelationTableNotFoundException("Row not found: " + rowId);
        }

        String oldStatus = oldData.get("status") != null ? oldData.get("status").toString() : "Unknown";
        Map<String, Object> newData = new LinkedHashMap<>(oldData);
        if (hasStatusField) {
            newData.put("status", status);
        }

        String currentUser = SecurityContextUtils.getCurrentUsername().orElse("system");
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "UPDATE " + DATA_ROWS_TABLE
                        + " SET status = ?, data = ?::jsonb, updated_at = ?, updated_by = ?"
                        + " WHERE table_id = ? AND row_id = ?",
                status, writeRowJson(newData), now, currentUser, tableId, rowId);

        auditService.logStatusChange(tableId, tableName, rowId, oldStatus, status);

        return RelationTableDataRowDTO.builder()
                .rowId(rowId)
                .tableId(tableId)
                .data(newData)
                .build();
    }

    // ==================== Helpers ====================

    /**
     * Exports table rows as CSV.
     */
    @Override
    @Transactional(readOnly = true)
    public String exportCsv(Long tableId, int maxRows) {
        RelationTableDefinition tableDef = getDeployedTableDefinition(tableId);
        List<RelationFieldDTO> fields = getDeployedFields(tableDef);

        List<String> columnNames = fields.stream()
                .map(RelationFieldDTO::getFieldName)
                .collect(Collectors.toList());
        Map<String, RelationDataType> typeByField = fields.stream()
                .filter(f -> f.getFieldName() != null)
                .collect(Collectors.toMap(RelationFieldDTO::getFieldName, RelationFieldDTO::getDataType, (a, b) -> a));

        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT data FROM " + DATA_ROWS_TABLE + " WHERE table_id = ? ORDER BY id LIMIT ?",
                (rs, rowNum) -> parseRowData(rs.getString("data")),
                tableId, maxRows);

        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", columnNames)).append("\n");
        for (Map<String, Object> row : rows) {
            csv.append(columnNames.stream()
                    .map(f -> escapeCsvValue(RelationCsvValueFormatter.format(row.get(f), typeByField.get(f))))
                    .collect(Collectors.joining(","))
            ).append("\n");
        }
        return csv.toString();
    }

    private String escapeCsvValue(String str) {
        if (str == null) return "";
        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateTemplate(Long tableId, String format) {
        RelationTableDefinition tableDef = getDeployedTableDefinition(tableId);
        return templateService.generateTemplate(getDeployedFields(tableDef), format);
    }

    @Override
    @Transactional
    public Map<String, Object> importData(Long tableId, byte[] fileBytes, String format, boolean dryRun) {
        requireWriteAccess(tableId);
        RelationTableDefinition tableDef = getDeployedTableDefinition(tableId);
        List<RelationFieldDTO> fields = getDeployedFields(tableDef);

        List<Map<String, Object>> rawRows = templateService.parseImport(fileBytes, format);
        if (rawRows.size() > RelationTableTemplateService.MAX_IMPORT_ROWS) {
            throw new IllegalArgumentException(
                    "Too many rows: " + rawRows.size() + ". A single import is limited to "
                            + RelationTableTemplateService.MAX_IMPORT_ROWS + " rows.");
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

        // Auto-generate PK values per strategy for imported rows that don't carry one
        // (manual-strategy PKs come from the file and were validated as required above).
        String pkField = findPrimaryKeyField(fields);
        boolean autoPk = pkField != null && fields.stream()
                .anyMatch(f -> pkField.equals(f.getFieldName()) && !RelationRowValidator.isManualPk(f));

        int inserted = 0;
        for (RowValidationResult r : results) {
            if (r.isValid()) {
                Map<String, Object> row = new LinkedHashMap<>(r.getValues());
                if (autoPk && row.get(pkField) == null) {
                    List<String> values = primaryKeyAllocationService
                            .allocate(tableId, pkField, 1, "rt-" + tableId).getValues();
                    if (values != null && !values.isEmpty()) row.put(pkField, values.get(0));
                }
                // Reuse addData for storage row-id, audit and timestamp handling.
                addData(tableId, row);
                inserted++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inserted", inserted);
        summary.put("failed", results.size() - inserted);
        summary.put("errors", errors);
        return summary;
    }

    /**
     * Loads deployed table definition and validates status allows data access.
     */
    private RelationTableDefinition getDeployedTableDefinition(Long tableId) {
        RelationTableDefinition tableDef = tableDefinitionRepository.findById(tableId)
                .orElseThrow(() -> new RelationTableNotFoundException(tableId));
        if (tableDef.getStatus() != RelationTableStatus.DEPLOYED
                && tableDef.getStatus() != RelationTableStatus.UPDATED
                && tableDef.getStatus() != RelationTableStatus.ROLLBACK) {
            throw new RelationTableNotFoundException("Table is not deployed: " + tableId);
        }
        return tableDef;
    }

    /**
     * Field list for Data Management queries, aligned with deployed version snapshot when applicable.
     */
    private List<RelationFieldDTO> getDeployedFields(RelationTableDefinition tableDef) {
        if (tableDef.getCurrentVersion() != null && tableDef.getCurrentVersion() > 0) {
            Optional<RelationTableVersion> latestVersion =
                    versionRepository.findLatestVersion(tableDef.getId());
            if (latestVersion.isPresent()) {
                try {
                    List<RelationFieldDTO> snapshotFields =
                            parseSnapshotData(latestVersion.get().getSnapshotData());
                    if (!snapshotFields.isEmpty()) {
                        log.debug("Using snapshot fields for table '{}' (version {}) to match deployed schema",
                                tableDef.getTableName(), tableDef.getCurrentVersion());
                        return snapshotFields;
                    }
                } catch (Exception e) {
                    // FALLBACK(external): same semantics as the snapshot fallback above — degraded
                    // accuracy (design-time columns), not corruption.
                    log.warn("Cannot parse snapshot for table {}, falling back to rt_field_definitions",
                            tableDef.getId());
                }
            }
        }
        List<RelationFieldDTO> fields = jdbcTemplate.query(
                "SELECT id, field_name, data_type, length, precision_value, scale, nullable, is_primary_key, default_value, display_name, sort_order, "
                + "pk_generation_json::text AS pk_json, lookup_config::text AS lookup_json, "
                + "is_foreign_key, ref_table_id, ref_primary_key_fields::text AS ref_pk_json, fk_display_mode "
                + "FROM rt_field_definitions WHERE table_id = ? ORDER BY sort_order ASC",
                (rs, rowNum) -> RelationFieldDTO.builder()
                        .id(rs.getLong("id"))
                        .fieldName(rs.getString("field_name"))
                        .dataType(com.platform.common.enums.RelationDataType.valueOf(rs.getString("data_type")))
                        .length(rs.getObject("length", Integer.class))
                        .precision(rs.getObject("precision_value", Integer.class))
                        .scale(rs.getObject("scale", Integer.class))
                        .nullable(rs.getBoolean("nullable"))
                        .isPrimaryKey(rs.getBoolean("is_primary_key"))
                        .defaultValue(rs.getString("default_value"))
                        .displayName(rs.getString("display_name"))
                        .sortOrder(rs.getInt("sort_order"))
                        .pkGeneration(parsePkGenerationJson(rs.getString("pk_json")))
                        .lookupConfig(parsePkGenerationJson(rs.getString("lookup_json")))
                        .isForeignKey(rs.getObject("is_foreign_key", Boolean.class))
                        .refTableId(rs.getObject("ref_table_id", Long.class))
                        .refPrimaryKeyFields(parseStringListJson(rs.getString("ref_pk_json")))
                        .fkDisplayMode(rs.getString("fk_display_mode"))
                        .build(),
                tableDef.getId());
        if (fields.isEmpty()) {
            throw new RelationTableNotFoundException(
                    "No field definitions found for table: " + tableDef.getId());
        }
        return fields;
    }

    private Map<String, Object> getRowData(Long tableId, String rowId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT data FROM " + DATA_ROWS_TABLE + " WHERE table_id = ? AND row_id = ?",
                (rs, rowNum) -> parseRowData(rs.getString("data")),
                tableId, rowId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRowData(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse row JSON data", e);
        }
    }

    private Map<String, Object> parsePkGenerationJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseStringListJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String writeRowJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize row JSON data", e);
        }
    }

    /** True when a value is null or an empty/whitespace string (used to detect a missing PK). */
    private boolean isBlank(Object value) {
        return value == null || value.toString().trim().isEmpty();
    }

    private String resolveRowId(String pkField, Map<String, Object> data) {
        if (!CTID_PK.equals(pkField) && data.get(pkField) != null) {
            return data.get(pkField).toString();
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Builds JSON search WHERE clause ({@code data->>'field' ILIKE}).
     *
     * <p>A leading {@code data::text ILIKE ?} guard is prepended so the pg_trgm GIN index
     * {@code idx_rt_data_rows_data_trgm} can serve the leading-wildcard search; the per-field
     * clause then filters out whole-row false positives (e.g. matches on JSON keys/punctuation),
     * keeping the result set identical to the per-field-only predicate. See migration V214.
     */
    private String buildJsonSearchWhereClause(List<RelationFieldDTO> fields, String search, List<Object> params) {
        if (search == null || search.isBlank()) {
            return "";
        }
        List<String> searchableFields = fields.stream()
                .filter(this::isTextType)
                .map(RelationFieldDTO::getFieldName)
                .filter(this::isSafeFieldName)
                .collect(Collectors.toList());
        if (searchableFields.isEmpty()) {
            return "";
        }
        String searchPattern = "%" + escapeLikePattern(search) + "%";
        String conditions = searchableFields.stream()
                .map(f -> "data->>'" + SqlIdentifiers.requireIdentifier(f) + "' ILIKE ? ESCAPE '\\'")
                .collect(Collectors.joining(" OR "));
        // index-accelerated broad guard (trgm GIN) first, then exact per-field filter for correctness
        params.add(searchPattern);
        for (int i = 0; i < searchableFields.size(); i++) {
            params.add(searchPattern);
        }
        return " AND data::text ILIKE ? ESCAPE '\\' AND (" + conditions + ")";
    }

    private boolean isSafeFieldName(String fieldName) {
        return fieldName != null && fieldName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    /**
     * Virtual PK name used when no field has isPrimaryKey=true.
     */
    private static final String CTID_PK = "__uuid__";

    /**
     * Returns the name of the primary-key field, or a generated UUID key when none is marked PK.
     */
    private String findPrimaryKeyField(List<RelationFieldDTO> fields) {
        return fields.stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsPrimaryKey()))
                .map(RelationFieldDTO::getFieldName)
                .findFirst()
                .orElse(CTID_PK);
    }

    private String escapeLikePattern(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Whether the field type is textual (searchable).
     */
    private boolean isTextType(RelationFieldDTO field) {
        return switch (field.getDataType()) {
            case VARCHAR, TEXT, LOOKUP -> true;
            default -> false;
        };
    }

    /**
     * Parses version snapshot JSON into field DTOs.
     */
    private List<RelationFieldDTO> parseSnapshotData(String snapshotData) {
        try {
            return objectMapper.readValue(snapshotData, new TypeReference<List<RelationFieldDTO>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse snapshot data", e);
        }
    }
}
