package com.portal.service.impl;

import com.platform.common.list.ListColumnMeta;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.jdbc.SubTableRowIdentity;
import com.platform.common.subtable.SubTableStoreKeys;
import com.portal.component.ComputedFieldRecalculator;
import com.portal.component.FunctionUnitAccessComponent;
import com.portal.component.MainTableViewAccessResolver;
import com.portal.component.MainTableViewAccessResolver.AccessRule;
import com.portal.component.MainTableViewInvolvementScope;
import com.portal.component.MainTableViewRowQueryComponent;
import com.portal.component.MainTableViewSubRowQueryComponent;
import com.portal.component.OwnerFieldComponent;
import com.portal.component.ProcessComponent;
import com.portal.component.RequestIdEnricher;
import com.portal.dto.MainTableViewImportResult;
import com.portal.dto.MainTableViewQueryRequest;
import com.portal.util.MainTableViewColumnSpec;
import com.portal.util.MainTableViewDerivedFilterSql;
import com.portal.util.MainTableViewDesignerFilterSql;
import com.portal.util.SqlFragment;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.dto.ProcessStartRequest;
import com.portal.dto.MainTableViewPortalDtos.FunctionUnitViewMenuItem;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewDataPage;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewDataRow;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewFieldColumn;
import com.portal.util.MainTableViewFkDisplaySupport;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewSummary;
import com.portal.entity.ProcessInstance;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.PortalMainTableViewService;
import com.portal.service.UserDisplayNameResolver;
import com.portal.util.PortalMainTableViewCsvUtils;
import com.portal.util.PortalMainTableViewDetailValues;
import com.portal.util.PortalMainTableViewNestedSubTables;
import com.portal.util.PortalMainTableViewNestedSubTables.NestedBinding;
import com.portal.util.PortalMainTableViewRowKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
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
    private final MainTableViewInvolvementScope involvementScope;
    private final MainTableViewRowQueryComponent rowQueryComponent;
    private final MainTableViewSubRowQueryComponent subRowQueryComponent;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessComponent processComponent;
    private final ComputedFieldRecalculator computedFieldRecalculator;
    private final UserDisplayNameResolver userDisplayNameResolver;

    /**
     * Lazy: re-derives the readonly Request ID after a CSV import edits main-table fields.
     * Field-injected (not a ctor arg) to keep the constructor arity stable for {@code new}-constructed
     * tests; null there simply skips stamping.
     */
    @Lazy
    @Autowired
    private RequestIdEnricher requestIdEnricher;

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
        } catch (DataAccessException e) {
            throw new IllegalStateException("Main table view menu query failed", e);
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

        Map<String, String> iconByFuCode = loadFunctionUnitIconSvgs(fuMetaByCode.keySet());

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
                    .iconSvg(iconByFuCode.get(code))
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
                                   v.main_table_id, v.detail_form_id, td.table_type,
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
                                .tableType(rs.getString("table_type"))
                                .enableExport(toolbarEnable(toolbar, "enableExport", true))
                                .enableImport(toolbarEnable(toolbar, "enableImport", true))
                                .detailFormId(rs.getObject("detail_form_id") != null
                                        ? rs.getLong("detail_form_id") : null)
                                .build();
                    },
                    functionUnitCode.trim());
            return summaries.stream()
                    .filter(summary -> canUserSeeView(userId, summary.id()))
                    .toList();
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "List published views failed for function unit " + functionUnitCode, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MainTableViewDataPage queryViewData(String userId, Long viewId,
                                               MainTableViewQueryRequest request) {
        ViewDefinition view = loadPublishedView(viewId, request.rowKey() != null);
        assertFuAccess(userId, view.functionUnitCode());
        assertViewAccess(userId, view);

        return isSubView(view)
                ? querySubViewPage(userId, view, request)
                : queryMainViewPage(userId, view, request);
    }

    /**
     * MAIN views page in the database: one process instance is one row, so the filters, the
     * search, the sort and the row visibility all become one predicate and the total comes from
     * counting it.
     */
    private MainTableViewDataPage queryMainViewPage(String userId, ViewDefinition view,
                                                    MainTableViewQueryRequest request) {
        MainTableViewRowQueryComponent.Page page = rowQueryComponent.query(
                mainViewQuery(userId, view, request, request.page(), request.size()));
        Map<String, FkSourceMeta> fkSource = loadFkSourceMeta(view.id());
        Map<String, String> ownerCache = ownerDisplayCacheForInstances(page.instances());
        List<MainTableViewDataRow> rows = page.instances().stream()
                .map(pi -> MainTableViewDataRow.builder()
                        .rowKey(pi.getId())
                        .processInstanceId(pi.getId())
                        .values(stripInternalKeys(projectInstanceRow(pi, view, fkSource, ownerCache)))
                        .build())
                .toList();
        return MainTableViewDataPage.builder()
                .columns(visibleColumns(view))
                .rows(rows)
                .total(page.total())
                .page(request.page())
                .size(request.size())
                .build();
    }

    /**
     * SUB views page in the database too, but on the expansion rather than on the instances: one
     * sub-table row is one row, so the total counts sub-table rows and the page slices them.
     */
    private MainTableViewDataPage querySubViewPage(String userId, ViewDefinition view,
                                                   MainTableViewQueryRequest request) {
        MainTableViewSubRowQueryComponent.Page page = subRowQueryComponent.query(
                subViewQuery(userId, view, request, request.page(), request.size()));
        Map<String, FkSourceMeta> fkSource = loadFkSourceMeta(view.id());
        Map<String, String> ownerCache = ownerDisplayCacheForSubRows(page.rows());
        boolean detailRow = request.rowKey() != null;
        return MainTableViewDataPage.builder()
                .columns(visibleColumns(view))
                .rows(page.rows().stream()
                        .map(row -> MainTableViewDataRow.builder()
                                .rowKey(row.instance().getId() + "|" + SubTableRowIdentity.identityOf(row.subRow()))
                                .processInstanceId(row.instance().getId())
                                .values(subRowValues(row, view, fkSource, ownerCache, detailRow))
                                .build())
                        .toList())
                .total(page.total())
                .page(request.page())
                .size(request.size())
                .build();
    }

    private MainTableViewRowQueryComponent.Query mainViewQuery(
            String userId, ViewDefinition view, MainTableViewQueryRequest request, int page, int size) {
        List<MainTableViewColumnSpec.FieldSource> fields = queryableFieldSources(view);
        MainTableViewColumnSpec.SqlSource source = MainTableViewColumnSpec.SqlSource.INSTANCE;
        return new MainTableViewRowQueryComponent.Query(
                view.functionUnitCode(),
                MainTableViewColumnSpec.sqlFor(fields, view.sortConfig()),
                designerAndDerived(view, fields, source, request),
                MainTableViewDerivedFilterSql.plainFilters(request.filters(), fields),
                request.sortField(),
                request.sortDirection(),
                request.search(),
                searchableFields(fields, source),
                involvementPredicate(userId, view),
                page,
                size);
    }

    private MainTableViewSubRowQueryComponent.Query subViewQuery(
            String userId, ViewDefinition view, MainTableViewQueryRequest request, int page, int size) {
        List<MainTableViewColumnSpec.FieldSource> fields = queryableFieldSources(view);
        MainTableViewColumnSpec.SqlSource source = MainTableViewColumnSpec.SqlSource.EXPANDED_SUB_ROW;
        return new MainTableViewSubRowQueryComponent.Query(
                view.id(),
                view.functionUnitCode(),
                view.subStoreKey(),
                MainTableViewColumnSpec.sqlFor(fields, view.sortConfig(), source, "pi.id, pi.row_identity"),
                designerAndDerived(view, fields, source, request),
                MainTableViewDerivedFilterSql.plainFilters(request.filters(), fields),
                request.sortField(),
                request.sortDirection(),
                request.search(),
                searchableFields(fields, source),
                involvementPredicate(userId, view),
                page,
                size);
    }

    private SqlFragment designerAndDerived(ViewDefinition view,
                                           List<MainTableViewColumnSpec.FieldSource> fields,
                                           MainTableViewColumnSpec.SqlSource source,
                                           MainTableViewQueryRequest request) {
        return designerFilter(view, fields, source)
                .and(MainTableViewDerivedFilterSql.whereClause(request.filters(), fields, source))
                .and(PortalMainTableViewRowKeys.exactMatch(
                        request.rowKey(), !source.instanceIsTheRow()));
    }

    /**
     * The designer's filter, compiled once per query. It used to be applied to each projected row
     * in memory; now that the page is drawn in SQL it has to be part of the same predicate, or a
     * view narrowed to a subset would page through everything and drop rows from each page.
     */
    private SqlFragment designerFilter(ViewDefinition view,
                                       List<MainTableViewColumnSpec.FieldSource> fields,
                                       MainTableViewColumnSpec.SqlSource source) {
        List<Object> params = new ArrayList<>();
        String sql = new MainTableViewDesignerFilterSql(
                MainTableViewColumnSpec.designerRefFor(fields, source),
                "View " + view.id() + " '" + view.viewName() + "'")
                .whereClause(view.filterConfig(), params);
        return new SqlFragment(sql, params);
    }

    /**
     * The keyword search covers the columns the query can compare as text — the same ones whose
     * values a user sees, minus display-mapped lookup / FK columns (those convert via
     * {@link MainTableViewDerivedFilterSql}, not ILIKE on the stored key).
     */
    private List<String> searchableFields(List<MainTableViewColumnSpec.FieldSource> fields,
                                          MainTableViewColumnSpec.SqlSource source) {
        return MainTableViewColumnSpec.columnsFor(fields, source).stream()
                .filter(ListColumnMeta::filterable)
                .filter(column -> fields.stream()
                        .noneMatch(f -> f.fieldName().equals(column.field())
                                && MainTableViewColumnSpec.isDisplayMapped(f)))
                .map(ListColumnMeta::field)
                .toList();
    }

    private MainTableViewInvolvementScope.Predicate involvementPredicate(String userId, ViewDefinition view) {
        if (!view.restrictToInvolvedUsers() || mainTableViewAccessResolver.isSystemAdministrator(userId)) {
            return null;
        }
        return involvementScope.predicateFor(userId, view.functionUnitCode());
    }

    private boolean isSubView(ViewDefinition view) {
        return "SUB".equalsIgnoreCase(view.tableType());
    }

    private MainTableViewColumnSpec.SqlSource sqlSourceOf(ViewDefinition view) {
        return isSubView(view)
                ? MainTableViewColumnSpec.SqlSource.EXPANDED_SUB_ROW
                : MainTableViewColumnSpec.SqlSource.INSTANCE;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportViewCsv(String userId, Long viewId, int maxRows,
                                MainTableViewQueryRequest request) {
        ViewDefinition view = loadPublishedView(viewId);
        assertFuAccess(userId, view.functionUnitCode());
        assertViewAccess(userId, view);

        List<MainTableViewFieldColumn> columns = visibleColumns(view);
        int limit = Math.min(Math.max(maxRows, 1), 10000);
        List<Map<String, Object>> slice = exportRows(userId, view, request, limit);

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
                computedFieldRecalculator.recalculate(pi.getFunctionUnitCode(), vars);
                // An import may edit a field that feeds the Request ID; re-derive it so the stored
                // identifier keeps matching the row it names.
                if (requestIdEnricher != null) {
                    requestIdEnricher.stampRequestId(pi.getFunctionUnitCode(), vars);
                }
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

    /** Export reads through the same path as the list, so a CSV matches what the user just saw. */
    private List<Map<String, Object>> exportRows(String userId, ViewDefinition view,
                                                 MainTableViewQueryRequest request, int limit) {
        Map<String, FkSourceMeta> fkSource = loadFkSourceMeta(view.id());
        if (isSubView(view)) {
            var page = subRowQueryComponent.query(subViewQuery(userId, view, request, 0, limit));
            Map<String, String> ownerCache = ownerDisplayCacheForSubRows(page.rows());
            return page.rows().stream()
                    .map(row -> projectSubRow(row, view, fkSource, ownerCache))
                    .toList();
        }
        var page = rowQueryComponent.query(mainViewQuery(userId, view, request, 0, limit));
        Map<String, String> ownerCache = ownerDisplayCacheForInstances(page.instances());
        return page.instances().stream()
                .map(pi -> projectInstanceRow(pi, view, fkSource, ownerCache))
                .toList();
    }

    /**
     * One SUB view row: the sub-table row's own members, with the owning instance's variables
     * available for the FK labels that resolve against the parent.
     */
    private Map<String, Object> projectSubRow(MainTableViewSubRowQueryComponent.Row row,
                                              ViewDefinition view,
                                              Map<String, FkSourceMeta> fkSource,
                                              Map<String, String> ownerCache) {
        Map<String, Object> vars = row.instance().getVariables() != null
                ? row.instance().getVariables()
                : Map.<String, Object>of();
        Map<String, Object> mainVars = stripInternalKeys(vars);
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("_processInstanceId", row.instance().getId());
        for (ViewFieldDef field : view.fields()) {
            if (!Boolean.TRUE.equals(field.visible())) {
                continue;
            }
            if (Boolean.TRUE.equals(field.systemField())) {
                projected.put(field.fieldName(), systemFieldValue(row.instance(), field.fieldName()));
            } else {
                projected.put(field.fieldName(),
                        resolveProjectedFieldValue(row.subRow(), field, mainVars, fkSource, ownerCache));
            }
        }
        return projected;
    }

    private Map<String, Object> projectInstanceRow(ProcessInstance pi, ViewDefinition view,
                                                   Map<String, FkSourceMeta> fkSource,
                                                   Map<String, String> ownerCache) {
        Map<String, Object> vars = pi.getVariables() != null ? pi.getVariables() : Map.<String, Object>of();
        Map<String, Object> mainVars = stripInternalKeys(vars);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_processInstanceId", pi.getId());

        for (ViewFieldDef field : view.fields()) {
            if (!Boolean.TRUE.equals(field.visible())) {
                continue;
            }
            if (Boolean.TRUE.equals(field.systemField())) {
                row.put(field.fieldName(), systemFieldValue(pi, field.fieldName()));
            } else {
                row.put(field.fieldName(), resolveProjectedFieldValue(vars, field, mainVars, fkSource, ownerCache));
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
            Map<String, FkSourceMeta> fkSourceMeta,
            Map<String, String> ownerCache) {
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
        Object raw = source.get(field.fieldName());
        Object liveUser = liveResolveUserPrefixed(raw, ownerCache);
        if (liveUser != null) {
            return liveUser;
        }
        if (raw instanceof String s && s.startsWith("group:")) {
            Object display = source.get(field.fieldName() + "__display");
            return display instanceof String d && !d.isBlank() ? display : raw;
        }
        return raw;
    }

    private Map<String, String> ownerDisplayCacheForInstances(List<ProcessInstance> instances) {
        Set<String> keys = new LinkedHashSet<>();
        for (ProcessInstance pi : instances) {
            collectOwnerUserKeys(pi, keys);
        }
        return userDisplayNameResolver.resolveBatch(keys);
    }

    private Map<String, String> ownerDisplayCacheForSubRows(
            List<MainTableViewSubRowQueryComponent.Row> rows) {
        Set<String> keys = new LinkedHashSet<>();
        for (MainTableViewSubRowQueryComponent.Row row : rows) {
            collectOwnerUserKeys(row.instance(), keys);
            collectUserPrefixedKeys(row.subRow(), keys);
        }
        return userDisplayNameResolver.resolveBatch(keys);
    }

    private void collectOwnerUserKeys(ProcessInstance pi, Set<String> keys) {
        collectUserPrefixedKeys(pi.getVariables(), keys);
    }

    private static void collectUserPrefixedKeys(Map<String, Object> source, Set<String> keys) {
        if (source == null) {
            return;
        }
        for (Object value : source.values()) {
            if (value instanceof String s) {
                keys.addAll(OwnerFieldComponent.parseStoredUserIds(s));
            }
        }
    }

    private String liveResolveUserPrefixed(Object raw, Map<String, String> ownerCache) {
        if (!(raw instanceof String s)) {
            return null;
        }
        List<String> ids = OwnerFieldComponent.parseStoredUserIds(s);
        if (ids.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            String live = userDisplayNameResolver.resolveCached(id, ownerCache);
            names.add(live != null && !live.isBlank() ? live : id);
        }
        return String.join(UserDisplayNameResolver.MULTI_ASSIGNEE_DISPLAY_SEPARATOR, names);
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
        ColumnMetaBundle bundle = new ColumnMetaBundle(
                fkMeta, fkSourceMeta, lookupMeta, declaredCapabilities(view));
        return view.fields().stream()
                .filter(f -> Boolean.TRUE.equals(f.visible()))
                .sorted(Comparator.comparingInt(f -> f.sortOrder() != null ? f.sortOrder() : 0))
                .map(f -> toFieldColumn(f, bundle))
                .toList();
    }

    private MainTableViewFieldColumn toFieldColumn(ViewFieldDef f, ColumnMetaBundle meta) {
        String type = f.columnType() != null ? f.columnType() : "";
        boolean lookupDisplay = "lookup_display".equalsIgnoreCase(type);
        boolean fkDisplay = "fk_display".equalsIgnoreCase(type);
        boolean derived = lookupDisplay || fkDisplay;
        String sourceField = derived && f.lookupSourceField() != null ? f.lookupSourceField() : f.fieldName();
        FkColumnMeta fk = derived ? null : meta.fk().get(f.fieldName());
        FkSourceMeta fkSrc = fkDisplay ? meta.fkSource().get(sourceField) : null;
        LookupColumnMeta lookup = lookupDisplay || !derived ? meta.lookup().get(sourceField) : null;
        Long lookupTableId = lookup != null ? lookup.tableId() : null;
        ListColumnMeta capability = meta.capabilities().get(f.fieldName());
        if (capability == null) {
            throw new IllegalStateException("View column was not declared: " + f.fieldName());
        }
        MainTableViewFieldColumn.MainTableViewFieldColumnBuilder column = MainTableViewFieldColumn.builder()
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
                .columnType(lookupDisplay ? "lookup_display" : (fkDisplay ? "fk_display" : "field"))
                .lookupSourceField(derived ? f.lookupSourceField() : (lookup != null ? sourceField : null))
                .lookupDisplayField(derived ? f.lookupDisplayField() : null)
                .lookupSelectedDisplayField(lookup != null ? lookup.selectedDisplayField() : null)
                .lookupSearchFields(lookup != null ? lookup.searchFields() : null)
                .fkRefTableId(fkSrc != null ? fkSrc.refTableId() : null);
        return MainTableViewFieldColumn.applyListCapabilities(column, capability).build();
    }

    /** What the header may offer per column, given where this view's rows live. */
    private Map<String, ListColumnMeta> declaredCapabilities(ViewDefinition view) {
        List<MainTableViewColumnSpec.FieldSource> fields = queryableFieldSources(view);
        List<ListColumnMeta> declared = MainTableViewColumnSpec.columnsFor(fields, sqlSourceOf(view));
        Map<String, ListColumnMeta> byField = new LinkedHashMap<>();
        declared.forEach(column -> byField.put(column.field(), column));
        return byField;
    }

    /**
     * The view's visible fields paired with the data type their table declares, which is what
     * decides whether a column can be compared as a number, a date or text.
     */
    private List<MainTableViewColumnSpec.FieldSource> queryableFieldSources(ViewDefinition view) {
        Map<String, String> dataTypes = fieldDataTypes(view);
        Map<String, LookupColumnMeta> lookupMeta = loadLookupColumnMeta(view.mainTableId());
        Map<String, FkSourceMeta> fkMeta = fkSourceMetaFor(view);
        return view.fields().stream()
                .filter(f -> Boolean.TRUE.equals(f.visible()))
                .sorted(Comparator.comparingInt(f -> f.sortOrder() != null ? f.sortOrder() : 0))
                .map(f -> toFieldSource(f, dataTypes, lookupMeta, fkMeta))
                .toList();
    }

    private MainTableViewColumnSpec.FieldSource toFieldSource(
            ViewFieldDef field,
            Map<String, String> dataTypes,
            Map<String, LookupColumnMeta> lookupMeta,
            Map<String, FkSourceMeta> fkMeta) {
        boolean lookupDisplay = "lookup_display".equalsIgnoreCase(field.columnType());
        boolean fkDisplay = "fk_display".equalsIgnoreCase(field.columnType());
        String sourceField = (lookupDisplay || fkDisplay) ? field.lookupSourceField() : null;
        String displayField = (lookupDisplay || fkDisplay) ? field.lookupDisplayField() : null;
        Long lookupTableId = null;
        List<String> fkPks = List.of();
        if (lookupDisplay && sourceField != null) {
            LookupColumnMeta lookup = lookupMeta.get(sourceField);
            if (lookup != null) {
                lookupTableId = lookup.tableId();
                if (displayField == null || displayField.isBlank()) {
                    displayField = lookup.selectedDisplayField();
                }
            }
        }
        if (fkDisplay && sourceField != null) {
            FkSourceMeta fk = fkMeta.get(sourceField);
            if (fk != null && fk.refPrimaryKeyFields() != null) {
                fkPks = fk.refPrimaryKeyFields();
            }
        }
        return new MainTableViewColumnSpec.FieldSource(
                field.fieldName(),
                field.displayLabel(),
                Boolean.TRUE.equals(field.systemField()),
                field.columnType(),
                dataTypes.get(field.fieldName()),
                sourceField,
                displayField,
                lookupTableId,
                fkPks);
    }

    private Map<String, FkSourceMeta> fkSourceMetaFor(ViewDefinition view) {
        Map<String, FkSourceMeta> fkSource = new LinkedHashMap<>(loadFkSourceMeta(view.id()));
        if (isSubView(view) && view.mainTableId() != null) {
            loadSubMainFkMeta(view.mainTableId()).ifPresent(m -> fkSource.putIfAbsent(
                    m.fieldName(),
                    new FkSourceMeta(null, m.meta().refPrimaryKeyFields())));
        }
        return fkSource;
    }

    /**
     * Types for designed view columns. The bound table wins; other tables in the same Function
     * Unit fill names the bound table does not declare so a SUB view can still type a parent MAIN
     * column it shows. A name that exists on none of them is compared as TEXT — we do
     * not invent DATE/USER from the label.
     */
    private Map<String, String> fieldDataTypes(ViewDefinition view) {
        Map<String, String> byField = new LinkedHashMap<>(fieldDataTypes(view.mainTableId()));
        if (view.functionUnitCode() == null || view.functionUnitCode().isBlank()) {
            return byField;
        }
        jdbcTemplate.queryForList("""
                        SELECT fd.field_name, fd.data_type
                        FROM dw_field_definitions fd
                        INNER JOIN dw_table_definitions td ON td.id = fd.table_id
                        INNER JOIN dw_function_units fu ON fu.id = td.function_unit_id
                        WHERE fu.code = ?
                        ORDER BY CASE WHEN UPPER(td.table_type) = 'MAIN' THEN 0 ELSE 1 END, td.id
                        """,
                view.functionUnitCode())
                .forEach(row -> byField.putIfAbsent(
                        stringVal(row.get("field_name")), stringVal(row.get("data_type"))));
        return byField;
    }

    private Map<String, String> fieldDataTypes(Long mainTableId) {
        if (mainTableId == null) {
            return Map.of();
        }
        Map<String, String> byField = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                        "SELECT field_name, data_type FROM dw_field_definitions WHERE table_id = ?",
                        mainTableId)
                .forEach(row -> byField.put(stringVal(row.get("field_name")), stringVal(row.get("data_type"))));
        return byField;
    }

    private record ColumnMetaBundle(
            Map<String, FkColumnMeta> fk,
            Map<String, FkSourceMeta> fkSource,
            Map<String, LookupColumnMeta> lookup,
            Map<String, ListColumnMeta> capabilities) {
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

    /**
     * Detail (rowKey) needs stored members the list omitted, plus the nested store the DETAIL
     * form's SUB grids read. List pages omit both. {@link #stripInternalKeys} would drop
     * {@code __subTables__}, so that map is attached after.
     */
    private Map<String, Object> subRowValues(
            MainTableViewSubRowQueryComponent.Row row,
            ViewDefinition view,
            Map<String, FkSourceMeta> fkSource,
            Map<String, String> ownerCache,
            boolean detailRow) {
        Map<String, Object> projected = projectSubRow(row, view, fkSource, ownerCache);
        if (detailRow) {
            projected = PortalMainTableViewDetailValues.overlayStoredMembers(projected, row.subRow());
        }
        Map<String, Object> values = stripInternalKeys(projected);
        if (!detailRow) {
            return values;
        }
        Map<String, Object> nested = PortalMainTableViewNestedSubTables.forParentRow(
                row.subRow(),
                row.instance().getVariables(),
                view.nestedSubBindings(),
                // The parent's identity columns come from Table Design, not from a list of likely
                // names: this view's parent may be keyed by `correspondence_id`, `case_number`, or
                // anything else the designer chose.
                designerPrimaryKeyFields(view.mainTableId()));
        if (!nested.isEmpty()) {
            values.put(PortalMainTableViewNestedSubTables.STORE_KEY, nested);
        }
        return values;
    }

    private ViewDefinition loadPublishedView(Long viewId) {
        return loadPublishedView(viewId, false);
    }

    private ViewDefinition loadPublishedView(Long viewId, boolean detailRow) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT v.id, v.view_name, v.sort_config::text AS sort_config,
                       v.filter_config::text AS filter_config, fu.code AS fu_code,
                       v.main_table_id, td.table_type, td.table_name,
                       v.restrict_to_involved_users, v.detail_form_id
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
        // SUB-table data is nested under variables.__subTables__ under one canonical key per table
        // (see SubTableStoreKeys). A view's main_table_id is a foreign key into dw_table_definitions,
        // so the table is always a designer table and the key is always the dw: namespace.
        String subStoreKey = null;
        if ("SUB".equalsIgnoreCase(tableType)) {
            subStoreKey = SubTableStoreKeys.dwKey(row.get("table_name"));
            if (subStoreKey == null) {
                throw new IllegalStateException("Sub-table view " + viewId + " resolves to table "
                        + mainTableId + ", which has no name; its __subTables__ key cannot be derived.");
            }
        }

        Long detailFormId = row.get("detail_form_id") != null
                ? ((Number) row.get("detail_form_id")).longValue() : null;

        return new ViewDefinition(
                viewId,
                stringVal(row.get("view_name")),
                stringVal(row.get("fu_code")),
                parseJsonList(stringVal(row.get("sort_config"))),
                parseJsonMap(stringVal(row.get("filter_config"))),
                fields,
                mainTableId,
                tableType,
                subStoreKey,
                Boolean.TRUE.equals(row.get("restrict_to_involved_users")),
                loadAccessRules(viewId),
                loadNestedSubBindings(detailRow ? detailFormId : null));
    }

    /**
     * A table's designer primary key columns, in configured order. Empty when the table declares
     * none — callers then rely on the platform-generated row key, never on a guessed column name.
     */
    private List<String> designerPrimaryKeyFields(Long tableId) {
        if (tableId == null) {
            return List.of();
        }
        try {
            return jdbcTemplate.queryForList("""
                    SELECT field_name
                    FROM dw_field_definitions
                    WHERE table_id = ? AND COALESCE(is_primary_key, false) = true
                    ORDER BY sort_order NULLS LAST, id
                    """, String.class, tableId);
        } catch (RuntimeException ex) {
            log.warn("Could not resolve primary key for table {}: {}", tableId, ex.getMessage());
            return List.of();
        }
    }

    private List<NestedBinding> loadNestedSubBindings(Long detailFormId) {
        if (detailFormId == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT td.table_name, b.foreign_key_field
                FROM dw_form_table_bindings b
                INNER JOIN dw_table_definitions td ON td.id = b.table_id
                WHERE b.form_id = ?
                  AND b.binding_type = 'SUB'
                """,
                (rs, n) -> new NestedBinding(
                        rs.getString("table_name"),
                        rs.getString("foreign_key_field")),
                detailFormId);
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

    /**
     * Load at most one {@code svg_content} per Function Unit after view rows are grouped.
     * Joining icons onto every published view row would repeat TEXT per view.
     */
    private Map<String, String> loadFunctionUnitIconSvgs(Set<String> fuCodes) {
        if (fuCodes.isEmpty()) {
            return Map.of();
        }
        try {
            String placeholders = String.join(",", Collections.nCopies(fuCodes.size(), "?"));
            List<Map<String, Object>> iconRows = jdbcTemplate.queryForList(
                    "SELECT fu.code AS fu_code, ic.svg_content AS icon_svg"
                            + " FROM dw_function_units fu"
                            + " LEFT JOIN dw_icons ic ON ic.id = fu.icon_id"
                            + " WHERE fu.code IN (" + placeholders + ")",
                    fuCodes.toArray());
            Map<String, String> icons = new HashMap<>(iconRows.size());
            for (Map<String, Object> row : iconRows) {
                String code = stringVal(row.get("fu_code"));
                if (code != null) {
                    icons.put(code, stringVal(row.get("icon_svg")));
                }
            }
            return icons;
        } catch (DataAccessException e) {
            // FALLBACK(ux): menu remains usable; placeholder icon is used when SVG is absent.
            log.warn("Main table view menu icon query failed: {}", e.getMessage());
            return Map.of();
        }
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
            String subStoreKey,
            boolean restrictToInvolvedUsers,
            List<AccessRule> accessRules,
            List<NestedBinding> nestedSubBindings) {}
}
