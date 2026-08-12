package com.portal.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.component.FunctionUnitAccessComponent;
import com.portal.component.MainTableViewAccessResolver;
import com.portal.component.MainTableViewAccessResolver.AccessRule;
import com.portal.component.MainTableViewJdbcQuery;
import com.portal.component.MainTableViewJdbcQuery.MainInstanceRow;
import com.portal.component.MainTableViewJdbcQuery.MainPageResult;
import com.portal.component.MainTableViewJdbcQuery.QuerySpec;
import com.portal.component.MainTableViewJdbcQuery.SubPageResult;
import com.portal.component.MainTableViewJdbcQuery.SubRow;
import com.portal.component.ProcessComponent;
import com.portal.dto.MainTableViewImportResult;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.dto.ProcessStartRequest;
import com.portal.dto.MainTableViewPortalDtos.FunctionUnitViewMenuItem;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewColumnFilter;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewDataPage;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewDataRow;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewFieldColumn;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewGroupCount;
import com.portal.util.MainTableViewFkDisplaySupport;
import com.portal.util.MainTableViewSqlQueryCompiler.FieldMeta;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewSummary;
import com.portal.entity.ProcessInstance;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.PortalMainTableViewService;
import com.portal.util.PortalMainTableViewCsvUtils;
import com.portal.util.PortalMainTableViewFilterUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortalMainTableViewServiceImpl implements PortalMainTableViewService {

    private static final String PROCESS_INSTANCE_ID_FIELD = "processInstanceId";
    private static final Set<String> PROCESS_INSTANCE_ID_HEADERS = Set.of(
            PROCESS_INSTANCE_ID_FIELD,
            "Process Instance ID",
            "process_instance_id"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FunctionUnitAccessComponent functionUnitAccessComponent;
    private final MainTableViewAccessResolver mainTableViewAccessResolver;
    private final MainTableViewJdbcQuery mainTableViewJdbcQuery;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessComponent processComponent;

    @Override
    @Transactional(readOnly = true)
    public List<FunctionUnitViewMenuItem> listAccessibleFunctionUnits(String userId) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList("""
                    SELECT fu.id AS fu_id, fu.code AS fu_code, fu.name AS fu_name, v.id AS view_id
                    FROM dw_main_table_view_configs v
                    INNER JOIN dw_function_units fu ON fu.id = v.function_unit_id
                    WHERE v.status = 'PUBLISHED'
                    ORDER BY fu.name, v.id
                    """);
        } catch (Exception e) {
            log.warn("Main table view menu query failed: {}", e.getMessage());
            return List.of();
        }

        Map<String, List<Long>> viewIdsByFuCode = new LinkedHashMap<>();
        Map<String, Map<String, Object>> fuMetaByCode = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String code = stringVal(row.get("fu_code"));
            if (code == null) {
                continue;
            }
            fuMetaByCode.putIfAbsent(code, row);
            viewIdsByFuCode.computeIfAbsent(code, k -> new ArrayList<>())
                    .add(((Number) row.get("view_id")).longValue());
        }

        List<FunctionUnitViewMenuItem> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : fuMetaByCode.entrySet()) {
            String code = entry.getKey();
            Map<String, Object> row = entry.getValue();
            if (!functionUnitAccessComponent.canAccessFunctionUnit(userId, code)) {
                continue;
            }
            if (!functionUnitAccessComponent.isFunctionUnitEnabled(code)) {
                continue;
            }
            long visibleCount = countVisibleViews(userId, viewIdsByFuCode.getOrDefault(code, List.of()));
            if (visibleCount <= 0) {
                continue;
            }
            result.add(FunctionUnitViewMenuItem.builder()
                    .functionUnitId(String.valueOf(row.get("fu_id")))
                    .functionUnitCode(code)
                    .functionUnitName(stringVal(row.get("fu_name")))
                    .viewCount((int) visibleCount)
                    .build());
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MainTableViewSummary> listPublishedViews(String userId, String functionUnitCode) {
        assertFuAccess(userId, functionUnitCode);
        try {
            List<MainTableViewSummary> summaries = jdbcTemplate.query("""
                            SELECT v.id, v.view_name, v.is_default, v.filter_config::text AS filter_config,
                                   v.main_table_id,
                                   COALESCE(td.table_display_name, td.table_name) AS table_label
                            FROM dw_main_table_view_configs v
                            INNER JOIN dw_function_units fu ON fu.id = v.function_unit_id
                            LEFT JOIN dw_table_definitions td ON td.id = v.main_table_id
                            WHERE fu.code = ? AND v.status = 'PUBLISHED'
                            ORDER BY COALESCE(td.table_display_name, td.table_name),
                                     v.is_default DESC, v.view_name
                            """,
                    (rs, rowNum) -> {
                        Map<String, Object> toolbar = parseToolbarConfig(stringVal(rs.getString("filter_config")));
                        return MainTableViewSummary.builder()
                                .id(rs.getLong("id"))
                                .viewName(rs.getString("view_name"))
                                .isDefault(rs.getBoolean("is_default"))
                                .tableId(rs.getObject("main_table_id") != null ? rs.getLong("main_table_id") : null)
                                .tableLabel(rs.getString("table_label"))
                                .enableExport(toolbarEnable(toolbar, "enableExport", true))
                                .enableImport(toolbarEnable(toolbar, "enableImport", true))
                                .build();
                    },
                    functionUnitCode.trim());
            return summaries.stream()
                    .filter(summary -> canUserSeeView(userId, summary.id()))
                    .toList();
        } catch (Exception e) {
            log.warn("List published views failed for {}: {}", functionUnitCode, e.getMessage());
            return List.of();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MainTableViewDataPage queryViewData(
            String userId,
            Long viewId,
            int page,
            int size,
            String search,
            List<MainTableViewColumnFilter> columnFilters,
            String sortField,
            String sortDirection,
            String groupBy) {
        ViewDefinition view = loadPublishedView(viewId);
        assertFuAccess(userId, view.functionUnitCode());
        assertViewAccess(userId, view);

        List<MainTableViewFieldColumn> columns = visibleColumns(view);
        Set<String> visibleFields = columns.stream()
                .map(MainTableViewFieldColumn::fieldName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<MainTableViewColumnFilter> safeFilters = sanitizeColumnFilters(columnFilters, visibleFields);
        String safeSortField = sanitizeFieldRef(sortField, visibleFields);
        String safeGroupBy = sanitizeFieldRef(groupBy, visibleFields);

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);

        QuerySpec spec = buildQuerySpec(userId, view, columns, visibleFields, safeFilters,
                search, safeSortField, sortDirection, safeGroupBy);

        List<MainTableViewDataRow> rows;
        long total;
        List<MainTableViewGroupCount> groupCounts;
        if ("SUB".equalsIgnoreCase(view.tableType())) {
            SubPageResult result = mainTableViewJdbcQuery.querySub(spec, safePage, safeSize);
            total = result.total();
            groupCounts = result.groupCounts();
            rows = result.rows().stream()
                    .map(r -> toDataRow(projectSubJdbcRow(r, view)))
                    .toList();
        } else {
            MainPageResult result = mainTableViewJdbcQuery.queryMain(spec, safePage, safeSize);
            total = result.total();
            groupCounts = result.groupCounts();
            rows = result.rows().stream()
                    .map(r -> toDataRow(projectMainJdbcRow(r, view)))
                    .toList();
        }

        return MainTableViewDataPage.builder()
                .columns(columns)
                .rows(rows)
                .total(total)
                .page(safePage)
                .size(safeSize)
                .groupCounts(groupCounts)
                .build();
    }

    private QuerySpec buildQuerySpec(
            String userId,
            ViewDefinition view,
            List<MainTableViewFieldColumn> columns,
            Set<String> visibleFields,
            List<MainTableViewColumnFilter> safeFilters,
            String search,
            String sortField,
            String sortDirection,
            String groupBy) {
        List<FieldMeta> fieldMetas = view.fields().stream()
                .map(f -> new FieldMeta(
                        f.fieldName(),
                        Boolean.TRUE.equals(f.systemField()),
                        f.columnType(),
                        f.lookupSourceField()))
                .toList();
        // Designer filter_config may include a non-filter "toolbar" key — compiler ignores empty trees.
        Map<String, Object> designerFilter = new LinkedHashMap<>();
        if (view.filterConfig() != null) {
            designerFilter.putAll(view.filterConfig());
        }
        designerFilter.remove("toolbar");
        return new QuerySpec(
                view.functionUnitCode(),
                fieldMetas,
                visibleFields,
                designerFilter,
                view.sortConfig(),
                safeFilters,
                search,
                sortField,
                sortDirection,
                groupBy,
                view.restrictToInvolvedUsers(),
                userId,
                view.subBindingKeys(),
                mainTableViewAccessResolver.isSystemAdministrator(userId));
    }

    private MainTableViewDataRow toDataRow(Map<String, Object> projected) {
        return MainTableViewDataRow.builder()
                .processInstanceId(String.valueOf(projected.get("_processInstanceId")))
                .values(stripInternalKeys(projected))
                .build();
    }

    private Map<String, Object> projectMainJdbcRow(MainInstanceRow row, ViewDefinition view) {
        ProcessInstance pi = ProcessInstance.builder()
                .id(row.id())
                .status(row.status())
                .startTime(row.startTime())
                .startUserId(row.startUserId())
                .startUserName(row.startUserName())
                .currentNode(row.currentNode())
                .variables(row.variables() != null ? row.variables() : Map.of())
                .build();
        return projectInstanceRow(pi, view);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> projectSubJdbcRow(SubRow row, ViewDefinition view) {
        Map<String, Object> mainVars = stripInternalKeys(
                row.mainVariables() != null ? row.mainVariables() : Map.of());
        Map<String, FkSourceMeta> fkSource = loadFkSourceMeta(view.id());
        Map<String, Object> source = row.subElem() != null ? row.subElem() : Map.of();
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("_processInstanceId", row.processInstanceId());
        for (ViewFieldDef field : view.fields()) {
            if (!Boolean.TRUE.equals(field.visible())) {
                continue;
            }
            if (Boolean.TRUE.equals(field.systemField())) {
                projected.put(field.fieldName(), switch (field.fieldName()) {
                    case "process_status" -> row.status();
                    case "start_time" -> row.startTime();
                    case "initiator" -> row.startUserName() != null ? row.startUserName() : row.startUserId();
                    case "current_step" -> row.currentNode();
                    default -> null;
                });
            } else {
                projected.put(field.fieldName(),
                        resolveProjectedFieldValue(source, field, mainVars, fkSource));
            }
        }
        return projected;
    }

    private List<MainTableViewColumnFilter> sanitizeColumnFilters(
            List<MainTableViewColumnFilter> filters,
            Set<String> visibleFields) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        List<MainTableViewColumnFilter> out = new ArrayList<>();
        for (MainTableViewColumnFilter filter : filters) {
            if (filter == null || !visibleFields.contains(filter.fieldName())) {
                continue;
            }
            if (!PortalMainTableViewFilterUtils.isActiveColumnFilter(filter)) {
                continue;
            }
            out.add(filter);
        }
        return out;
    }

    private String sanitizeFieldRef(String field, Set<String> visibleFields) {
        if (field == null || field.isBlank()) {
            return null;
        }
        String trimmed = field.trim();
        return visibleFields.contains(trimmed) ? trimmed : null;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportViewCsv(String userId, Long viewId, int maxRows) {
        ViewDefinition view = loadPublishedView(viewId);
        assertFuAccess(userId, view.functionUnitCode());
        assertViewAccess(userId, view);

        List<MainTableViewFieldColumn> columns = visibleColumns(view);
        Set<String> visibleFields = columns.stream()
                .map(MainTableViewFieldColumn::fieldName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int limit = Math.min(Math.max(maxRows, 1), 10000);
        QuerySpec spec = buildQuerySpec(userId, view, columns, visibleFields, List.of(),
                null, null, null, null);

        List<Map<String, Object>> slice;
        if ("SUB".equalsIgnoreCase(view.tableType())) {
            slice = mainTableViewJdbcQuery.querySubExport(spec, limit).stream()
                    .map(r -> projectSubJdbcRow(r, view))
                    .toList();
        } else {
            slice = mainTableViewJdbcQuery.queryMainExport(spec, limit).stream()
                    .map(r -> projectMainJdbcRow(r, view))
                    .toList();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(PortalMainTableViewCsvUtils.csvEscape(PROCESS_INSTANCE_ID_FIELD));
        if (!columns.isEmpty()) {
            sb.append(',');
        }
        sb.append(columns.stream().map(c -> PortalMainTableViewCsvUtils.csvEscape(c.displayLabel())).collect(Collectors.joining(",")));
        sb.append('\n');
        for (Map<String, Object> row : slice) {
            Map<String, Object> values = stripInternalKeys(row);
            sb.append(PortalMainTableViewCsvUtils.csvEscape(String.valueOf(row.get("_processInstanceId"))));
            if (!columns.isEmpty()) {
                sb.append(',');
            }
            sb.append(columns.stream()
                    .map(c -> PortalMainTableViewCsvUtils.csvEscape(PortalMainTableViewCsvUtils.formatCell(values.get(c.fieldName()))))
                    .collect(Collectors.joining(",")));
            sb.append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional
    public MainTableViewImportResult importViewCsv(String userId, Long viewId, byte[] csvBytes) {
        ViewDefinition view = loadPublishedView(viewId);
        assertFuAccess(userId, view.functionUnitCode());
        assertViewAccess(userId, view);

        if (csvBytes == null || csvBytes.length == 0) {
            throw new IllegalArgumentException("CSV file is empty");
        }

        List<MainTableViewFieldColumn> columns = visibleColumns(view);
        Map<String, String> headerToField = new LinkedHashMap<>();
        for (MainTableViewFieldColumn col : columns) {
            if (Boolean.TRUE.equals(col.systemField())) {
                continue;
            }
            headerToField.put(col.displayLabel(), col.fieldName());
            headerToField.put(col.fieldName(), col.fieldName());
        }

        List<String[]> parsedRows = PortalMainTableViewCsvUtils.parseCsvRows(csvBytes);
        if (parsedRows.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows");
        }

        String[] headers = parsedRows.get(0);
        int processIdCol = PortalMainTableViewCsvUtils.findProcessInstanceIdColumn(headers, PROCESS_INSTANCE_ID_HEADERS);

        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (int rowIdx = 1; rowIdx < parsedRows.size(); rowIdx++) {
            String[] cells = parsedRows.get(rowIdx);
            if (cells.length == 0 || PortalMainTableViewCsvUtils.isBlankRow(cells)) {
                skipped++;
                continue;
            }
            String processInstanceId = processIdCol >= 0 ? PortalMainTableViewCsvUtils.cellValue(cells, processIdCol) : "";
            if (processInstanceId.isBlank()) {
                if (createProcessFromCsvRow(userId, view, headers, cells, processIdCol, headerToField, rowIdx + 1, errors)) {
                    created++;
                }
                continue;
            }

            Optional<ProcessInstance> optPi = processInstanceRepository.findById(processInstanceId);
            if (optPi.isEmpty()) {
                errors.add("Row " + (rowIdx + 1) + ": process not found " + processInstanceId);
                continue;
            }
            ProcessInstance pi = optPi.get();
            if (!userId.equals(pi.getStartUserId())) {
                errors.add("Row " + (rowIdx + 1) + ": access denied for " + processInstanceId);
                continue;
            }
            if (view.functionUnitCode() != null
                    && pi.getFunctionUnitCode() != null
                    && !view.functionUnitCode().equalsIgnoreCase(pi.getFunctionUnitCode())) {
                errors.add("Row " + (rowIdx + 1) + ": function unit mismatch for " + processInstanceId);
                continue;
            }

            Map<String, Object> vars = pi.getVariables() != null
                    ? new HashMap<>(pi.getVariables())
                    : new HashMap<>();
            boolean changed = false;
            for (int col = 0; col < headers.length; col++) {
                if (col == processIdCol) {
                    continue;
                }
                String header = headers[col] != null ? headers[col].trim() : "";
                String fieldName = headerToField.get(header);
                if (fieldName == null) {
                    continue;
                }
                String raw = PortalMainTableViewCsvUtils.cellValue(cells, col);
                Object parsed = raw.isBlank() ? null : raw;
                Object existing = vars.get(fieldName);
                if (!Objects.equals(existing, parsed)) {
                    if (parsed == null) {
                        vars.remove(fieldName);
                    } else {
                        vars.put(fieldName, parsed);
                    }
                    changed = true;
                }
            }
            if (changed) {
                pi.setVariables(vars);
                processInstanceRepository.save(pi);
                updated++;
            } else {
                skipped++;
            }
        }

        return MainTableViewImportResult.builder()
                .createdCount(created)
                .updatedCount(updated)
                .skippedCount(skipped)
                .errorCount(errors.size())
                .errors(errors.size() > 20 ? errors.subList(0, 20) : errors)
                .build();
    }

    /**
     * Starts a new process for a CSV row with blank {@code processInstanceId}.
     *
     * @return {@code true} if a process was created
     */
    private boolean createProcessFromCsvRow(
            String userId,
            ViewDefinition view,
            String[] headers,
            String[] cells,
            int processIdCol,
            Map<String, String> headerToField,
            int rowNumber,
            List<String> errors) {
        Map<String, Object> formData = new LinkedHashMap<>();
        String businessKey = null;
        for (int col = 0; col < headers.length; col++) {
            if (col == processIdCol) {
                continue;
            }
            String header = headers[col] != null ? headers[col].trim() : "";
            String fieldName = headerToField.get(header);
            if (fieldName == null) {
                continue;
            }
            String raw = PortalMainTableViewCsvUtils.cellValue(cells, col);
            if (raw.isBlank()) {
                continue;
            }
            formData.put(fieldName, parseImportCellValue(raw));
            if (businessKey == null && ("case_number".equals(fieldName) || "business_key".equals(fieldName))) {
                businessKey = raw;
            }
        }
        if (formData.isEmpty()) {
            errors.add("Row " + rowNumber + ": no field values for new record");
            return false;
        }
        if (view.functionUnitCode() == null || view.functionUnitCode().isBlank()) {
            errors.add("Row " + rowNumber + ": view has no function unit for process start");
            return false;
        }
        try {
            ProcessStartRequest request = ProcessStartRequest.builder()
                    .formData(formData)
                    .businessKey(businessKey)
                    .build();
            ProcessInstanceInfo created = processComponent.startProcess(userId, view.functionUnitCode(), request);
            log.info("CSV import created process {} for user {} at row {}", created.getId(), userId, rowNumber);
            return true;
        } catch (Exception ex) {
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            errors.add("Row " + rowNumber + ": create failed – " + message);
            log.warn("CSV import create failed at row {}: {}", rowNumber, message);
            return false;
        }
    }

    private Object parseImportCellValue(String raw) {
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.parseBoolean(raw);
        }
        return raw;
    }

    private Map<String, Object> projectInstanceRow(ProcessInstance pi, ViewDefinition view) {
        Map<String, Object> vars = pi.getVariables() != null ? pi.getVariables() : Map.of();
        Map<String, Object> mainVars = stripInternalKeys(vars);
        Map<String, FkSourceMeta> fkSource = loadFkSourceMeta(view.id());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_processInstanceId", pi.getId());

        for (ViewFieldDef field : view.fields()) {
            if (!Boolean.TRUE.equals(field.visible())) {
                continue;
            }
            if (Boolean.TRUE.equals(field.systemField())) {
                row.put(field.fieldName(), systemFieldValue(pi, field.fieldName()));
            } else {
                row.put(field.fieldName(), resolveProjectedFieldValue(vars, field, mainVars, fkSource));
            }
        }
        return row;
    }

    /**
     * Lookup display columns are not stored in process variables — project the source lookup
     * value (PK or row object) under the synthetic column key so the portal can hydrate labels.
     * FK display columns resolve against same-instance MAIN variables; unmatched keeps the FK scalar.
     */
    private Object resolveProjectedFieldValue(
            Map<String, Object> source,
            ViewFieldDef field,
            Map<String, Object> mainVars,
            Map<String, FkSourceMeta> fkSourceMeta) {
        if ("lookup_display".equalsIgnoreCase(field.columnType())
                && field.lookupSourceField() != null && !field.lookupSourceField().isBlank()) {
            return source.get(field.lookupSourceField());
        }
        if ("fk_display".equalsIgnoreCase(field.columnType())
                && field.lookupSourceField() != null && !field.lookupSourceField().isBlank()) {
            Object fkVal = source.get(field.lookupSourceField());
            FkSourceMeta meta = fkSourceMeta.get(field.lookupSourceField());
            List<String> pkFields = meta != null ? meta.refPrimaryKeyFields() : List.of();
            Object resolved = MainTableViewFkDisplaySupport.resolveAttribute(
                    mainVars, fkVal, pkFields, field.lookupDisplayField());
            // FALLBACK(ux): unmatched FK — show raw scalar rather than inventing a value
            return resolved != null ? resolved : fkVal;
        }
        return source.get(field.fieldName());
    }

    private Object systemFieldValue(ProcessInstance pi, String fieldName) {
        return switch (fieldName) {
            case "process_status" -> pi.getStatus();
            case "start_time" -> pi.getStartTime();
            case "initiator" -> pi.getStartUserName() != null ? pi.getStartUserName() : pi.getStartUserId();
            case "current_step" -> pi.getCurrentNode();
            default -> null;
        };
    }

    private List<MainTableViewFieldColumn> visibleColumns(ViewDefinition view) {
        Map<String, FkColumnMeta> resolvedFk = loadFkColumnMeta(view.id());
        // SUB views: the main-id column is a FK back to the owning MAIN table. That linkage lives on the
        // form-table binding (foreign_key_field), not in dw_field_definitions, so resolve it separately.
        if ("SUB".equalsIgnoreCase(view.tableType()) && view.mainTableId() != null) {
            Map<String, FkColumnMeta> merged = new LinkedHashMap<>(resolvedFk);
            loadSubMainFkMeta(view.mainTableId())
                    .ifPresent(m -> merged.putIfAbsent(m.fieldName(), m.meta()));
            resolvedFk = merged;
        }
        final Map<String, FkColumnMeta> fkMeta = resolvedFk;
        final Map<String, FkSourceMeta> fkSourceMeta = loadFkSourceMeta(view.id());
        // Lookup columns reference a Relation Table (via the form's lookupConfig), not a DW table.
        final Map<String, LookupColumnMeta> lookupMeta = loadLookupColumnMeta(view.mainTableId());
        return view.fields().stream()
                .filter(f -> Boolean.TRUE.equals(f.visible()))
                .sorted(Comparator.comparingInt(f -> f.sortOrder() != null ? f.sortOrder() : 0))
                .map(f -> {
                    boolean lookupDisplay = "lookup_display".equalsIgnoreCase(
                            f.columnType() != null ? f.columnType() : "");
                    boolean fkDisplay = "fk_display".equalsIgnoreCase(
                            f.columnType() != null ? f.columnType() : "");
                    boolean derived = lookupDisplay || fkDisplay;
                    String sourceField = derived && f.lookupSourceField() != null
                            ? f.lookupSourceField()
                            : f.fieldName();
                    FkColumnMeta fk = derived ? null : fkMeta.get(f.fieldName());
                    FkSourceMeta fkSrc = fkDisplay ? fkSourceMeta.get(sourceField) : null;
                    LookupColumnMeta lookup = lookupDisplay || !derived
                            ? lookupMeta.get(sourceField)
                            : null;
                    Long lookupTableId = lookup != null ? lookup.tableId() : null;
                    String columnType = lookupDisplay ? "lookup_display"
                            : (fkDisplay ? "fk_display" : "field");
                    return MainTableViewFieldColumn.builder()
                            .fieldName(f.fieldName())
                            .displayLabel(f.displayLabel() != null ? f.displayLabel() : f.fieldName())
                            .columnWidth(f.columnWidth())
                            .systemField(f.systemField())
                            .isForeignKey(fk != null)
                            .refViewId(fk != null ? fk.refViewId() : null)
                            .refFunctionUnitCode(fk != null ? fk.refFunctionUnitCode() : null)
                            .refPrimaryKeyFields(fk != null ? fk.refPrimaryKeyFields()
                                    : (fkSrc != null ? fkSrc.refPrimaryKeyFields() : null))
                            .isLookup(lookupTableId != null)
                            .lookupTableId(lookupTableId)
                            .columnType(columnType)
                            .lookupSourceField(derived ? f.lookupSourceField()
                                    : (lookup != null ? sourceField : null))
                            .lookupDisplayField(derived ? f.lookupDisplayField() : null)
                            .lookupSelectedDisplayField(lookup != null ? lookup.selectedDisplayField() : null)
                            .lookupSearchFields(lookup != null ? lookup.searchFields() : null)
                            .fkRefTableId(fkSrc != null ? fkSrc.refTableId() : null)
                            .build();
                })
                .toList();
    }

    /**
     * FK source metadata for {@code fk_display} hydration (does not require a published default view).
     */
    private Map<String, FkSourceMeta> loadFkSourceMeta(Long viewId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT fd.field_name,
                           fd.ref_table_id,
                           fd.ref_primary_key_fields::text AS ref_pk_fields
                    FROM dw_main_table_view_configs v
                    INNER JOIN dw_field_definitions fd ON fd.table_id = v.main_table_id
                    WHERE v.id = ? AND fd.is_foreign_key = TRUE AND fd.ref_table_id IS NOT NULL
                    """, viewId);
            Map<String, FkSourceMeta> meta = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String fieldName = stringVal(row.get("field_name"));
                if (fieldName == null) {
                    continue;
                }
                Object refTableObj = row.get("ref_table_id");
                Long refTableId = refTableObj instanceof Number n ? n.longValue() : null;
                meta.put(fieldName, new FkSourceMeta(
                        refTableId,
                        parseStringList(stringVal(row.get("ref_pk_fields")))));
            }
            return meta;
        } catch (Exception e) {
            log.warn("Failed to load FK source metadata for view {}: {}", viewId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Resolve lookup columns for the view's owning table: scan every form that binds the table, parse each
     * {@code type:"lookup"} widget's {@code props.lookupConfig}, and map the widget's {@code field} →
     * full lookup metadata (table id, search/display fields) for portal hydration and drill-down.
     */
    private Map<String, LookupColumnMeta> loadLookupColumnMeta(Long tableId) {
        if (tableId == null) {
            return Map.of();
        }
        try {
            List<String> formConfigs = jdbcTemplate.query("""
                    SELECT DISTINCT f.config_json::text AS cfg
                    FROM dw_form_table_bindings b
                    INNER JOIN dw_form_definitions f ON f.id = b.form_id
                    WHERE b.table_id = ? AND f.config_json IS NOT NULL
                    """, (rs, n) -> rs.getString("cfg"), tableId);
            Map<String, LookupColumnMeta> meta = new LinkedHashMap<>();
            for (String cfg : formConfigs) {
                collectLookupFields(cfg, meta);
            }
            return meta;
        } catch (Exception e) {
            log.warn("Failed to load lookup column metadata for table {}: {}", tableId, e.getMessage());
            return Map.of();
        }
    }

    /** Walk a form config JSON tree, recording each lookup widget's field → lookup metadata. */
    private void collectLookupFields(String configJson, Map<String, LookupColumnMeta> out) {
        if (configJson == null || configJson.isBlank()) {
            return;
        }
        try {
            walkLookupNodes(objectMapper.readTree(configJson), out);
        } catch (Exception e) {
            log.warn("Failed to parse form config for lookup fields: {}", e.getMessage());
        }
    }

    private void walkLookupNodes(com.fasterxml.jackson.databind.JsonNode node, Map<String, LookupColumnMeta> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            String type = node.path("type").asText(null);
            String field = node.path("field").asText(null);
            if ("lookup".equalsIgnoreCase(type) && field != null && !field.isBlank()) {
                LookupColumnMeta meta = parseLookupConfigMeta(node.path("props").path("lookupConfig").asText(null));
                if (meta != null) {
                    out.putIfAbsent(field, meta);
                }
            }
            node.fields().forEachRemaining(e -> walkLookupNodes(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> walkLookupNodes(child, out));
        }
    }

    /** {@code lookupConfig} is a JSON string holding {tableId, searchFields, selectedDisplayField, ...}. */
    private LookupColumnMeta parseLookupConfigMeta(String lookupConfigJson) {
        if (lookupConfigJson == null || lookupConfigJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode cfg = objectMapper.readTree(lookupConfigJson);
            com.fasterxml.jackson.databind.JsonNode tableIdNode = cfg.get("tableId");
            if (tableIdNode == null || !tableIdNode.isNumber()) {
                return null;
            }
            List<String> searchFields = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode searchNode = cfg.get("searchFields");
            if (searchNode != null && searchNode.isArray()) {
                searchNode.forEach(n -> {
                    if (n != null && n.isTextual() && !n.asText().isBlank()) {
                        searchFields.add(n.asText());
                    }
                });
            }
            String selected = null;
            if (cfg.hasNonNull("selectedDisplayField") && cfg.get("selectedDisplayField").isTextual()) {
                selected = cfg.get("selectedDisplayField").asText();
            } else if (cfg.hasNonNull("displayField") && cfg.get("displayField").isTextual()) {
                selected = cfg.get("displayField").asText();
            }
            return new LookupColumnMeta(tableIdNode.asLong(), searchFields, selected);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * For each FK field of the view's owning table, resolve the referenced table's PUBLISHED default
     * view (id + FU code) so the portal can render a drill-down link. FK columns whose referenced table
     * has no published default view are omitted from the map (rendered as plain text — graceful degrade).
     */
    private Map<String, FkColumnMeta> loadFkColumnMeta(Long viewId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT fd.field_name,
                           fd.ref_primary_key_fields::text AS ref_pk_fields,
                           rv.id AS ref_view_id,
                           rfu.code AS ref_fu_code
                    FROM dw_main_table_view_configs v
                    INNER JOIN dw_field_definitions fd ON fd.table_id = v.main_table_id
                    INNER JOIN dw_table_definitions rt ON rt.id = fd.ref_table_id
                    INNER JOIN dw_function_units rfu ON rfu.id = rt.function_unit_id
                    INNER JOIN dw_main_table_view_configs rv
                            ON rv.main_table_id = fd.ref_table_id
                           AND rv.is_default = TRUE
                           AND rv.status = 'PUBLISHED'
                    WHERE v.id = ? AND fd.is_foreign_key = TRUE AND fd.ref_table_id IS NOT NULL
                    """, viewId);
            Map<String, FkColumnMeta> meta = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String fieldName = stringVal(row.get("field_name"));
                if (fieldName == null) {
                    continue;
                }
                meta.put(fieldName, new FkColumnMeta(
                        ((Number) row.get("ref_view_id")).longValue(),
                        stringVal(row.get("ref_fu_code")),
                        parseStringList(stringVal(row.get("ref_pk_fields")))));
            }
            return meta;
        } catch (Exception e) {
            log.warn("Failed to load FK column metadata for view {}: {}", viewId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Resolve the FK metadata for a SUB table's main-id column. The SUB→MAIN link is stored on the
     * form-table binding ({@code foreign_key_field}); the MAIN table is the PRIMARY-bound table on the
     * same form(s). Returns the main-id field name + the MAIN table's published default view, or empty
     * when no such published default view exists (graceful degrade to plain text).
     */
    private Optional<SubMainFk> loadSubMainFkMeta(Long subTableId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT sub.foreign_key_field AS fk_field,
                           rv.id AS ref_view_id,
                           rfu.code AS ref_fu_code
                    FROM dw_form_table_bindings sub
                    INNER JOIN dw_form_table_bindings main
                            ON main.form_id = sub.form_id
                           AND main.binding_type = 'PRIMARY'
                    INNER JOIN dw_table_definitions rt ON rt.id = main.table_id
                    INNER JOIN dw_function_units rfu ON rfu.id = rt.function_unit_id
                    INNER JOIN dw_main_table_view_configs rv
                            ON rv.main_table_id = main.table_id
                           AND rv.is_default = TRUE
                           AND rv.status = 'PUBLISHED'
                    WHERE sub.table_id = ?
                      AND sub.binding_type = 'SUB'
                      AND sub.foreign_key_field IS NOT NULL
                    LIMIT 1
                    """, subTableId);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> row = rows.get(0);
            String fkField = stringVal(row.get("fk_field"));
            if (fkField == null || fkField.isBlank()) {
                return Optional.empty();
            }
            FkColumnMeta meta = new FkColumnMeta(
                    ((Number) row.get("ref_view_id")).longValue(),
                    stringVal(row.get("ref_fu_code")),
                    List.of());
            return Optional.of(new SubMainFk(fkField, meta));
        } catch (Exception e) {
            log.warn("Failed to resolve SUB main FK metadata for table {}: {}", subTableId, e.getMessage());
            return Optional.empty();
        }
    }

    private List<String> parseStringList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> stripInternalKeys(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            if (!k.startsWith("_")) {
                out.put(k, v);
            }
        });
        return out;
    }

    private ViewDefinition loadPublishedView(Long viewId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT v.id, v.view_name, v.sort_config::text AS sort_config,
                       v.filter_config::text AS filter_config, fu.code AS fu_code,
                       v.main_table_id, td.table_type,
                       v.restrict_to_involved_users
                FROM dw_main_table_view_configs v
                INNER JOIN dw_function_units fu ON fu.id = v.function_unit_id
                LEFT JOIN dw_table_definitions td ON td.id = v.main_table_id
                WHERE v.id = ? AND v.status = 'PUBLISHED'
                """, viewId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Published view not found: " + viewId);
        }
        Map<String, Object> row = rows.get(0);
        List<ViewFieldDef> fields = jdbcTemplate.query("""
                        SELECT field_name, display_label, column_width, sort_order, visible, is_system_field,
                               COALESCE(column_type, 'field') AS column_type,
                               lookup_source_field, lookup_display_field
                        FROM dw_main_table_view_fields
                        WHERE view_config_id = ?
                        ORDER BY sort_order
                        """,
                (rs, rowNum) -> new ViewFieldDef(
                        rs.getString("field_name"),
                        rs.getString("display_label"),
                        rs.getObject("column_width") != null ? rs.getInt("column_width") : null,
                        rs.getInt("sort_order"),
                        rs.getBoolean("visible"),
                        rs.getBoolean("is_system_field"),
                        rs.getString("column_type"),
                        rs.getString("lookup_source_field"),
                        rs.getString("lookup_display_field")),
                viewId);

        Long mainTableId = row.get("main_table_id") != null
                ? ((Number) row.get("main_table_id")).longValue() : null;
        String tableType = stringVal(row.get("table_type"));
        // SUB-table data is nested under variables.__subTables__, keyed by each binding id that maps
        // this table into a form. Collect those binding keys so we can flatten the rows below.
        List<String> subBindingKeys = new ArrayList<>();
        if ("SUB".equalsIgnoreCase(tableType) && mainTableId != null) {
            subBindingKeys = jdbcTemplate.queryForList(
                    "SELECT id FROM dw_form_table_bindings WHERE table_id = ?",
                    Long.class, mainTableId).stream().map(String::valueOf).toList();
        }

        return new ViewDefinition(
                viewId,
                stringVal(row.get("view_name")),
                stringVal(row.get("fu_code")),
                parseJsonList(stringVal(row.get("sort_config"))),
                parseJsonMap(stringVal(row.get("filter_config"))),
                fields,
                mainTableId,
                tableType,
                subBindingKeys,
                Boolean.TRUE.equals(row.get("restrict_to_involved_users")),
                loadAccessRules(viewId));
    }

    private List<AccessRule> loadAccessRules(Long viewId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT target_type, target_id
                    FROM dw_main_table_view_access
                    WHERE view_config_id = ?
                    """, viewId);
            return mainTableViewAccessResolver.parseAccessRules(rows);
        } catch (Exception e) {
            log.warn("Failed to load view access rules for {}: {}", viewId, e.getMessage());
            return List.of();
        }
    }

    private boolean canUserSeeView(String userId, Long viewId) {
        return mainTableViewAccessResolver.canUserSeeView(userId, loadAccessRules(viewId));
    }

    private long countVisibleViews(String userId, List<Long> viewIds) {
        if (viewIds == null || viewIds.isEmpty()) {
            return 0;
        }
        return viewIds.stream().filter(viewId -> canUserSeeView(userId, viewId)).count();
    }

    private void assertViewAccess(String userId, ViewDefinition view) {
        if (!mainTableViewAccessResolver.canUserSeeView(userId, view.accessRules())) {
            throw new FunctionUnitAccessComponent.FunctionUnitAccessDeniedException(
                    "Access denied for view: " + view.id());
        }
    }

    private void assertFuAccess(String userId, String functionUnitCode) {
        if (!functionUnitAccessComponent.canAccessFunctionUnit(userId, functionUnitCode)) {
            throw new FunctionUnitAccessComponent.FunctionUnitAccessDeniedException(
                    "Access denied for function unit: " + functionUnitCode);
        }
        if (!functionUnitAccessComponent.isFunctionUnitEnabled(functionUnitCode)) {
            throw new FunctionUnitAccessComponent.FunctionUnitDisabledException(
                    "Function unit is disabled: " + functionUnitCode);
        }
    }

    private List<Map<String, Object>> parseJsonList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseJsonMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String stringVal(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToolbarConfig(String filterConfigJson) {
        Map<String, Object> filter = parseJsonMap(filterConfigJson);
        Object toolbar = filter.get("toolbar");
        if (toolbar instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private boolean toolbarEnable(Map<String, Object> toolbar, String key, boolean defaultValue) {
        Object val = toolbar.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(val));
    }

    private record FkColumnMeta(
            Long refViewId,
            String refFunctionUnitCode,
            List<String> refPrimaryKeyFields) {}

    /** Structural FK on the owning table — used to hydrate {@code fk_display} columns. */
    private record FkSourceMeta(Long refTableId, List<String> refPrimaryKeyFields) {}

    private record SubMainFk(String fieldName, FkColumnMeta meta) {}

    private record LookupColumnMeta(
            Long tableId,
            List<String> searchFields,
            String selectedDisplayField) {}

    private record ViewFieldDef(
            String fieldName,
            String displayLabel,
            Integer columnWidth,
            Integer sortOrder,
            Boolean visible,
            Boolean systemField,
            String columnType,
            String lookupSourceField,
            String lookupDisplayField) {}

    private record ViewDefinition(
            Long id,
            String viewName,
            String functionUnitCode,
            List<Map<String, Object>> sortConfig,
            Map<String, Object> filterConfig,
            List<ViewFieldDef> fields,
            Long mainTableId,
            String tableType,
            List<String> subBindingKeys,
            boolean restrictToInvolvedUsers,
            List<AccessRule> accessRules) {}
}
