package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.audit.SystemAuditFields;
import com.portal.client.WorkflowEngineClient;
import com.portal.dto.*;
import com.portal.entity.ProcessInstance;
import com.portal.util.SubTableNestingSanitizer;
import com.portal.util.SystemAuditFieldFiller;
import com.portal.service.UserDisplayNameResolver;
import com.portal.exception.PortalException;
import com.portal.repository.ProcessInstanceRepository;
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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task Form component.
 * Loads Task Form data, submit handling, snapshot capture, and completed-task
 * form queries.
 *
 * <p>
 * 本类为门面：保留全部 public 方法签名与 public 内部类型，方法体委托同包协作类——
 * {@link TaskFormFieldMapper}（字段/快照纯函数）、{@link TaskFormDefinitionLoader}（表单定义加载）、
 * {@link TaskFormSubTableChangeRecorder}（子表变更历史）。协作类以 {@code @Lazy @Autowired}
 * 字段注入，
 * 不拓宽构造器，沿用仓库既定破环模式（见 {@code processComponent}）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskFormComponent {

    private final ProcessFormComponent processFormComponent;
    private final ChangeHistoryComponent changeHistoryComponent;
    private final ProcessInstanceRepository processInstanceRepository;
    private final WorkflowEngineClient workflowEngineClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager platformTransactionManager;

    private volatile TransactionTemplate taskFormWriteTxTemplate;

    /**
     * Single write txn for task-form persistence — avoids
     * UnexpectedRollbackException vs nested listeners/history.
     */
    private TransactionTemplate taskFormWriteTx() {
        TransactionTemplate t = taskFormWriteTxTemplate;
        if (t == null) {
            synchronized (this) {
                t = taskFormWriteTxTemplate;
                if (t == null) {
                    t = new TransactionTemplate(platformTransactionManager);
                    taskFormWriteTxTemplate = t;
                }
            }
        }
        return t;
    }

    /**
     * Lazy: merges physical relation-table rows into task-form variable payloads
     * without widening ctor for tests.
     */
    @Lazy
    @Autowired
    private ProcessComponent processComponent;

    /**
     * 协作类以 {@code @Lazy @Autowired} 字段注入——保持 8 参 {@code @RequiredArgsConstructor}
     * 不变，不破坏测试构造点。
     * 测试以 {@code new TaskFormComponent(...)} 构造时 Spring 不参与注入，这些字段为 null；
     * 故下方 accessor 在为 null 时用门面自身已持有的依赖按需构造一份（协作类无额外状态），行为与注入版本一致。
     */
    @Lazy
    @Autowired
    private TaskFormFieldMapper fieldMapper;

    @Lazy
    @Autowired
    private TaskFormDefinitionLoader formDefinitionLoader;

    @Lazy
    @Autowired
    private TaskFormSubTableChangeRecorder subTableChangeRecorder;

    @Lazy
    @Autowired
    private ChangeHistorySubmissionFilter changeHistorySubmissionFilter;

    /**
     * Lazy: computes the readonly Request ID value so the form field matches DW
     * preview / portal lists.
     */
    @Lazy
    @Autowired
    private RequestIdEnricher requestIdEnricher;

    /**
     * Lazy: resolves updated_by display names for system audit fields (null in
     * `new`-constructed tests).
     */
    @Lazy
    @Autowired
    private UserDisplayNameResolver userDisplayNameResolver;

    /**
     * Lazy: Owner-field validation / display re-resolution / default fill on task-form
     * submits (null in `new`-constructed tests, which do not exercise owner fields).
     */
    @Lazy
    @Autowired
    private OwnerFieldComponent ownerFieldComponent;

    /**
     * Display name for audit fields; falls back to the raw user id when the
     * resolver is unavailable.
     */
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

    @Lazy
    @Autowired
    private ProcessSubTablePrimaryKeyEnricherComponent processSubTablePrimaryKeyEnricherComponent;

    /** Lazy: server-side formula columns; null in {@code new}-constructed tests skips recalculation. */
    @Lazy
    @Autowired
    private ComputedFieldRecalculator computedFieldRecalculator;

    private ProcessSubTablePrimaryKeyEnricherComponent subTablePrimaryKeyEnricher() {
        return processSubTablePrimaryKeyEnricherComponent;
    }

    /**
     * Lazy: MI (multi-instance) sub-task row-level save isolation — merges only the current
     * sub-task's own {@code __subTables__} row into the persisted baseline instead of replacing
     * the whole shared array. See {@link MiSubTaskSubTableRowMerger}.
     */
    @Lazy
    @Autowired
    private MiSubTaskSubTableRowMerger miSubTaskSubTableRowMerger;

    private MiSubTaskSubTableRowMerger miSubTaskSubTableRowMerger() {
        MiSubTaskSubTableRowMerger m = miSubTaskSubTableRowMerger;
        if (m == null) {
            m = new MiSubTaskSubTableRowMerger(jdbcTemplate);
            miSubTaskSubTableRowMerger = m;
        }
        return m;
    }

    private void recalculateComputedFields(String functionUnitCode, Map<String, Object> variables) {
        ComputedFieldRecalculator recalculator = computedFieldRecalculator;
        if (recalculator == null || functionUnitCode == null || functionUnitCode.isBlank() || variables == null) {
            return;
        }
        recalculator.recalculate(functionUnitCode, variables);
    }

    private ChangeHistorySubmissionFilter changeHistorySubmissionFilter() {
        ChangeHistorySubmissionFilter filter = changeHistorySubmissionFilter;
        if (filter == null) {
            filter = new ChangeHistorySubmissionFilter(jdbcTemplate, objectMapper);
            changeHistorySubmissionFilter = filter;
        }
        return filter;
    }

    Map<String, Object> filterTaskSubmissionForChangeHistory(String processInstanceId,
            String stageId,
            Map<String, Object> submitted,
            Map<String, Object> enriched) {
        return changeHistorySubmissionFilter().filterTaskSubmission(
                processInstanceId, stageId, submitted, enriched);
    }

    Map<String, Object> copyChangeHistorySubmission(Map<String, Object> submitted) {
        return changeHistorySubmissionFilter().copyPayload(submitted);
    }

    Object filterTaskSubTableBaselineForChangeHistory(String processInstanceId,
            String stageId,
            Object storedSubTables) {
        return changeHistorySubmissionFilter().filterTaskSubTableBaseline(
                processInstanceId, stageId, storedSubTables);
    }

    private RequestIdEnricher requestIdEnricher() {
        RequestIdEnricher r = requestIdEnricher;
        if (r == null) {
            r = new RequestIdEnricher(jdbcTemplate, objectMapper, processInstanceRepository);
            requestIdEnricher = r;
        }
        return r;
    }

    private TaskFormFieldMapper fieldMapper() {
        TaskFormFieldMapper m = fieldMapper;
        if (m == null) {
            m = new TaskFormFieldMapper();
            fieldMapper = m;
        }
        return m;
    }

    private TaskFormDefinitionLoader formDefinitionLoader() {
        TaskFormDefinitionLoader l = formDefinitionLoader;
        if (l == null) {
            l = new TaskFormDefinitionLoader(restTemplate, objectMapper, jdbcTemplate);
            formDefinitionLoader = l;
        }
        return l;
    }

    private TaskFormSubTableChangeRecorder subTableChangeRecorder() {
        TaskFormSubTableChangeRecorder r = subTableChangeRecorder;
        if (r == null) {
            r = new TaskFormSubTableChangeRecorder(changeHistoryComponent);
            subTableChangeRecorder = r;
        }
        return r;
    }

    /**
     * Lazy: hydrates {@code up_process_instance} for engine-only starts (email
     * monitor).
     */
    @Lazy
    @Autowired
    private ProcessInstanceHydrationComponent processInstanceHydration;

    private ProcessInstance requireProcessInstance(String processInstanceId) {
        ProcessInstanceHydrationComponent hydration = processInstanceHydration;
        if (hydration != null) {
            return hydration.requireProcessInstance(processInstanceId);
        }
        return processInstanceRepository.findById(processInstanceId)
                .orElseThrow(() -> new PortalException("404",
                        "Process instance not found: " + processInstanceId));
    }

    /**
     * Normalizes the declared "deliberately emptied" store keys.
     *
     * <p>An empty slice is ambiguous by itself ("user deleted their last row" vs "this binding was
     * never rendered"), so the frontend declares intent rather than the backend guessing. A missing
     * or blank-only declaration yields an empty set — i.e. the previous behavior, where an empty
     * slice always leaves the baseline untouched.
     *
     * <p>It arrives as its own request field rather than inside {@code formData}: the
     * approve/complete path copies {@code formData} wholesale into process variables, so a marker
     * carried there would be persisted as a business variable.
     */
    private static Set<String> normalizeEmptiedSubTableKeys(List<String> declared) {
        if (declared == null || declared.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String k : declared) {
            if (k != null && !k.trim().isEmpty()) {
                keys.add(k.trim());
            }
        }
        return keys;
    }

    /**
     * Overlays THIS task's own MI loop variable ({@code _currentItem}) from the engine.
     *
     * <p>Flowable scopes {@code currentItem} to the MI sub-process execution, so each participant's
     * sub-task has a different one; {@code TaskInfoAssembler} reads it per task and returns it on the
     * task payload. The portal's {@code up_process_instance.variables}, by contrast, is one blob for
     * the whole process and cannot represent per-participant identity — reading it left every MI
     * sub-task believing it owned whichever row happened to be stored last.
     *
     * <p>Absent/unavailable engine data leaves {@code target} without the key, which is correct: the
     * caller then treats the submission as non-MI rather than acting on a foreign participant's row.
     */
    private void applyTaskScopedMiCurrentItem(String taskId, Map<String, Object> target) {
        if (workflowEngineClient == null || taskId == null) {
            return;
        }
        try {
            workflowEngineClient.getTaskById(taskId).ifPresent(body -> {
                Object dataRaw = body.containsKey("data") ? body.get("data") : body;
                if (!(dataRaw instanceof Map<?, ?> data)) {
                    return;
                }
                Object varsRaw = data.get("variables");
                if (!(varsRaw instanceof Map<?, ?> vars)) {
                    return;
                }
                Object currentItem = vars.get("_currentItem");
                if (currentItem == null) {
                    currentItem = vars.get("currentItem");
                }
                if (currentItem instanceof Map) {
                    target.put("_currentItem", currentItem);
                }
            });
        } catch (RuntimeException e) {
            log.debug("applyTaskScopedMiCurrentItem skipped for {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Fills {@code target} with process variables that live only in the Flowable
     * engine (e.g. a service
     * task's {@code __subTables__} output) and are absent from the portal's own
     * {@code up_process_instance}
     * store. Gap-fill only: values already present win, so portal form submissions
     * and user edits are never
     * overwritten. Best-effort — a failed engine round-trip leaves the portal-store
     * values untouched.
     */
    private void mergeEngineOnlyVariables(String processInstanceId, Map<String, Object> target) {
        if (workflowEngineClient == null || processInstanceId == null) {
            return;
        }
        try {
            workflowEngineClient.getProcessInstance(processInstanceId).ifPresent(row -> {
                Object raw = row.get("variables");
                if (raw instanceof Map<?, ?> engineVars) {
                    engineVars.forEach((k, v) -> {
                        if (k != null && v != null && !target.containsKey(String.valueOf(k))) {
                            target.put(String.valueOf(k), v);
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            log.debug("mergeEngineOnlyVariables skipped for {}: {}", processInstanceId, e.getMessage());
        }
    }

    @Value("${developer-workstation.url:http://localhost:8091}")
    private String developerWorkstationUrl;

    /**
     * Returns Task Form layout and current process variable values (field subset).
     * Resolves FormStageBinding by taskDefinitionKey; falls back to read-only
     * Process Form when unbound.
     *
     * @param taskId task instance ID
     * @return TaskFormData DTO
     */
    public TaskFormData getTaskFormData(String taskId) {
        log.debug("Getting task form data for task: {}", taskId);

        long __t = System.nanoTime();
        TaskInfo taskInfo = getTaskInfo(taskId);
        log.info("[PERF] form-data.getTaskInfo(engine) took {} ms", (System.nanoTime() - __t) / 1_000_000L);

        // Find FormStageBinding by taskDefinitionKey
        __t = System.nanoTime();
        Map<String, Object> formDefinition = fetchTaskFormByStageId(taskInfo.taskDefinitionKey, taskInfo.processInstanceId);
        log.info("[PERF] form-data.fetchTaskFormByStageId took {} ms", (System.nanoTime() - __t) / 1_000_000L);

        // Get process instance for variables
        ProcessInstance processInstance = requireProcessInstance(taskInfo.processInstanceId);

        Map<String, Object> allVariables = processInstance.getVariables() != null
                ? processInstance.getVariables()
                : Collections.emptyMap();
        Map<String, Object> hydratedVariables = new HashMap<>(allVariables);
        // The MI loop variable is EXECUTION-scoped: this process-wide store must never be the source
        // of "which participant row is mine". A stale copy left here by an older build (or any other
        // writer) would hand THIS task another participant's row identity, so drop it before the
        // per-task value is resolved from the engine below.
        hydratedVariables.remove("_currentItem");
        hydratedVariables.remove("currentItem");
        // Service-task outputs (e.g. an Activepieces task that sets __subTables__) live
        // in the Flowable
        // engine but never reach the portal's up_process_instance store, which is only
        // written on portal
        // form submissions. Gap-fill from the live engine variables so task forms
        // render those results.
        mergeEngineOnlyVariables(taskInfo.processInstanceId, hydratedVariables);
        // `mergeEngineOnlyVariables` reads PROCESS-INSTANCE-level engine variables, so it can
        // reintroduce the instance-wide `_currentItem` that was deliberately dropped above — that
        // copy belongs to whichever participant wrote it last. Drop it again, then let the per-task
        // lookup be the only writer: if the engine cannot answer for this task, the form must carry
        // NO participant identity rather than a foreign one. Measured: task aa9fd949 owns
        // Test-000001 (its MI execution's own loop variable) while this merge left Test-000002 —
        // the form then saved People rows under the wrong participant, and MI isolation, which reads
        // the task-detail endpoint's correct value, filtered them straight back out on reload.
        hydratedVariables.remove("_currentItem");
        hydratedVariables.remove("currentItem");
        applyTaskScopedMiCurrentItem(taskId, hydratedVariables);
        if (ownerFieldComponent != null) {
            ownerFieldComponent.projectForRead(
                    processInstance.getFunctionUnitCode(),
                    new OwnerFieldComponent.OwnerWriteContext(
                            processInstance.getStartUserId(),
                            processInstance.getStartUserId(),
                            processInstance.getCurrentAssignee(),
                            processInstance.getCandidateUsers(),
                            null),
                    hydratedVariables);
        }
        if (processComponent != null) {
            __t = System.nanoTime();
            processComponent.enrichSubTablesVariablesFromPhysicalTables(taskInfo.processInstanceId, hydratedVariables);
            log.info("[PERF] form-data.enrichSubTables took {} ms", (System.nanoTime() - __t) / 1_000_000L);
        }

        // Get Process Form reference data
        __t = System.nanoTime();
        ProcessFormData processFormRef = processFormComponent.getProcessFormData(taskInfo.processInstanceId);
        log.info("[PERF] form-data.nested.getProcessFormData took {} ms", (System.nanoTime() - __t) / 1_000_000L);
        // `getProcessFormData` is keyed by PROCESS INSTANCE only, so its fieldValues carry the
        // instance-wide `_currentItem` — the copy left by whichever participant wrote it last. The
        // portal reads THIS one when building the submit payload, so a task whose own MI execution
        // owns a different row saved under a foreign participant while the read path (task detail,
        // already task-scoped) filtered those rows straight back out. Measured on FU fu-20260422:
        // task aa9fd949 owns Test-000001, this payload said Test-000002, and People rows added on
        // that sub-task vanished on reload. Re-scope it to this task, same as hydratedVariables.
        if (processFormRef != null && processFormRef.getFieldValues() != null) {
            applyTaskScopedMiCurrentItem(taskId, processFormRef.getFieldValues());
        }

        if (formDefinition == null) {
            // Fallback: no Task Form binding, return only ProcessFormData in read-only
            // mode.
            // Still surface the readonly Request ID so the rendered field isn't blank.
            log.info("No Task Form binding found for stage '{}', falling back to Process Form",
                    taskInfo.taskDefinitionKey);
            Map<String, Object> fallbackFieldValues = null;
            String fallbackRequestId = requestIdEnricher()
                    .buildRequestId(processInstance.getFunctionUnitCode(), hydratedVariables);
            if (fallbackRequestId != null) {
                fallbackFieldValues = new HashMap<>();
                fallbackFieldValues.put(RequestIdEnricher.REQUEST_ID_FIELD, fallbackRequestId);
            }
            return TaskFormData.builder()
                    .taskId(taskId)
                    .taskDefinitionKey(taskInfo.taskDefinitionKey)
                    .formName(null)
                    .configJson(null)
                    .fieldPermissions(null)
                    .fieldValues(fallbackFieldValues)
                    .subTableBindings(null)
                    .processFormRef(processFormRef)
                    .requestIdConfig(processFormRef != null ? processFormRef.getRequestIdConfig() : null)
                    .build();
        }

        // Extract Task Form layout and field permissions
        Map<String, Object> configJson = fieldMapper().extractMapField(formDefinition, "configJson");
        Map<String, String> fieldPermissions = fieldMapper().extractFieldPermissions(formDefinition);
        String formName = formDefinition.get("formName") != null
                ? (String) formDefinition.get("formName")
                : "Task Form";
        Boolean formReadOnly = formDefinition.get("readOnly") instanceof Boolean
                ? (Boolean) formDefinition.get("readOnly")
                : false;

        // Get field values from process variables (subset based on fieldPermissions
        // keys)
        Map<String, Object> fieldValues = fieldMapper().extractFieldSubset(hydratedVariables,
                fieldPermissions.keySet());
        // Mirror persistTaskFormSnapshot: always attach live __subTables__ when present
        // so nested /
        // copied-task bindings hydrate even if fieldPermissions omits or carries a
        // stale __subTables__ entry.
        if (hydratedVariables.containsKey("__subTables__")) {
            fieldValues.put("__subTables__", hydratedVariables.get("__subTables__"));
        }
        // Same reasoning for the MI loop variable: `extractFieldSubset` keeps only DESIGNER form
        // fields, and `_currentItem` is runtime state, not a form field — so the task-scoped value
        // resolved just above by `applyTaskScopedMiCurrentItem` was filtered straight back out.
        //
        // Without it the whole MI row-isolation contract silently switches off for this form:
        // `isMiSubTaskSubmission` sees no loop variable and treats the submission as non-MI (whole
        // array replace), and the frontend cannot resolve `currentMiRowId`, so it can neither scope
        // rows to this participant nor express a deletion. Measured on task 506809ee (Test-000009):
        // People rows loaded from the server could not be deleted at all, while rows added in the
        // same session could — the latter never needed the participant identity.
        if (hydratedVariables.get("_currentItem") instanceof Map) {
            fieldValues.put("_currentItem", hydratedVariables.get("_currentItem"));
        }
        // Readonly Request ID synthetic field: render the same value as DW preview /
        // portal lists.
        applyRequestIdFieldValue(fieldValues, processInstance, hydratedVariables);

        return TaskFormData.builder()
                .taskId(taskId)
                .taskDefinitionKey(taskInfo.taskDefinitionKey)
                .formName(formName)
                .configJson(configJson)
                .fieldPermissions(fieldPermissions)
                .fieldValues(fieldValues)
                .subTableBindings(Collections.emptyList())
                .processFormRef(processFormRef)
                .formReadOnly(formReadOnly)
                .requestIdConfig(processFormRef != null ? processFormRef.getRequestIdConfig() : null)
                .build();
    }

    /**
     * Fill the synthetic {@code __request_id} field value when the main table
     * configures a Request ID.
     * No-op (leaves the field absent) when unconfigured — the readonly input then
     * shows empty.
     */
    private void applyRequestIdFieldValue(Map<String, Object> fieldValues,
            ProcessInstance processInstance,
            Map<String, Object> variables) {
        String requestId = requestIdEnricher()
                .buildRequestId(processInstance.getFunctionUnitCode(), variables);
        if (requestId != null) {
            fieldValues.put(RequestIdEnricher.REQUEST_ID_FIELD, requestId);
        }
    }

    /**
     * Submits Task Form updates (editable fields only).
     * Filters read-only fields and updates only EDITABLE process variables.
     *
     * @param taskId   task instance ID
     * @param userId   acting user ID
     * @param formData form payload (may include read-only fields; they are filtered
     *                 out)
     */
    public void submitTaskForm(String taskId, String userId, Map<String, Object> formData) {
        submitTaskForm(taskId, userId, formData, null);
    }

    /**
     * Submits Task Form updates (editable fields only) with optional
     * concurrent-modification detection.
     *
     * @param taskId         task instance ID
     * @param userId         acting user ID
     * @param formData       form payload (read-only fields filtered)
     * @param baselineValues field snapshot from client load for concurrency check;
     *                       null skips detection
     */
    public void submitTaskForm(String taskId, String userId, Map<String, Object> formData,
            Map<String, Object> baselineValues) {
        submitTaskForm(taskId, userId, formData, baselineValues, null);
    }

    /**
     * @param emptiedSubTableKeys participant-scoped {@code __subTables__} store keys the frontend
     *                            deliberately emptied (the user deleted the last row they owned).
     *                            An empty slice cannot express that on its own — see the empty-slice
     *                            branch in {@link MiSubTaskSubTableRowMerger}. {@code null}/empty
     *                            keeps the previous behavior (baseline untouched).
     */
    public void submitTaskForm(String taskId, String userId, Map<String, Object> formData,
            Map<String, Object> baselineValues, List<String> emptiedSubTableKeys) {
        log.info("Submitting task form for task: {}, user: {}", taskId, userId);

        TaskInfo taskInfo = getTaskInfo(taskId);

        // Get field permissions to filter out READONLY fields
        Map<String, Object> formDefinition = fetchTaskFormByStageId(taskInfo.taskDefinitionKey, taskInfo.processInstanceId);
        Map<String, String> fieldPermissions = formDefinition != null
                ? fieldMapper().extractFieldPermissions(formDefinition)
                : Collections.emptyMap();

        Map<String, Object> submittedSnapshot = changeHistorySubmissionFilter().copyPayload(formData);

        // Filter: only accept EDITABLE fields
        Map<String, Object> editableData = filterEditableFields(formData, fieldPermissions);

        if (editableData.isEmpty()) {
            log.debug("No editable fields to update for task: {}", taskId);
            return;
        }

        if (editableData.containsKey("__subTables__")) {
            processInstanceRepository.findById(taskInfo.processInstanceId).ifPresent(pi -> {
                String fuCode = pi.getFunctionUnitCode();
                if (fuCode != null && !fuCode.isBlank()) {
                    ProcessSubTablePrimaryKeyEnricherComponent enricher = subTablePrimaryKeyEnricher();
                    if (enricher != null) {
                        enricher.allocateMissingPrimaryKeysInVariables(fuCode, editableData);
                    }
                }
            });
            filterSubTableFieldsInPlace(editableData, taskInfo.processInstanceId, taskInfo.taskDefinitionKey);
        }

        Map<String, Object> userChanges = changeHistorySubmissionFilter().filterTaskSubmission(
                taskInfo.processInstanceId, taskInfo.taskDefinitionKey, submittedSnapshot, editableData);
        AtomicReference<Map<String, Object>> snapshotOldVarsRef = new AtomicReference<>();
        AtomicReference<Set<String>> concurrentFieldsRef = new AtomicReference<>(Set.of());

        // MI (multi-instance) sub-task detection: presence of the BPMN _currentItem/currentItem
        // loop variable in the submitted form data means this task owns exactly one row of a
        // shared __subTables__ collection. Resolved once, outside the transaction, since it only
        // depends on the submitted payload — not on the process instance's persisted state.
        // An MI submission whose row key can't be resolved fails outright, before any DB access,
        // rather than silently falling back to a whole-array replace that could overwrite other
        // participants' data.
        Map<String, Object> resolvedMiCurrentRowKey = null;
        if (editableData.containsKey("__subTables__") && miSubTaskSubTableRowMerger().isMiSubTaskSubmission(formData)) {
            resolvedMiCurrentRowKey = miSubTaskSubTableRowMerger().resolveCurrentItemRowKey(formData);
            miSubTaskSubTableRowMerger().requireResolvedRowKey(resolvedMiCurrentRowKey);
        }
        final Map<String, Object> miCurrentRowKey = resolvedMiCurrentRowKey;
        // Which participant-scoped sub-table slices the frontend deliberately emptied (the user
        // deleted the last row they owned). An empty slice alone cannot say this — see the
        // empty-slice branch in MiSubTaskSubTableRowMerger — so the intent is declared explicitly.
        final Set<String> miEmptiedSubTableKeys = normalizeEmptiedSubTableKeys(emptiedSubTableKeys);

        taskFormWriteTx().executeWithoutResult(status -> {
            ProcessInstance processInstance = requireProcessInstance(taskInfo.processInstanceId);

            Map<String, Object> currentVariables = processInstance.getVariables() != null
                    ? new HashMap<>(processInstance.getVariables())
                    : new HashMap<>();

            Set<String> concurrentFields = detectConcurrentModifications(
                    baselineValues, currentVariables, editableData.keySet());

            if (!concurrentFields.isEmpty()) {
                log.warn("Concurrent modification detected on process {}, task {}, fields: {}, user: {}",
                        taskInfo.processInstanceId, taskId, concurrentFields, userId);
            }

            snapshotOldVarsRef.set(new HashMap<>(currentVariables));
            concurrentFieldsRef.set(Set.copyOf(concurrentFields));

            Map<String, Object> updatedVariables = new HashMap<>(currentVariables);
            Map<String, Object> inbound = new HashMap<>(editableData);
            // Defense in depth: strip even if filterEditableFields missed a case variant.
            SystemAuditFieldFiller.stripClientAuditKeys(inbound);
            // The MI loop variable is EXECUTION-scoped in Flowable: each participant's sub-task has
            // its own. up_process_instance.variables is a single process-wide blob, so persisting it
            // here stamps one participant's row identity onto the whole process — every other MI
            // sub-task then loads that stale value as "my row", edits the wrong participant's row,
            // and its save is rejected for submitting a row it does not own. Read it from the
            // engine's execution scope only; never store it process-wide.
            inbound.remove("_currentItem");
            inbound.remove("currentItem");

            if (miCurrentRowKey != null) {
                // Row-level isolation: merge only this MI sub-task's own row into whatever is
                // already persisted for every __subTables__ alias key, so another participant's
                // sub-task saving concurrently (or earlier) never gets its data overwritten by
                // this submission's necessarily-thin view of sibling rows.
                @SuppressWarnings("unchecked")
                Map<String, Object> submittedSubTables = (Map<String, Object>) inbound.get("__subTables__");
                @SuppressWarnings("unchecked")
                Map<String, Object> baselineSubTables = (Map<String, Object>) currentVariables.get("__subTables__");
                inbound.put("__subTables__",
                        miSubTaskSubTableRowMerger().mergeCurrentRowOnly(
                                submittedSubTables, baselineSubTables, miCurrentRowKey, miEmptiedSubTableKeys));
            }

            updatedVariables.putAll(inbound);

            // Owner fields: Creator pins startUserId; Current Assignee follows snapshot.
            // Overlay THIS task's MI loop variable only for the write, then drop it —
            // never persist execution-scoped _currentItem on the process-wide blob.
            if (ownerFieldComponent != null) {
                Object taskCurrentItem = OwnerFieldComponent.taskScopedCurrentItem(formData);
                if (taskCurrentItem instanceof Map) {
                    updatedVariables.put("_currentItem", taskCurrentItem);
                }
                ownerFieldComponent.applyOnSubmit(
                        processInstance.getFunctionUnitCode(),
                        new OwnerFieldComponent.OwnerWriteContext(
                                userId,
                                processInstance.getStartUserId(),
                                processInstance.getCurrentAssignee(),
                                processInstance.getCandidateUsers(),
                                currentVariables),
                        updatedVariables);
                OwnerFieldComponent.stripProcessWideCurrentItem(updatedVariables);
            }

            // System audit fields: refresh updated_at/updated_by at real update
            // (platform-managed; not gated on Form Design). created_* preserved from insert.
            SystemAuditFieldFiller.fillOnUpdate(updatedVariables, resolveAuditUserDisplay(userId));
            recalculateComputedFields(processInstance.getFunctionUnitCode(), updatedVariables);
            // Request ID is platform-derived: recompute it from the main-table config so a task
            // that edits a contributing field cannot leave the persisted identifier stale, and a
            // client-supplied value never survives.
            requestIdEnricher().stampRequestId(processInstance.getFunctionUnitCode(), updatedVariables);
            // Prevent geometric __subTables__ bloat: drop deep nested copies before
            // persisting so each
            // task save stores the canonical one-level structure instead of compounding
            // prior rounds.
            SubTableNestingSanitizer.stripDeepNestedSubTables(updatedVariables);
            processInstance.setVariables(updatedVariables);
            processInstanceRepository.save(processInstance);

            log.info("Process variables updated for task: {}, fields: {}", taskId, editableData.keySet());
        });

        /*
         * Change history runs after the write TransactionTemplate commits so failures
         * cannot mark it rollback-only.
         * Sub-table change history is deferred to task completion for consolidated
         * recording.
         */
        ChangeHistoryContext context = ChangeHistoryContext.builder()
                .processInstanceId(taskInfo.processInstanceId)
                .taskInstanceId(taskId)
                .stageId(taskInfo.taskDefinitionKey)
                .userId(userId)
                .build();

        Map<String, Object> snapshotOldVars = snapshotOldVarsRef.get();
        if (snapshotOldVars == null) {
            snapshotOldVars = Collections.emptyMap();
        }
        Set<String> concurrentSnapshot = concurrentFieldsRef.get();
        if (concurrentSnapshot == null) {
            concurrentSnapshot = Set.of();
        }

        try {
            for (String field : concurrentSnapshot) {
                changeHistoryComponent.recordConcurrentModificationWarning(
                        taskInfo.processInstanceId, field, "unknown", userId);
            }
            // Record top-level field changes
            changeHistoryComponent.recordFieldChanges(context, snapshotOldVars, userChanges);
            // Record sub-table row changes immediately so users see per-save
            // history; completion-time consolidation against the first-save
            // baseline is still recorded (cross-save dedup prevents identical
            // old+new pairs from duplicating when the final state equals the
            // last save).
            Object oldSubTables = snapshotOldVars.get("__subTables__");
            Object newSubTables = userChanges.get("__subTables__");
            if (newSubTables != null || oldSubTables != null) {
                Object filteredOldSubTables = changeHistorySubmissionFilter()
                        .filterTaskSubTableBaseline(taskInfo.processInstanceId,
                                taskInfo.taskDefinitionKey, oldSubTables);
                subTableChangeRecorder().recordSubTableChangeHistory(
                        context, filteredOldSubTables, newSubTables);
            }
        } catch (RuntimeException ex) {
            log.warn("task form change-history skipped for task {}: {}", taskId, ex.getMessage());
        }
    }

    /**
     * Enforces sub-table field-level permissions (composite {@code bindingId:field} keys, see
     * {@code FormDefinition.fieldPermissions}) on {@code editableData.get("__subTables__")},
     * in place, on the numeric-bindingId-keyed slice only.
     *
     * <p>The portal frontend submits every sub-table under several key aliases pointing at the
     * same row array (numeric bindingId, table name, normalized name — see
     * {@code useTaskForm.ts buildSubTableSubmitPayload}). Only the numeric-bindingId alias is
     * resolvable here without an extra DB round trip, so only it is filtered; other aliases of a
     * gated binding are left as submitted, unchanged from the pre-existing behavior (this method
     * only narrows the previously fully-unfiltered numeric-keyed entry — it does not widen any
     * other key's exposure). Bindings with no composite-key permission configured are untouched,
     * preserving full backward compatibility for unconfigured Function Units.
     */
    @SuppressWarnings("unchecked")
    private void filterSubTableFieldsInPlace(Map<String, Object> editableData,
            String processInstanceId, String stageId) {
        Object subTablesObj = editableData.get("__subTables__");
        if (!(subTablesObj instanceof Map)) {
            return;
        }
        Map<String, Set<String>> readonlyFieldsByBinding = changeHistorySubmissionFilter()
                .resolveSubFormFieldPermissionsByBinding(processInstanceId, stageId);
        if (readonlyFieldsByBinding.isEmpty()) {
            return;
        }
        Map<String, Object> subTables = (Map<String, Object>) subTablesObj;
        for (Map.Entry<String, Set<String>> gated : readonlyFieldsByBinding.entrySet()) {
            String bindingId = gated.getKey();
            Set<String> readonlyFields = gated.getValue();
            if (readonlyFields.isEmpty()) {
                continue;
            }
            Object rowsObj = subTables.get(bindingId);
            if (!(rowsObj instanceof List)) {
                continue;
            }
            // Identity columns survive the readonly filter so the next save can still match this
            // row. Resolved per binding from designer config — a fixed name list cannot name a key
            // such as `correspondence_id`, and would treat a business column called `id` as one.
            List<String> identityFields = changeHistorySubmissionFilter()
                    .rowIdentityFieldsForBinding(processInstanceId, stageId, bindingId);
            List<Object> filteredRows = new ArrayList<>();
            for (Object rowObj : (List<?>) rowsObj) {
                if (!(rowObj instanceof Map)) {
                    filteredRows.add(rowObj);
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) rowObj;
                Map<String, Object> filteredRow = new HashMap<>();
                for (Map.Entry<String, Object> field : row.entrySet()) {
                    if (!readonlyFields.contains(field.getKey())
                            || identityFields.contains(field.getKey())
                            || SystemAuditFields.isAuditField(field.getKey())) {
                        filteredRow.put(field.getKey(), field.getValue());
                    }
                }
                filteredRows.add(filteredRow);
            }
            subTables.put(bindingId, filteredRows);
        }
    }

    /**
     * Detects concurrent edits by comparing baseline values to current process
     * variables.
     * When current value != baseline, another user changed the field during
     * editing.
     *
     * @param baselineValues      field snapshot from client load (may be null)
     * @param currentVariables    current process variables
     * @param submittedFieldNames field names in this submit
     * @return field names modified concurrently
     */
    public Set<String> detectConcurrentModifications(Map<String, Object> baselineValues,
            Map<String, Object> currentVariables,
            Set<String> submittedFieldNames) {
        return fieldMapper().detectConcurrentModifications(baselineValues, currentVariables, submittedFieldNames);
    }

    /**
     * Returns snapshot and live values for a completed task.
     *
     * @param taskId task instance ID
     * @return CompletedTaskFormData DTO
     */
    public CompletedTaskFormData getCompletedTaskFormData(String taskId) {
        log.debug("Getting completed task form data for task: {}", taskId);

        TaskInfo taskInfo = getTaskInfo(taskId);

        ProcessInstance processInstance = requireProcessInstance(taskInfo.processInstanceId);

        Map<String, Object> allVariables = processInstance.getVariables() != null
                ? processInstance.getVariables()
                : Collections.emptyMap();

        // Get snapshot from process variable _snapshot_{taskId}
        String snapshotKey = "_snapshot_" + taskId;
        TaskFormSnapshot snapshot = fieldMapper().extractSnapshot(allVariables, snapshotKey);

        // Get current live values from process variables
        Map<String, Object> liveValues;
        Map<String, Object> snapshotFields = snapshot != null ? snapshot.getFieldValues() : null;
        if (snapshotFields != null && !snapshotFields.isEmpty()) {
            liveValues = fieldMapper().extractFieldSubset(allVariables, snapshotFields.keySet());
        } else {
            liveValues = Collections.emptyMap();
        }

        // Readonly Request ID synthetic field: same value as DW preview / portal lists
        // (frozen variables).
        String requestId = requestIdEnricher().buildRequestId(processInstance.getFunctionUnitCode(), allVariables);
        if (requestId != null) {
            if (!(liveValues instanceof HashMap)) {
                liveValues = new HashMap<>(liveValues);
            }
            liveValues.put(RequestIdEnricher.REQUEST_ID_FIELD, requestId);
            if (snapshot != null && snapshot.getFieldValues() != null
                    && !snapshot.getFieldValues().containsKey(RequestIdEnricher.REQUEST_ID_FIELD)) {
                snapshot.getFieldValues().put(RequestIdEnricher.REQUEST_ID_FIELD, requestId);
            }
        }

        // Get showLiveValues config from form definition
        boolean showLiveValues = true;
        Map<String, Object> formDefinition = fetchTaskFormByStageId(taskInfo.taskDefinitionKey, taskInfo.processInstanceId);
        if (formDefinition != null && formDefinition.containsKey("showLiveValues")) {
            Object slv = formDefinition.get("showLiveValues");
            if (slv instanceof Boolean) {
                showLiveValues = (Boolean) slv;
            }
        }

        // Get Process Form reference data
        ProcessFormData processFormRef = processFormComponent.getProcessFormData(taskInfo.processInstanceId);

        return CompletedTaskFormData.builder()
                .snapshot(snapshot)
                .liveValues(liveValues)
                .showLiveValues(showLiveValues)
                .processFormRef(processFormRef)
                .build();
    }

    /**
     * Merges a Task Form field-subset snapshot as {@code _snapshot_{taskId}} into
     * process variables before approval completion.
     * <p>
     * Callers should {@code save} {@link ProcessInstance} <strong>once</strong> to
     * avoid {@link ProcessInstance#lockVersion}
     * optimistic-lock conflicts (back-to-back UPDATEs on the same row can cause
     * UnexpectedRollback).
     * </p>
     * <p>
     * <b>Anti-bloat guardrails:</b>
     * </p>
     * <ul>
     * <li>Form resolution matches Change History: BPMN {@code formId} first
     * (TASK or PROCESS), then {@code dw_form_stage_bindings}. Empty permissions are
     * not treated as “no form” — field names still come from {@code configJson}.</li>
     * <li>When that resolution finds no form, the snapshot stores
     * <strong>empty fieldValues</strong>—no fallback to copying all process
     * variables and no {@code __subTables__}.</li>
     * <li>When a form is resolved, the snapshot freezes the form field subset
     * and a canonical {@code __subTables__} (numeric binding-id slices only),
     * even if {@code fieldPermissions} is empty.</li>
     * </ul>
     *
     * @param processInstanceId process instance owning the task; identifies the function unit that scopes
     *                          the Task Form binding lookup
     * @param mergedVariables merged process variables map (snapshot key written in
     *                        place)
     * @return form field names included in the snapshot (for logging)
     */
    public Set<String> mergeCompletedTaskSnapshotIntoVariables(String taskId, String userId, String taskDefinitionKey,
            String processInstanceId, Map<String, Object> mergedVariables) {
        if (mergedVariables == null || taskId == null || taskDefinitionKey == null) {
            return Set.of();
        }
        Map<String, Object> formDefinition = changeHistorySubmissionFilter()
                .resolveTaskFormDefinition(processInstanceId, taskDefinitionKey);
        Set<String> snapshotKeys = changeHistorySubmissionFilter().snapshotFieldKeys(formDefinition);
        boolean formResolved = formDefinition != null && !formDefinition.isEmpty();
        Map<String, Object> fieldValues = CompletedTaskSnapshotAssembler.assembleFieldValues(
                mergedVariables, snapshotKeys, formResolved, fieldMapper(), objectMapper);
        if (ownerFieldComponent != null) {
            processInstanceRepository.findById(processInstanceId).ifPresent(instance ->
                    ownerFieldComponent.copyOwnerValuesIntoSnapshot(
                            instance.getFunctionUnitCode(), mergedVariables, fieldValues));
        }

        TaskFormSnapshot snapshot = TaskFormSnapshot.builder()
                .taskId(taskId)
                .taskDefinitionKey(taskDefinitionKey)
                .assignee(userId)
                .completedAt(Instant.now())
                .fieldValues(fieldValues)
                .build();

        mergedVariables.put("_snapshot_" + taskId, snapshotToMap(snapshot));
        log.debug("Merged snapshot keys into variables for task {}, fields {}", taskId, fieldValues.keySet());
        return Set.copyOf(fieldValues.keySet());
    }

    /**
     * Captures snapshot on task completion.
     * Persists current process variables (Task Form field subset) as
     * _snapshot_{taskId}.
     *
     * @param taskId task instance ID
     * @param userId acting user ID (assignee)
     */
    @Transactional
    public void captureTaskFormSnapshot(String taskId, String userId) {
        log.info("Capturing task form snapshot for task: {}, user: {}", taskId, userId);

        TaskInfo taskInfo = getTaskInfo(taskId);
        persistTaskFormSnapshot(taskId, userId, taskInfo.taskDefinitionKey, taskInfo.processInstanceId,
                Collections.emptyMap());
    }

    /**
     * Captures snapshot after task completion.
     * <p>
     * Flowable runtime task is gone after completion; caller must pass
     * stage/process info captured beforehand.
     * </p>
     */
    @Transactional
    public void captureTaskFormSnapshot(String taskId, String userId, String taskDefinitionKey,
            String processInstanceId, Map<String, Object> completedVariables) {
        log.info("Capturing completed task form snapshot for task: {}, stage: {}", taskId, taskDefinitionKey);

        persistTaskFormSnapshot(taskId, userId, taskDefinitionKey, processInstanceId,
                completedVariables != null ? completedVariables : Collections.emptyMap());
    }

    private void persistTaskFormSnapshot(String taskId, String userId, String taskDefinitionKey,
            String processInstanceId, Map<String, Object> completedVariables) {
        ProcessInstance processInstance = requireProcessInstance(processInstanceId);

        Map<String, Object> merged = new HashMap<>();
        if (processInstance.getVariables() != null) {
            merged.putAll(processInstance.getVariables());
        }
        merged.putAll(completedVariables != null ? completedVariables : Collections.emptyMap());

        Set<String> snapshotFieldKeys = mergeCompletedTaskSnapshotIntoVariables(taskId, userId, taskDefinitionKey,
                processInstanceId, merged);
        SubTableNestingSanitizer.stripDeepNestedSubTables(merged);
        // The merge can pull a client-supplied Request ID in from completedVariables; re-derive it
        // so a snapshot capture never rewrites the stored identifier with an unstamped value.
        requestIdEnricher().stampRequestId(processInstance.getFunctionUnitCode(), merged);
        processInstance.setVariables(merged);
        processInstanceRepository.save(processInstance);

        log.info("Task form snapshot captured for task: {}, fields: {}", taskId, snapshotFieldKeys);
    }

    // ==================== Public utility methods for testing ====================

    /**
     * Filters read-only fields, keeping EDITABLE fields only.
     * When fieldPermissions is empty, accepts all fields (backward compatible).
     */
    public Map<String, Object> filterEditableFields(Map<String, Object> formData,
            Map<String, String> fieldPermissions) {
        return fieldMapper().filterEditableFields(formData, fieldPermissions);
    }

    /**
     * Enforces sub-table field-level permissions on {@code editableData.get("__subTables__")},
     * in place. See {@link #filterSubTableFieldsInPlace(Map, String, String)}.
     */
    public void filterSubTableFieldsForTesting(Map<String, Object> editableData,
            String processInstanceId, String stageId) {
        filterSubTableFieldsInPlace(editableData, processInstanceId, stageId);
    }

    /**
     * Extracts a field subset from full process variables.
     */
    public Map<String, Object> extractFieldSubset(Map<String, Object> allVariables,
            Set<String> fieldNames) {
        return fieldMapper().extractFieldSubset(allVariables, fieldNames);
    }

    /**
     * Counts fields that differ between snapshot and live values.
     */
    public int countSnapshotDiffs(Map<String, Object> snapshotValues, Map<String, Object> liveValues) {
        return fieldMapper().countSnapshotDiffs(snapshotValues, liveValues);
    }

    /**
     * Converts snapshot DTO to Map for storage in process variables.
     */
    public Map<String, Object> snapshotToMap(TaskFormSnapshot snapshot) {
        return fieldMapper().snapshotToMap(snapshot);
    }

    /**
     * Restores snapshot DTO from Map (read from process variables).
     */
    public TaskFormSnapshot mapToSnapshot(Map<String, Object> map) {
        return fieldMapper().mapToSnapshot(map);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Loads task info (taskDefinitionKey, processInstanceId) via
     * WorkflowEngineClient from Flowable.
     */
    @SuppressWarnings("unchecked")
    protected TaskInfo getTaskInfo(String taskId) {
        if (workflowEngineClient.isAvailable()) {
            Optional<Map<String, Object>> result = workflowEngineClient.getTaskById(taskId);
            if (result.isPresent()) {
                Map<String, Object> body = result.get();
                Map<String, Object> data = body.containsKey("data")
                        ? (Map<String, Object>) body.get("data")
                        : body;

                String taskDefinitionKey = (String) data.get("taskDefinitionKey");
                String processInstanceId = (String) data.get("processInstanceId");

                if (taskDefinitionKey != null && processInstanceId != null) {
                    return new TaskInfo(taskDefinitionKey, processInstanceId);
                }
            }
        }

        throw new PortalException("404", "Task not found: " + taskId);
    }

    /**
     * Loads Task Form definition by stageId (taskDefinitionKey), scoped to the process instance's function unit.
     * Prefers developer-workstation; falls back to local
     * {@code dw_form_stage_bindings} when unreachable (shared PostgreSQL with DW).
     *
     * <p>{@code processInstanceId} is what identifies the function unit: the same BPMN node id may be bound
     * in several units, so an unscoped lookup can return a different unit's form.</p>
     */
    private Map<String, Object> fetchTaskFormByStageId(String stageId, String processInstanceId) {
        return formDefinitionLoader().fetchTaskFormByStageId(stageId, processInstanceId, developerWorkstationUrl);
    }

    // ========== Inner data class ==========

    /**
     * Task info (from Flowable).
     */
    public static class TaskInfo {
        public final String taskDefinitionKey;
        public final String processInstanceId;

        public TaskInfo(String taskDefinitionKey, String processInstanceId) {
            this.taskDefinitionKey = taskDefinitionKey;
            this.processInstanceId = processInstanceId;
        }
    }
}
