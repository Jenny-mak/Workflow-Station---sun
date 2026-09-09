package com.portal.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.dto.ChangeHistoryContext;
import com.portal.dto.ProcessFormData;
import com.portal.dto.SubTableBindingData;
import com.portal.dto.SubTableChange;
import com.portal.entity.ProcessInstance;
import com.portal.exception.PortalException;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.UserDisplayNameResolver;
import com.portal.util.SystemAuditFieldFiller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import com.platform.common.util.ApiResponseBodyUnwrap;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process Form component.
 * Loads Process Form data, handles submit updates, and validates existence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessFormComponent {

    private final ProcessInstanceRepository processInstanceRepository;
    private final ChangeHistoryComponent changeHistoryComponent;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager platformTransactionManager;

    @Lazy
    @Autowired
    private ChangeHistorySubmissionFilter changeHistorySubmissionFilter;
    private ChangeHistorySubmissionFilter changeHistorySubmissionFilter() {
        ChangeHistorySubmissionFilter filter = changeHistorySubmissionFilter;
        if (filter == null) {
            filter = new ChangeHistorySubmissionFilter(jdbcTemplate, objectMapper);
            changeHistorySubmissionFilter = filter;
        }
        return filter;
    }
    /** Lazy: server-side formula columns; null in {@code new}-constructed tests skips recalculation. */
    @Lazy
    @Autowired
    private ComputedFieldRecalculator computedFieldRecalculator;

    /** Lazy: computes the readonly Request ID value; injected as a field to keep ctor arity stable for tests. */
    @Lazy
    @Autowired
    private RequestIdEnricher requestIdEnricher;

    private RequestIdEnricher requestIdEnricher() {
        RequestIdEnricher r = requestIdEnricher;
        if (r == null) {
            r = new RequestIdEnricher(jdbcTemplate, objectMapper, processInstanceRepository);
            requestIdEnricher = r;
        }
        return r;
    }

    /** Lazy: resolves updated_by display names for system audit fields (null in `new`-constructed tests). */
    @Lazy
    @Autowired
    private UserDisplayNameResolver userDisplayNameResolver;

    /**
     * Lazy: Owner-field validation / display re-resolution / default fill on process-form
     * submits (null in `new`-constructed tests, which do not exercise owner fields).
     */
    @Lazy
    @Autowired
    private OwnerFieldComponent ownerFieldComponent;

    @Lazy
    @Autowired
    private MiOuterStepResolver miOuterStepResolver;

    /** Display name for audit fields; falls back to the raw user id when the resolver is unavailable. */
    private String resolveAuditUserDisplay(String userId) {
        UserDisplayNameResolver resolver = userDisplayNameResolver;
        if (resolver == null) {
            return userId;
        }
        try {
            String display = resolver.resolve(userId);
            return display != null && !display.isBlank() ? display : userId;
        } catch (RuntimeException ex) {
            log.debug("resolveAuditUserDisplay failed for {}: {}", userId, ex.getMessage());
            return userId;
        }
    }

    private void recalculateComputedFields(String functionUnitCode, Map<String, Object> variables) {
        ComputedFieldRecalculator recalculator = computedFieldRecalculator;
        if (recalculator == null || functionUnitCode == null || functionUnitCode.isBlank() || variables == null) {
            return;
        }
        recalculator.recalculate(functionUnitCode, variables);
    }

    private volatile TransactionTemplate processFormWriteTxTemplate;

    private TransactionTemplate processFormWriteTx() {
        TransactionTemplate t = processFormWriteTxTemplate;
        if (t == null) {
            synchronized (this) {
                t = processFormWriteTxTemplate;
                if (t == null) {
                    t = new TransactionTemplate(platformTransactionManager);
                    processFormWriteTxTemplate = t;
                }
            }
        }
        return t;
    }

    /** Lazy: hydrates {@code up_process_instance} for engine-only starts (email monitor). */
    @Lazy
    @Autowired
    private ProcessInstanceHydrationComponent processInstanceHydration;

    /** Lazy: fetches deployed BPMN XML to derive MI assignment contracts (keeps ctor arity stable for tests). */
    @Lazy
    @Autowired
    private com.portal.client.WorkflowEngineClient workflowEngineClient;

    private ProcessInstance requireProcessInstance(String processInstanceId) {
        ProcessInstanceHydrationComponent hydration = processInstanceHydration;
        if (hydration != null) {
            return hydration.requireProcessInstance(processInstanceId);
        }
        return processInstanceRepository.findById(processInstanceId)
                .orElseThrow(() -> new PortalException("404",
                        "Process instance not found: " + processInstanceId));
    }

    @Value("${admin-center.url:http://localhost:8090}")
    private String adminCenterUrl;

    private static final String RETURN_TO_REQUESTER = "RETURN_TO_REQUESTER";

    /**
     * Returns Process Form layout and current process variable values.
     *
     * @param processInstanceId process instance ID
     * @return ProcessFormData DTO
     */
    public ProcessFormData getProcessFormData(String processInstanceId) {
        log.debug("Getting process form data for process instance: {}", processInstanceId);

        ProcessInstance processInstance = requireProcessInstance(processInstanceId);

        Map<String, Object> variables = processInstance.getVariables() != null
                ? processInstance.getVariables()
                : Collections.emptyMap();

        String processDefinitionKey = processInstance.getProcessDefinitionKey();

        // Retrieve the PROCESS form definition for this function unit
        Map<String, Object> formDefinition = fetchProcessFormDefinition(processDefinitionKey);

        Map<String, Object> configJson = Collections.emptyMap();
        String formName = "Process Form";
        List<SubTableBindingData> subTableBindings = Collections.emptyList();

        if (formDefinition != null) {
            configJson = extractMapField(formDefinition, "configJson");
            formName = formDefinition.get("name") != null
                    ? (String) formDefinition.get("name")
                    : "Process Form";
            subTableBindings = extractSubTableBindings(formDefinition);
            attachMiAssignmentConfigs(subTableBindings, processDefinitionKey);
        }

        boolean editable = RETURN_TO_REQUESTER.equals(processInstance.getStatus());

        // Readonly Request ID synthetic field: compute from the main-table config + variables.
        Map<String, Object> fieldValues = new HashMap<>(variables);
        if (ownerFieldComponent != null) {
            ownerFieldComponent.projectForRead(
                    processInstance.getFunctionUnitCode(),
                    new OwnerFieldComponent.OwnerWriteContext(
                            processInstance.getStartUserId(),
                            processInstance.getStartUserId(),
                            processInstance.getCurrentAssignee(),
                            processInstance.getCandidateUsers(),
                            null),
                    fieldValues);
        }
        String requestId = requestIdEnricher().buildRequestId(processInstance.getFunctionUnitCode(), variables);
        if (requestId != null) {
            fieldValues.put(RequestIdEnricher.REQUEST_ID_FIELD, requestId);
        }

        return ProcessFormData.builder()
                .processInstanceId(processInstanceId)
                .formName(formName)
                .formType("PROCESS")
                .configJson(configJson)
                .fieldValues(fieldValues)
                .subTableBindings(subTableBindings)
                .editable(editable)
                .processState(processInstance.getStatus())
                .requestIdConfig(buildRequestIdConfigMap(processInstance.getFunctionUnitCode()))
                .build();
    }

    /** Serialize the main-table Request ID config so the portal can recompute the field live. */
    Map<String, Object> buildRequestIdConfigMap(String functionUnitCode) {
        RequestIdEnricher.RequestIdConfigView cfg = requestIdEnricher().resolveConfigView(functionUnitCode);
        if (cfg == null) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        map.put("fieldNames", cfg.fieldNames());
        map.put("separator", cfg.separator());
        return map;
    }

    /**
     * Submits Process Form updates (only in Return_To_Requester state).
     *
     * @param processInstanceId process instance ID
     * @param userId            acting user ID
     * @param formData          form payload
     */
    public void submitProcessFormUpdate(String processInstanceId, String userId, Map<String, Object> formData) {
        log.info("Submitting process form update for process: {}, user: {}", processInstanceId, userId);

        ProcessInstance gate = requireProcessInstance(processInstanceId);

        if (!userId.equals(gate.getStartUserId())) {
            log.warn("User {} attempted to update process form for process {} owned by {}", userId, processInstanceId, gate.getStartUserId());
            throw new PortalException("403", "Only the process initiator can update the process form");
        }

        if (!RETURN_TO_REQUESTER.equals(gate.getStatus())) {
            throw new PortalException("403", "Process form can only be updated in Return_To_Requester state. Current state: " + gate.getStatus());
        }

        Map<String, Object> userChanges = changeHistorySubmissionFilter().filterProcessSubmission(
            gate.getFunctionUnitCode(), formData, formData);

        AtomicReference<Map<String, Object>> oldValuesRef = new AtomicReference<>();

        processFormWriteTx().executeWithoutResult(status -> {
            ProcessInstance processInstance = requireProcessInstance(processInstanceId);

            Map<String, Object> oldValues = processInstance.getVariables() != null
                    ? new HashMap<>(processInstance.getVariables())
                    : new HashMap<>();

            oldValuesRef.set(new HashMap<>(oldValues));

            Map<String, Object> updatedVariables = new HashMap<>(oldValues);
            Map<String, Object> inbound = formData != null ? new HashMap<>(formData) : new HashMap<>();
            // Drop forged audit keys so putAll cannot wipe created_* from insert.
            SystemAuditFieldFiller.stripClientAuditKeys(inbound);
            updatedVariables.putAll(inbound);
            // Owner fields: Creator pins startUserId; Current Assignee follows snapshot.
            if (ownerFieldComponent != null) {
                ownerFieldComponent.applyOnSubmit(
                        processInstance.getFunctionUnitCode(),
                        new OwnerFieldComponent.OwnerWriteContext(
                                userId,
                                processInstance.getStartUserId(),
                                processInstance.getCurrentAssignee(),
                                processInstance.getCandidateUsers(),
                                oldValues,
                                ownerMiLookup(processInstance)),
                        updatedVariables);
            }
            // System audit fields: refresh updated_at/updated_by at real update
            // (platform-managed; not gated on Form Design). created_* preserved from insert.
            SystemAuditFieldFiller.fillOnUpdate(updatedVariables, resolveAuditUserDisplay(userId));
            recalculateComputedFields(processInstance.getFunctionUnitCode(), updatedVariables);
            // Request ID is platform-derived: recompute it here too, so a resubmit that edits a
            // contributing field cannot persist a stale or client-supplied identifier.
            requestIdEnricher().stampRequestId(processInstance.getFunctionUnitCode(), updatedVariables);
            processInstance.setVariables(updatedVariables);
            processInstanceRepository.save(processInstance);

            log.info("Process variables updated for process: {}", processInstanceId);
        });

        Map<String, Object> snapshotOldValues = oldValuesRef.get();
        if (snapshotOldValues == null) {
            snapshotOldValues = Collections.emptyMap();
        }

        ChangeHistoryContext context = ChangeHistoryContext.builder()
                .processInstanceId(processInstanceId)
                .taskInstanceId(null)
                .stageId(RETURN_TO_REQUESTER)
                .userId(userId)
                .build();

        try {
            changeHistoryComponent.recordFieldChanges(context, snapshotOldValues, userChanges);
                    Object filteredOldSubTables = changeHistorySubmissionFilter().filterProcessSubTableBaseline(
                        gate.getFunctionUnitCode(), snapshotOldValues.get("__subTables__"));
            recordSubTableChangeHistory(context,
                        filteredOldSubTables,
                    userChanges.get("__subTables__"));
        } catch (RuntimeException ex) {
            log.warn("process form change-history skipped for {}: {}", processInstanceId, ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void recordSubTableChangeHistory(ChangeHistoryContext context,
                                              Object oldSubTablesObj,
                                              Object newSubTablesObj) {
        if (newSubTablesObj == null) {
            return;
        }
        try {
            Map<String, List<Map<String, Object>>> oldRowsByTable =
                    ChangeHistoryComponent.normalizeSubTableRowsByHistoryName(oldSubTablesObj,
                            changeHistoryComponent::designerPrimaryKeyFieldsForSliceKey);
            Map<String, List<Map<String, Object>>> newRowsByTable =
                    ChangeHistoryComponent.normalizeSubTableRowsByHistoryName(newSubTablesObj,
                            changeHistoryComponent::designerPrimaryKeyFieldsForSliceKey);
            for (Map.Entry<String, List<Map<String, Object>>> subTableEntry : newRowsByTable.entrySet()) {
                String subTableKey = subTableEntry.getKey();
                List<Map<String, Object>> newRows = subTableEntry.getValue();
                List<Map<String, Object>> oldRows = oldRowsByTable.getOrDefault(subTableKey, Collections.emptyList());
                // Pair rows by the identity this table declares, resolved per slice from Table
                // Design — never by assuming a column name means "identity".
                List<SubTableChange> changes = SubTableChangeHistoryDiff.compute(oldRows, newRows,
                        changeHistoryComponent.designerPrimaryKeyFieldsForSliceKey(subTableKey));
                if (!changes.isEmpty()) {
                    changeHistoryComponent.recordSubTableChanges(
                            context, subTableKey, changes);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to record sub-table changes for process {}: {}",
                    context.getProcessInstanceId(), e.getMessage());
        }
    }

    /**
     * Validates that the FunctionUnit has a PROCESS form.
     *
     * @param functionUnitId function unit ID
     * @throws PortalException HTTP 400 when no PROCESS form exists
     */
    public void validateProcessFormExists(String functionUnitId) {
        log.debug("Validating PROCESS form exists for function unit: {}", functionUnitId);

        boolean exists = checkProcessFormExists(functionUnitId);
        if (!exists) {
            throw new PortalException("400", "PROCESS form not found for function unit: " + functionUnitId
                    + ". A PROCESS form must be configured before starting a process.");
        }
    }

    /**
     * Whether the process is in Return_To_Requester state.
     */
    public boolean isInReturnToRequesterState(String processInstanceId) {
        return RETURN_TO_REQUESTER.equals(requireProcessInstance(processInstanceId).getStatus());
    }

    // ==================== Private Helper Methods ====================

    /**
     * Resolves PROCESS form definition: prefer shared {@code dw_*} tables with developer-workstation;
     * otherwise parse admin-center {@code GET /function-units/{id}/contents?type=FORM} package snapshot (includes formType).
     * <p>
     * Legacy code incorrectly called non-existent {@code /function-units/.../forms?formType=PROCESS}, causing 4xx/5xx.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchProcessFormDefinition(String processDefinitionKey) {
        try {
            Map<String, Object> fromDw = fetchProcessFormFromLocalDw(processDefinitionKey);
            if (fromDw != null) {
                log.debug("Resolved PROCESS form for {} from local dw_form_definitions", processDefinitionKey);
                return fromDw;
            }
            Map<String, Object> fromAdmin = fetchProcessFormFromAdminCenter(processDefinitionKey);
            if (fromAdmin != null) {
                log.debug("Resolved PROCESS form for {} from admin-center contents", processDefinitionKey);
                return fromAdmin;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch PROCESS form definition for {}: {}", processDefinitionKey, e.getMessage());
        }
        return null;
    }

    private Map<String, Object> fetchProcessFormFromLocalDw(String functionUnitCode) {
        if (functionUnitCode == null || functionUnitCode.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    """
                            SELECT fd.id AS form_id, fd.form_name, fd.config_json::text AS config_json
                            FROM dw_form_definitions fd
                            INNER JOIN dw_function_units fu ON fu.id = fd.function_unit_id
                            WHERE fu.code = ? AND fd.form_type = 'PROCESS'
                            LIMIT 1
                            """,
                    (rs, rowNum) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("formId", rs.getLong("form_id"));
                        m.put("formName", rs.getString("form_name"));
                        m.put("configJsonRaw", rs.getString("config_json"));
                        return m;
                    },
                    functionUnitCode.trim());
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> first = rows.get(0);
            long formId = ((Number) first.get("formId")).longValue();
            Map<String, Object> formDef = new HashMap<>();
            formDef.put("name", first.get("formName"));
            formDef.put("configJson", parseConfigJsonString((String) first.get("configJsonRaw")));
            formDef.put("subTableBindings", loadSubTableBindingMapsForForm(formId));
            return formDef;
        } catch (Exception e) {
            log.debug("Local dw PROCESS form lookup failed for {}: {}", functionUnitCode, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfigJsonString(String raw) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Aligns with admin-center attachTableBindings: prefer dw_table_definitions; RELATED falls back to rt_table_definitions.
     */
    private List<Map<String, Object>> loadSubTableBindingMapsForForm(long formId) {
        try {
            List<Map<String, Object>> bindings = jdbcTemplate.query(
                    """
                            SELECT ftb.id AS binding_id,
                                   ftb.binding_type::text AS binding_type,
                                   ftb.binding_mode::text AS binding_mode,
                                   COALESCE(td.id, rt.id) AS table_id,
                                   COALESCE(td.table_name, rt.table_name) AS table_name,
                                   COALESCE(td.table_display_name, rt.display_name) AS table_display_name
                            FROM dw_form_table_bindings ftb
                            LEFT JOIN dw_table_definitions td ON td.id = ftb.table_id
                            LEFT JOIN rt_table_definitions rt ON rt.id = ftb.relation_table_id
                            WHERE ftb.form_id = ?
                            ORDER BY ftb.sort_order NULLS LAST, ftb.id
                            """,
                    (rs, rowNum) -> {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("bindingId", rs.getLong("binding_id"));
                        b.put("tableName", rs.getString("table_name"));
                        b.put("tableDisplayName", rs.getString("table_display_name"));
                        b.put("bindingType", rs.getString("binding_type"));
                        b.put("bindingMode", rs.getString("binding_mode"));
                        b.put("columns", Collections.emptyList());
                        b.put("data", Collections.emptyList());
                        // Store table_id for PK resolution below
                        long tableId = rs.getLong("table_id");
                        if (!rs.wasNull()) {
                            b.put("_tableId", tableId);
                        }
                        return b;
                    },
                    formId);

            // Batch-resolve primary key field names for all dw_ tables referenced in these bindings
            List<Long> dwTableIds = bindings.stream()
                    .filter(b -> b.get("_tableId") instanceof Long)
                    .map(b -> (Long) b.get("_tableId"))
                    .distinct()
                    .toList();
            if (!dwTableIds.isEmpty()) {
                Map<Long, List<String>> pkByTable = new HashMap<>();
                List<Map<String, Object>> pkRows = jdbcTemplate.queryForList(
                        "SELECT table_id, field_name FROM dw_field_definitions WHERE is_primary_key = true AND table_id IN ("
                                + String.join(",", dwTableIds.stream().map(String::valueOf).toList())
                                + ") ORDER BY sort_order");
                for (Map<String, Object> row : pkRows) {
                    long tid = ((Number) row.get("table_id")).longValue();
                    pkByTable.computeIfAbsent(tid, k -> new ArrayList<>())
                            .add((String) row.get("field_name"));
                }
                for (Map<String, Object> b : bindings) {
                    Object tid = b.remove("_tableId");
                    if (tid instanceof Long) {
                        List<String> pkFields = pkByTable.get((Long) tid);
                        if (pkFields != null && !pkFields.isEmpty()) {
                            b.put("primaryKeyFields", pkFields);
                        }
                    }
                }
            }

            return bindings;
        } catch (Exception e) {
            log.debug("Could not load dw_form_table_bindings for formId={}: {}", formId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchProcessFormFromAdminCenter(String processDefinitionKeyOrCatalogId) {
        String base = normalizeBaseUrl(adminCenterUrl);
        String functionUnitId = resolveAdminFunctionUnitId(base, processDefinitionKeyOrCatalogId);
        if (functionUnitId == null || functionUnitId.isBlank()) {
            functionUnitId = processDefinitionKeyOrCatalogId;
        }
        return fetchProcessFormFromAdminContents(base, functionUnitId);
    }

    @SuppressWarnings("unchecked")
    private String resolveAdminFunctionUnitId(String base, String processKey) {
        if (processKey == null || processKey.isBlank()) {
            return null;
        }
        String byKeyUrl = base + "/api/v1/admin/function-units/by-process-key/"
                + UriUtils.encodePathSegment(processKey, StandardCharsets.UTF_8);
        try {
            Map<String, Object> fu = restTemplate.getForObject(byKeyUrl, Map.class);
            if (fu != null && fu.get("id") != null) {
                return Objects.toString(fu.get("id"), null);
            }
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound notFound) {
            log.debug("admin-center by-process-key not found for {}: {}", processKey, notFound.getMessage());
        } catch (Exception e) {
            log.debug("admin-center by-process-key failed for {}: {}", processKey, e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchProcessFormFromAdminContents(String base, String functionUnitId) {
        if (functionUnitId == null || functionUnitId.isBlank()) {
            return null;
        }
        String contentsUrl = base + "/api/v1/admin/function-units/"
                + UriUtils.encodePathSegment(functionUnitId, StandardCharsets.UTF_8)
                + "/contents?type=FORM";
        Map<String, Object> response;
        try {
            response = restTemplate.getForObject(contentsUrl, Map.class);
        } catch (Exception e) {
            log.debug("admin-center contents failed for fu {}: {}", functionUnitId, e.getMessage());
            return null;
        }
        List<Map<String, Object>> items = ApiResponseBodyUnwrap.normalizeToListOfMaps(response);
        for (Map<String, Object> item : items) {
            Object rawData = item.get("contentData");
            if (!(rawData instanceof String contentDataStr) || contentDataStr.isBlank()) {
                continue;
            }
            try {
                Map<String, Object> parsed = objectMapper.readValue(contentDataStr, new TypeReference<Map<String, Object>>() {});
                Object ft = parsed.get("formType");
                if (!"PROCESS".equals(ft instanceof String ? ft : Objects.toString(ft, null))) {
                    continue;
                }
                Map<String, Object> formDef = new HashMap<>();
                formDef.put("name", parsed.get("formName"));
                Object cj = parsed.get("configJson");
                if (cj instanceof Map<?, ?>) {
                    formDef.put("configJson", new HashMap<>((Map<String, Object>) cj));
                } else if (cj instanceof String s) {
                    formDef.put("configJson", parseConfigJsonString(s));
                } else {
                    formDef.put("configJson", Collections.emptyMap());
                }
                Long formIdLong = null;
                Object fid = parsed.get("formId");
                if (fid instanceof Number n) {
                    formIdLong = n.longValue();
                } else if (fid != null) {
                    try {
                        formIdLong = Long.parseLong(fid.toString());
                    } catch (NumberFormatException ignored) {
                        // leave null
                    }
                }
                if (formIdLong != null) {
                    formDef.put("subTableBindings", loadSubTableBindingMapsForForm(formIdLong));
                } else {
                    formDef.put("subTableBindings", Collections.emptyList());
                }
                return formDef;
            } catch (Exception parseEx) {
                log.debug("Skip malformed FORM contentData for {}: {}", functionUnitId, parseEx.getMessage());
            }
        }
        return null;
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8090";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @SuppressWarnings("unchecked")
    private boolean checkProcessFormExists(String functionUnitId) {
        try {
            if (fetchProcessFormFromLocalDw(functionUnitId) != null) {
                return true;
            }
            return fetchProcessFormFromAdminCenter(functionUnitId) != null;
        } catch (Exception e) {
            log.warn("Failed to check PROCESS form existence for {}: {}", functionUnitId, e.getMessage());
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMapField(Map<String, Object> source, String fieldName) {
        Object value = source.get(fieldName);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<SubTableBindingData> extractSubTableBindings(Map<String, Object> formDefinition) {
        Object bindings = formDefinition.get("subTableBindings");
        if (bindings instanceof List) {
            List<Map<String, Object>> bindingList = (List<Map<String, Object>>) bindings;
            List<SubTableBindingData> result = bindingList.stream()
                    .map(b -> SubTableBindingData.builder()
                            .bindingId(b.get("bindingId") != null ? ((Number) b.get("bindingId")).longValue() : null)
                            .tableName((String) b.get("tableName"))
                            .tableDisplayName((String) b.get("tableDisplayName"))
                            .bindingType((String) b.get("bindingType"))
                            .bindingMode((String) b.get("bindingMode"))
                            .columns((List<Map<String, Object>>) b.get("columns"))
                            .data((List<Map<String, Object>>) b.get("data"))
                            .build())
                    .toList();

            // Resolve primary key field names from dw_field_definitions for each table
            List<String> tableNames = result.stream()
                    .map(SubTableBindingData::getTableName)
                    .filter(t -> t != null && !t.isBlank())
                    .distinct()
                    .toList();
            if (!tableNames.isEmpty()) {
                Map<String, List<String>> pkByTable = new HashMap<>();
                List<Map<String, Object>> pkRows = jdbcTemplate.queryForList(
                        "SELECT td.table_name, fd.field_name FROM dw_field_definitions fd"
                                + " INNER JOIN dw_table_definitions td ON td.id = fd.table_id"
                                + " WHERE td.table_name IN ('"
                                + String.join("','", tableNames)
                                + "') AND fd.is_primary_key = true ORDER BY fd.sort_order");
                for (Map<String, Object> row : pkRows) {
                    String tn = (String) row.get("table_name");
                    pkByTable.computeIfAbsent(tn, k -> new ArrayList<>())
                            .add((String) row.get("field_name"));
                }
                for (SubTableBindingData b : result) {
                    if (b.getTableName() != null) {
                        b.setPrimaryKeyFields(pkByTable.get(b.getTableName()));
                    }
                }
            }

            attachRelationTableIdentity(result);

            return result;
        }
        return Collections.emptyList();
    }

    /**
     * Stamps {@code relationTableId} / {@code relationTableName} onto bindings that target a RELATION
     * table, so the portal can tell them apart from designer sub-tables.
     *
     * <p>Without this the portal filed both kinds under the same {@code dw:<name>} canonical key and
     * MI row isolation treated a relation table's rows as one participant's — rejecting every Save on
     * that task. Best-effort: a lookup failure simply leaves the fields null, i.e. the previous
     * behaviour, rather than failing form load.
     */
    private void attachRelationTableIdentity(List<SubTableBindingData> bindings) {
        List<Long> bindingIds = bindings.stream()
                .map(SubTableBindingData::getBindingId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (bindingIds.isEmpty()) {
            return;
        }
        try {
            String placeholders = String.join(",", Collections.nCopies(bindingIds.size(), "?"));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT b.id AS binding_id, b.relation_table_id, rt.table_name AS relation_table_name"
                            + " FROM dw_form_table_bindings b"
                            + " LEFT JOIN rt_table_definitions rt ON rt.id = b.relation_table_id"
                            + " WHERE b.relation_table_id IS NOT NULL AND b.id IN (" + placeholders + ")",
                    bindingIds.toArray());
            if (rows.isEmpty()) {
                return;
            }
            Map<Long, Map<String, Object>> byBindingId = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Object id = row.get("binding_id");
                if (id instanceof Number n) {
                    byBindingId.put(n.longValue(), row);
                }
            }
            for (SubTableBindingData b : bindings) {
                Map<String, Object> row = b.getBindingId() != null ? byBindingId.get(b.getBindingId()) : null;
                if (row == null) {
                    continue;
                }
                Object rtId = row.get("relation_table_id");
                if (rtId instanceof Number n) {
                    b.setRelationTableId(n.longValue());
                }
                Object rtName = row.get("relation_table_name");
                if (rtName != null) {
                    b.setRelationTableName(String.valueOf(rtName));
                }
            }
        } catch (RuntimeException e) {
            log.debug("attachRelationTableIdentity skipped: {}", e.getMessage());
        }
    }

    /**
     * Parses the process's deployed BPMN for MI assignment contracts (allowUser/allowRole/assigneeField/
     * roleField/buField) and stamps them onto matching bindings by table name, so the Process Form's
     * sub-table Add/Edit dialog renders the same Assignment Mode block as the Task Form (portal-design-parity).
     * Best-effort: BPMN fetch failure leaves bindings without assignmentConfig (dialog just omits the block).
     */
    private void attachMiAssignmentConfigs(List<SubTableBindingData> bindings, String processDefinitionKey) {
        if (bindings.isEmpty() || processDefinitionKey == null || processDefinitionKey.isBlank()
                || workflowEngineClient == null) {
            return;
        }
        try {
            Optional<String> bpmnOpt = workflowEngineClient.getBpmnXml(processDefinitionKey);
            if (bpmnOpt.isEmpty() || bpmnOpt.get().isBlank()) {
                return;
            }
            Map<String, Map<String, Object>> assignments =
                    BpmnMiXmlSupport.buildMiAssignmentsBySubTableName(bpmnOpt.get());
            if (assignments.isEmpty()) {
                return;
            }
            for (SubTableBindingData binding : bindings) {
                Map<String, Object> config = assignments.get(binding.getTableName());
                if (config != null) {
                    binding.setAssignmentConfig(config);
                }
            }
        } catch (BpmnMiXmlSupport.MiAssignmentConfigurationException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Failed to attach MI assignment configs for processDefinitionKey={}: {}",
                    processDefinitionKey, e.getMessage());
        }
    }

    private MiOuterStepResolver.OuterLookup ownerMiLookup(ProcessInstance instance) {
        if (miOuterStepResolver == null || instance == null) {
            return MiOuterStepResolver.OuterLookup.known(null);
        }
        return miOuterStepResolver.lookup(instance.getProcessDefinitionKey(), instance.getCurrentNode());
    }
}
