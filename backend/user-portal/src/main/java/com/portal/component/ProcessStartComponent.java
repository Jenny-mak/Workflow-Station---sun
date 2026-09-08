package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.client.WorkflowEngineClient;
import com.portal.dto.ChangeHistoryContext;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.dto.ProcessStartRequest;
import com.portal.dto.SubTableChange;
import com.portal.entity.ProcessHistory;
import com.portal.entity.ProcessInstance;
import com.portal.exception.PortalException;
import com.portal.repository.ProcessHistoryRepository;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.PortalWorkspaceAuthService;
import com.portal.service.ProcessAssigneeSnapshot;
import com.portal.util.BpmnInitiatorTaskDetection;
import com.portal.util.SystemAuditFieldFiller;
import com.portal.service.UserDisplayNameResolver;
import com.platform.common.i18n.I18nService;
import com.platform.common.util.ApiResponseBodyUnwrap;
import com.platform.security.util.SecurityContextUtils;
import com.portal.util.PortalUserSecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Process start flow: active catalog pin resolution, BPMN deployment, engine
 * start,
 * first-task auto-completion and start bookkeeping. Extracted from
 * {@link ProcessComponent}
 * (the original 330-line {@code startProcess} body is decomposed into
 * sequential steps
 * with unchanged order and logic).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessStartComponent {

    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessHistoryRepository processHistoryRepository;
    private final FunctionUnitAccessComponent functionUnitAccessComponent;
    private final WorkflowEngineClient workflowEngineClient;
    private final ChangeHistoryComponent changeHistoryComponent;
    private final PortalWorkspaceAuthService portalWorkspaceAuthService;
    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final MeetingParticipantVariablesPersistence meetingParticipantVariablesPersistence;
    private final ProcessSubTablePrimaryKeyEnricherComponent processSubTablePrimaryKeyEnricherComponent;
    private final ComputedFieldRecalculator computedFieldRecalculator;
    private final OwnerFieldComponent ownerFieldComponent;
    private final TaskFormComponent taskFormComponent;
    private final UserDisplayNameResolver userDisplayNameResolver;
    private final I18nService i18nService;
    private final PlatformTransactionManager transactionManager;

    @Lazy
    @Autowired
    private ChangeHistorySubmissionFilter changeHistorySubmissionFilter;

    private ChangeHistorySubmissionFilter changeHistorySubmissionFilter() {
        ChangeHistorySubmissionFilter filter = changeHistorySubmissionFilter;
        if (filter == null) {
            filter = new ChangeHistorySubmissionFilter(jdbcTemplate, new ObjectMapper());
            changeHistorySubmissionFilter = filter;
        }
        return filter;
    }

    /** Lazy: stamps the derived Request ID; field-injected to keep ctor arity stable for tests. */
    @Lazy
    @Autowired
    private RequestIdEnricher requestIdEnricher;

    private RequestIdEnricher requestIdEnricher() {
        RequestIdEnricher r = requestIdEnricher;
        if (r == null) {
            r = new RequestIdEnricher(jdbcTemplate, new ObjectMapper(), processInstanceRepository);
            requestIdEnricher = r;
        }
        return r;
    }

    /**
     * Recompute the derived Request ID server-side, overwriting whatever the client sent.
     * See {@link RequestIdEnricher#stampRequestId(String, Map)} for why the server owns the value.
     */
    private void stampRequestId(String functionUnitCode, Map<String, Object> variables) {
        requestIdEnricher().stampRequestId(functionUnitCode, variables);
    }

    /**
     * Programmatic short transactions for the DB-write phases of a start.
     *
     * <p>
     * {@code startProcess} makes ~4-5 sequential blocking HTTP calls (admin-center
     * FU checks, engine
     * deploy/start/auto-complete). Previously the whole method was
     * {@code @Transactional}, so one JDBC
     * connection was pinned across all of that — capping start throughput at
     * {@code pool / holdTime}
     * (~30-60 TPS on pool=20; thread dumps showed most workers parked in
     * {@code Hikari.borrow} while a
     * few held a connection blocked on an engine/admin socket read). Now the HTTP
     * runs with no ambient
     * transaction and only the actual writes run inside these short
     * {@link TransactionTemplate} blocks,
     * so a connection is held for milliseconds, not the whole flow. Lazily built
     * (Lombok owns the ctor).
     * </p>
     */
    private volatile TransactionTemplate txTemplate;

    private TransactionTemplate tx() {
        TransactionTemplate t = txTemplate;
        if (t == null) {
            t = new TransactionTemplate(transactionManager);
            txTemplate = t;
        }
        return t;
    }

    /**
     * Lazy: breaks cycle with {@link ProcessComponent}, which keeps the FU content
     * cache.
     */
    @Lazy
    @Autowired
    private ProcessComponent processComponent;

    @Value("${admin-center.url:http://localhost:8090}")
    private String adminCenterUrl;

    /**
     * Deploy-once cache: {@code processKey + ":" + sha256(bpmnXml)} -> resolved
     * Flowable process definition key.
     *
     * <p>
     * Before: {@code deployProcess} was called on <em>every</em> start. A
     * 200-concurrent start burst of the
     * same function unit therefore fired 200 simultaneous engine deploys of an
     * identical BPMN; Flowable dedups
     * identical resources but concurrent deploys race on
     * {@code ACT_RE_DEPLOYMENT}/{@code ACT_RE_PROCDEF}
     * (unique key / optimistic lock / deadlock) and surface to the portal as HTTP
     * 500 =
     * {@code portal.deploy_process_failed}. Keying by BPMN content hash means
     * exactly one request deploys an
     * unchanged definition and every other reuses the resolved key; a changed BPMN
     * (new hash) redeploys once.
     * Entry is evicted if the subsequent start fails, so a lost/rolled-back engine
     * deployment self-heals.
     * </p>
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> deployedProcessKeyCache = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Per-cache-key locks so concurrent starts of the same definition serialize the
     * single deploy (not the map bin).
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> deployLocks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Starts process
     * Via WorkflowEngineClient calling Flowable engine
     */
    // NOT @Transactional: the ~4-5 engine/admin HTTP calls below must not run while
    // holding a DB
    // connection (that pins the pool and throttles start throughput). DB writes are
    // confined to the
    // short tx() blocks; all HTTP runs connection-free. See txTemplate javadoc.
    public ProcessInstanceInfo startProcess(String userId, String processKey, ProcessStartRequest request) {
        if (processKey == null || processKey.isEmpty()) {
            throw new IllegalArgumentException("Process key cannot be empty");
        }
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be empty");
        }

        ActiveCatalogPin pin = resolveActiveCatalogPin(userId, processKey);

        LoadedStartDefinition def = loadProcessDefinitionForStart(pin, processKey);

        // actualProcessKey is the key used to start (Flowable uses BPMN <process id>)
        final String actualProcessKey = deployAndResolveProcessKey(processKey, def);

        // Start instance: strip forged workspace keys; UBR users need valid JWT
        // workspace context (matches hasContext)
        Map<String, Object> submittedSnapshot = changeHistorySubmissionFilter().copyPayload(request.getFormData());
        Map<String, Object> variables = changeHistorySubmissionFilter().copyPayload(request.getFormData());
        variables.remove("activeBusinessUnitId");
        variables.remove("activeRoleId");
        applyCatalogContextToVariables(variables, userId, pin.catalogId(), pin.code());
        // Demo FU fu-20260403-a1b2c5: assign-participants node uses INITIATOR; scripts
        // still read participant_assigner_user_id
        if ("fu-20260403-a1b2c5".equals(pin.code())) {
            variables.put("participant_assigner_user_id", userId);
        }
        applyWorkspaceContextVariables(userId, variables);
        processSubTablePrimaryKeyEnricherComponent.allocateMissingPrimaryKeysInVariables(pin.code(), variables);
        // Owner fields: Creator uses startUserId; Current Assignee waits for the
        // first-task snapshot written after persist.
        ownerFieldComponent.applyOnSubmit(pin.code(),
                new OwnerFieldComponent.OwnerWriteContext(userId, userId, null, null, null), variables);
        // System audit fields are platform-managed: written at real insert regardless of
        // Form Design canvas (audit widgets are stripped from the designer by design).
        String startUserDisplayName = userDisplayNameResolver.resolve(userId);
        SystemAuditFieldFiller.fillOnInsert(variables, startUserDisplayName);
        computedFieldRecalculator.recalculate(pin.code(), variables);
        stampRequestId(pin.code(), variables);
        Map<String, Object> userChanges = changeHistorySubmissionFilter().filterProcessSubmission(
                pin.code(), submittedSnapshot, variables);
        Map<String, Object> data;
        try {
            data = workflowEngineClient.startProcess(
                    actualProcessKey, request.getBusinessKey(), userId, variables);
        } catch (RuntimeException ex) {
            // If the start failed (e.g. engine no longer has the deployment we cached),
            // drop the cache entry
            // so the next attempt redeploys instead of being permanently poisoned by a
            // stale "deployed" flag.
            evictDeploymentCache(processKey, def.bpmnXml());
            throw ex;
        }
        if (data == null || data.get("processInstanceId") == null) {
            evictDeploymentCache(processKey, def.bpmnXml());
            throw new IllegalStateException("Process start returned empty data: " + processKey);
        }
        String flowableProcessInstanceId = (String) data.get("processInstanceId");
        log.info("Process started via Flowable: {}", flowableProcessInstanceId);
        // TX1 (short): persist instance as RUNNING so ProcessCompletionListener
        // callback finds a row.
        // Committing here (before auto-complete) is stronger than the old single-tx
        // behaviour, where the
        // row was not visible until the whole method committed after all the engine
        // round-trips.
        tx().executeWithoutResult(status -> persistRunningProcessInstance(
                flowableProcessInstanceId, data, processKey, def.processName(), request,
                userId, startUserDisplayName, variables, pin));
        // Engine HTTP (claim/complete first task, query next) — runs with NO ambient tx
        // / no connection held.
        // Its own internal writes are individually transactional
        // (captureTaskFormSnapshot is @Transactional;
        // the meeting-path save auto-commits); autoCompleteFirstTask never throws (see
        // its catch).
        FirstTaskOutcome outcome = autoCompleteFirstTask(flowableProcessInstanceId, userId, variables, pin);
        // TX2 (short): change history + current-node update grouped atomically (the
        // node update is a
        // @Modifying query that requires a transaction). First task completed via
        // engine API.
        tx().executeWithoutResult(status -> {
            recordInitialSubmitChangeHistory(
                    flowableProcessInstanceId,
                    outcome.initiatorTaskIdForHistory,
                    outcome.initiatorTaskDefKeyForHistory,
                    userId,
                    userChanges);
            updateInstanceNodeAndRecordStartHistory(flowableProcessInstanceId, outcome, userId, startUserDisplayName);
        });

        return buildStartResult(flowableProcessInstanceId, data, processKey, def.processName(), request,
                userId, startUserDisplayName, outcome, pin);
    }

    record ActiveCatalogPin(String catalogId, String code, String versionLabel) {
    }

    /**
     * Process name / BPMN XML / deployed process key loaded from FU content for one
     * start request.
     */
    private record LoadedStartDefinition(String processName, String bpmnXml, String flowableProcessKey) {
    }

    /**
     * Mutable holder mirroring the original {@code startProcess} locals around
     * first-task auto-completion.
     */
    private static final class FirstTaskOutcome {
        String currentNodeName;
        ProcessAssigneeSnapshot nextAssigneeSnapshot = ProcessAssigneeSnapshot.empty();
        String initiatorTaskIdForHistory;
        String initiatorTaskDefKeyForHistory;
        /** Non-null when auto-completion of the first task failed; surfaced to the caller. */
        String firstStepError;
    }

    /**
     * Catalog context required by Send Email delegate and other FU-scoped service
     * tasks.
     */
    static void applyCatalogContextToVariables(
            Map<String, Object> variables, String userId, String catalogId, String catalogCode) {
        variables.put("initiator", userId);
        variables.put("functionUnitId", catalogId);
        variables.put("functionUnitCode", catalogCode);
    }

    private ActiveCatalogPin resolveActiveCatalogPin(String userId, String processKey) {
        Optional<ActiveCatalogPin> activePinOpt = fetchActiveCatalogForStart(processKey);
        if (activePinOpt.isEmpty()) {
            throw new IllegalStateException(
                    "No deployed and enabled function unit version available for this process. Please deploy and activate in Admin Center: "
                            + processKey);
        }
        ActiveCatalogPin pin = activePinOpt.get();

        String resolvedFunctionUnitId = functionUnitAccessComponent.resolveFunctionUnitId(processKey);
        if (!pin.catalogId().equals(resolvedFunctionUnitId)) {
            throw new FunctionUnitAccessComponent.FunctionUnitAccessDeniedException(
                    "Portal startable version mismatch. Please refresh the process list and try again");
        }

        functionUnitAccessComponent.checkFunctionUnitAccess(userId, pin.catalogId());
        return pin;
    }

    private LoadedStartDefinition loadProcessDefinitionForStart(ActiveCatalogPin pin, String processKey) {
        // Load process definition name and BPMN XML
        String processName = processKey;
        String bpmnXml = null;

        // Prefer deployed process definition key from API response (most reliable).
        // admin-center API returns this in
        // ProcessContentDTO.flowableProcessDefinitionKey.
        String flowableProcessKey = null;

        try {
            Map<String, Object> content = processComponent.getFunctionUnitContent(pin.catalogId());
            if (content != null) {
                if (content.get("name") != null) {
                    processName = (String) content.get("name");
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> processes = (List<Map<String, Object>>) content.get("processes");
                if (processes != null && !processes.isEmpty()) {
                    // Read deployed process key from API response (most reliable)
                    Object flowableKeyObj = processes.get(0).get("flowableProcessDefinitionKey");
                    if (flowableKeyObj != null) {
                        flowableProcessKey = flowableKeyObj.toString();
                    }
                    Object dataObj = processes.get(0).get("data");
                    bpmnXml = (String) dataObj;
                }
            }
        } catch (FunctionUnitAccessComponent.FunctionUnitDisabledException
                | FunctionUnitAccessComponent.FunctionUnitAccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to get process info for {}: {}", processKey, e.getMessage(), e);
        }

        if (bpmnXml == null) {
            throw new IllegalStateException("Cannot obtain process definition BPMN: " + processKey);
        }
        return new LoadedStartDefinition(processName, bpmnXml, flowableProcessKey);
    }

    private String deployAndResolveProcessKey(String processKey, LoadedStartDefinition def) {
        // Check Flowable engine availability
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException(
                    "Workflow engine unavailable, please check if workflow-engine-core service is running");
        }

        log.info("Using Flowable engine to start process: {}", processKey);

        String flowableProcessKey = def.flowableProcessKey();
        // If API lacks flowableProcessDefinitionKey, extract from BPMN XML
        if (flowableProcessKey == null || flowableProcessKey.isEmpty()) {
            flowableProcessKey = extractProcessIdFromBpmn(def.bpmnXml());
            if (flowableProcessKey == null || flowableProcessKey.isEmpty()) {
                throw new IllegalStateException(
                        i18nService.getMessage("portal.process.bpmn_process_id_missing", processKey));
            }
        }

        // Deploy once per unchanged BPMN (see deployedProcessKeyCache). Fast path:
        // already deployed.
        final String cacheKey = deploymentCacheKey(processKey, def.bpmnXml());
        String cached = deployedProcessKeyCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        // Double-checked locking on a per-key lock: concurrent starts of the same
        // definition wait for the
        // single in-flight deploy instead of each POSTing /deploy (which is what raced
        // and 500'd under load).
        Object lock = deployLocks.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            cached = deployedProcessKeyCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            Optional<Map<String, Object>> deployResult = workflowEngineClient.deployProcess(processKey, def.bpmnXml(),
                    def.processName());
            if (deployResult.isEmpty()) {
                throw new IllegalStateException("Process deployment returned empty data: " + processKey);
            }
            Map<String, Object> deployed = deployResult.get();
            log.info("Process definition deployed: {}", deployed);
            // deployResult processDefinitionKey from Flowable response, validated
            Object deployedKey = deployed.get("processDefinitionKey");
            if (deployedKey != null && !deployedKey.toString().isEmpty()) {
                flowableProcessKey = deployedKey.toString();
                log.info("Using actual process definition key from deployment response: [{}]", flowableProcessKey);
            }
            deployedProcessKeyCache.put(cacheKey, flowableProcessKey);
            return flowableProcessKey;
        }
    }

    /**
     * Cache key for a deployed definition: process key + BPMN content hash (a
     * changed BPMN redeploys once).
     */
    private static String deploymentCacheKey(String processKey, String bpmnXml) {
        return processKey + ":" + sha256(bpmnXml);
    }

    /**
     * Force a redeploy of this definition on the next start (e.g. after a start
     * failed because the engine
     * lost/rolled back the deployment). Cheap no-op if the entry was never cached.
     */
    private void evictDeploymentCache(String processKey, String bpmnXml) {
        deployedProcessKeyCache.remove(deploymentCacheKey(processKey, bpmnXml));
    }

    private static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is always present on a JRE; fall back to identity hash rather than
            // fail the start.
            return Integer.toHexString(java.util.Objects.hashCode(s));
        }
    }

    private void applyWorkspaceContextVariables(String userId, Map<String, Object> variables) {
        List<PortalWorkspaceAuthService.WorkspaceContextRow> wctx = portalWorkspaceAuthService
                .listWorkspaceContexts(userId);
        boolean jwtUserMatches = SecurityContextUtils.getCurrentUserId().map(uid -> uid.equals(userId)).orElse(false);
        if (!jwtUserMatches && userId != null && !userId.isEmpty()) {
            log.warn("Process start: path userId={} does not match JWT subject={} — active BU will not be applied",
                    userId, SecurityContextUtils.getCurrentUserId().orElse(null));
        }

        if (!wctx.isEmpty()) {
            if (!jwtUserMatches) {
                throw new PortalException("400",
                        "Cannot verify logged-in identity with workspace, please re-login to start process");
            }
            String jwtBu = PortalUserSecurityUtils.getCurrentActiveBusinessUnitId().orElse("").trim();
            String jwtRole = PortalUserSecurityUtils.getCurrentActiveRoleId().orElse("").trim();
            if (jwtBu.isEmpty() || jwtRole.isEmpty()) {
                throw new PortalException("400",
                        "Your account is associated with a business unit role. Please login first and select a workspace from the top, or re-login and try again");
            }
            if (!portalWorkspaceAuthService.hasContext(userId, jwtBu, jwtRole)) {
                throw new PortalException("400",
                        "Current workspace identity has expired (permissions may have been adjusted), please switch workspace or re-login and try again");
            }
            variables.put("activeBusinessUnitId", jwtBu);
        } else {
            if (jwtUserMatches) {
                PortalUserSecurityUtils.getCurrentActiveBusinessUnitId()
                        .map(String::trim)
                        .filter(bu -> !bu.isEmpty())
                        .ifPresent(bu -> variables.put("activeBusinessUnitId", bu));
            }
        }
    }

    private void persistRunningProcessInstance(
            String flowableProcessInstanceId, Map<String, Object> data, String processKey, String processName,
            ProcessStartRequest request, String userId, String startUserDisplayName,
            Map<String, Object> variables, ActiveCatalogPin pin) {
        ProcessInstance processInstance = ProcessInstance.builder()
                .id(flowableProcessInstanceId)
                .processInstanceId(flowableProcessInstanceId)
                .processDefinitionId((String) data.get("processDefinitionId"))
                .processDefinitionKey(processKey)
                .processDefinitionName(processName)
                .businessKey(request.getBusinessKey())
                .initiatorId(userId)
                .startUserId(userId)
                .startUserName(startUserDisplayName)
                .status("RUNNING")
                .currentNode(null)
                .currentAssignee(null)
                .variables(variables)
                .functionUnitCatalogId(pin.catalogId())
                .functionUnitCode(pin.code())
                .functionUnitVersionLabel(pin.versionLabel())
                .build();
        processInstanceRepository.save(processInstance);
        log.info("Process instance pre-saved to local database: {}", flowableProcessInstanceId);
    }

    private FirstTaskOutcome autoCompleteFirstTask(
            String flowableProcessInstanceId, String userId, Map<String, Object> variables, ActiveCatalogPin pin) {
        // Auto-complete only when the first user task is a true initiator / start-form
        // node.
        // Approval-first flows (BU_ROLE, INITIATOR_BU_ROLE, etc.) must stay open for
        // assignees.
        FirstTaskOutcome outcome = new FirstTaskOutcome();

        try {
            Optional<Map<String, Object>> tasksResult = workflowEngineClient
                    .getProcessInstanceTasks(flowableProcessInstanceId);
            if (tasksResult.isPresent()) {
                Map<String, Object> tasksData = tasksResult.get();
                if (tasksData != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tasks = (List<Map<String, Object>>) tasksData.get("tasks");
                    if (tasks != null && !tasks.isEmpty()) {
                        Map<String, Object> firstTask = tasks.get(0);
                        if (BpmnInitiatorTaskDetection.shouldAutoCompleteFirstTask(firstTask)) {
                            completeFirstTaskAndCaptureNext(
                                    outcome, firstTask, flowableProcessInstanceId, userId, variables, pin);
                        } else {
                            captureFirstTaskWithoutAutoComplete(outcome, firstTask);
                        }
                    } else {
                        log.warn("No tasks found for process instance: {}", flowableProcessInstanceId);
                    }
                }
            } else {
                log.warn("Failed to get tasks for process instance: {}", flowableProcessInstanceId);
            }
        } catch (Exception e) {
            log.warn("Failed to auto-complete first task: {}", e.getMessage());
            // Do not throw; the instance already exists and its first task stays open for retry.
            // Still report it — swallowing this is what made a failed submission look successful.
            if (outcome.firstStepError == null) {
                outcome.firstStepError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
        }
        return outcome;
    }

    private void captureFirstTaskWithoutAutoComplete(FirstTaskOutcome outcome, Map<String, Object> firstTask) {
        outcome.currentNodeName = (String) firstTask.get("taskName");
        outcome.nextAssigneeSnapshot = ProcessAssigneeSnapshot.fromEngineTask(firstTask);
        log.info(
                "Skipping auto-complete for first approval task: node={}, assignee={}, candidates={}, bpmnAssigneeType={}",
                outcome.currentNodeName,
                outcome.nextAssigneeSnapshot.getAssigneeUserId(),
                outcome.nextAssigneeSnapshot.getCandidateUserIds(),
                firstTask.get("bpmnAssigneeType"));
    }

    private void completeFirstTaskAndCaptureNext(
            FirstTaskOutcome outcome, Map<String, Object> firstTask, String flowableProcessInstanceId,
            String userId, Map<String, Object> variables, ActiveCatalogPin pin) {
        String taskId = (String) firstTask.get("taskId");
        outcome.initiatorTaskIdForHistory = taskId;
        Object taskDefKey = firstTask.get("taskDefinitionKey");
        outcome.initiatorTaskDefKeyForHistory = taskDefKey != null ? taskDefKey.toString() : null;
        log.info("Auto-completing first task: {} for process: {}", taskId, flowableProcessInstanceId);

        // Claim task first (set assignee)
        Optional<Map<String, Object>> claimResult = workflowEngineClient.claimTask(taskId, userId);
        if (claimResult.isPresent()) {
            log.info("First task claimed successfully: {} by user: {}", taskId, userId);
        } else {
            log.warn("Failed to claim first task: {}, trying to complete anyway", taskId);
        }

        Object firstTaskDefKeyObj = firstTask.get("taskDefinitionKey");
        String firstTaskDefKey = firstTaskDefKeyObj != null ? firstTaskDefKeyObj.toString() : "";
        if ("Task_CreateMeeting".equals(firstTaskDefKey)) {
            meetingParticipantVariablesPersistence.persistIfApplicable(
                    flowableProcessInstanceId, variables, pin.code());
            processInstanceRepository.findById(flowableProcessInstanceId).ifPresent(pi -> {
                pi.setVariables(new HashMap<>(variables));
                processInstanceRepository.save(pi);
            });
        }

        // User field may deserialize as { userId } without id; Map#toString can exceed
        // Flowable varchar(255)
        coerceUserRefVariablesForEngine(variables);

        // Compute sub-table condition variables (e.g. requestItemsHasHighValue) and
        // inject
        computeSubTableConditionVariables(variables);

        // Complete first task
        Optional<Map<String, Object>> completeResult = workflowEngineClient.completeTask(
                taskId, userId, "SUBMIT", variables);
        String failure = firstStepErrorOf(completeResult);
        if (failure == null) {
            log.info("First task completed successfully: {}", taskId);
            taskFormComponent.captureTaskFormSnapshot(
                    taskId, userId, firstTaskDefKey, flowableProcessInstanceId, variables);

            // After first task, query current task (next approval node)
            captureNextTaskAfterAutoComplete(outcome, flowableProcessInstanceId);
        } else {
            log.warn("Failed to complete first task: {} — {}", taskId, failure);
            outcome.firstStepError = failure;
        }
    }

    /** Opaque marker returned to the browser; the real reason stays in the server log. */
    static final String FIRST_STEP_NOT_COMPLETED = "FIRST_STEP_NOT_COMPLETED";

    /**
     * Whether the first task failed to auto-complete: {@code null} when it completed, otherwise the
     * engine's reason — <b>for logging only</b>, see {@link #FIRST_STEP_NOT_COMPLETED}.
     *
     * <p>The instance exists and the task is back in the initiator's To Do, so this is recoverable
     * and {@code /start} must not fail — but it is not a successful submission either. The caller
     * needs to know so it can say "created, first step did not complete" instead of "submitted
     * successfully"; a WARN in the log is invisible to whoever clicked Submit.
     */
    static String firstStepErrorOf(Optional<Map<String, Object>> completeResult) {
        if (completeResult.isEmpty() || completeResult.get() == null) {
            return "no response from workflow engine";
        }
        Map<String, Object> body = completeResult.get();
        if (!Boolean.FALSE.equals(body.get("success"))) {
            return null;
        }
        Object message = body.get("message");
        String text = message != null ? message.toString().trim() : "";
        return text.isEmpty() ? "workflow engine rejected task completion" : text;
    }

    private void captureNextTaskAfterAutoComplete(FirstTaskOutcome outcome, String flowableProcessInstanceId) {
        Optional<Map<String, Object>> nextTasksResult = workflowEngineClient
                .getProcessInstanceTasks(flowableProcessInstanceId);
        if (nextTasksResult.isPresent()) {
            Map<String, Object> nextTasksData = nextTasksResult.get();
            if (nextTasksData != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> nextTasks = (List<Map<String, Object>>) nextTasksData.get("tasks");
                if (nextTasks != null && !nextTasks.isEmpty()) {
                    Map<String, Object> currentTask = nextTasks.get(0);
                    outcome.currentNodeName = (String) currentTask.get("taskName");
                    outcome.nextAssigneeSnapshot = ProcessAssigneeSnapshot.fromEngineTask(currentTask);
                    log.info("Current task after auto-complete: node={}, assignee={}, candidates={}",
                            outcome.currentNodeName,
                            outcome.nextAssigneeSnapshot.getAssigneeUserId(),
                            outcome.nextAssigneeSnapshot.getCandidateUserIds());
                }
            }
        }
    }

    private void updateInstanceNodeAndRecordStartHistory(
            String flowableProcessInstanceId, FirstTaskOutcome outcome, String userId, String startUserDisplayName) {
        // Update process instance (current node and assignee)
        // Conditional UPDATE avoids overwriting COMPLETED set by
        // ProcessCompletionListener (race)
        // JPA L1 cache stale findById — use @Modifying native update to bypass cache
        int updated = processInstanceRepository.updateCurrentNodeAndAssigneesIfNotCompleted(
                flowableProcessInstanceId,
                outcome.currentNodeName,
                outcome.nextAssigneeSnapshot.getAssigneeUserId(),
                outcome.nextAssigneeSnapshot.getCandidateUserIds());
        if (updated > 0) {
            log.info("Process instance updated in local database: {} with currentNode={}, assignee={}, candidates={}",
                    flowableProcessInstanceId, outcome.currentNodeName,
                    outcome.nextAssigneeSnapshot.getAssigneeUserId(),
                    outcome.nextAssigneeSnapshot.getCandidateUserIds());
            processInstanceRepository.findById(flowableProcessInstanceId).ifPresent(instance -> {
                Map<String, Object> vars = instance.getVariables() == null
                        ? new HashMap<>()
                        : new HashMap<>(instance.getVariables());
                ownerFieldComponent.applyAssigneeSnapshot(
                        instance.getFunctionUnitCode(),
                        vars,
                        outcome.nextAssigneeSnapshot.getAssigneeUserId(),
                        outcome.nextAssigneeSnapshot.getCandidateUserIds());
                instance.setVariables(vars);
                processInstanceRepository.save(instance);
            });
        } else {
            log.info("Process instance {} already COMPLETED, skipped currentNode update (race condition avoided)",
                    flowableProcessInstanceId);
        }

        // Record process start history
        ProcessHistory startHistory = ProcessHistory.builder()
                .processInstanceId(flowableProcessInstanceId)
                .activityId("startEvent")
                .activityName(i18nService.getMessage("portal.process.history.submit_application"))
                .activityType("startEvent")
                .operationType("SUBMIT")
                .operatorId(userId)
                .operatorName(startUserDisplayName)
                .comment(i18nService.getMessage("portal.process.history.start_comment"))
                .build();
        processHistoryRepository.save(startHistory);
    }

    private ProcessInstanceInfo buildStartResult(
            String flowableProcessInstanceId, Map<String, Object> data, String processKey, String processName,
            ProcessStartRequest request, String userId, String startUserDisplayName,
            FirstTaskOutcome outcome, ActiveCatalogPin pin) {
        Map<String, String> startAssigneeCache = userDisplayNameResolver.resolveBatch(
                userDisplayNameResolver.collectAssigneeUserKeys(
                        outcome.nextAssigneeSnapshot.getAssigneeUserId(),
                        outcome.nextAssigneeSnapshot.getCandidateUserIds()));
        String startAssigneeDisplay = userDisplayNameResolver.resolveCurrentAssigneeDisplay(
                outcome.nextAssigneeSnapshot.getAssigneeUserId(),
                outcome.nextAssigneeSnapshot.getCandidateUserIds(),
                startAssigneeCache);

        return ProcessInstanceInfo.builder()
                .id(flowableProcessInstanceId)
                .processDefinitionId((String) data.get("processDefinitionId"))
                .processDefinitionKey(processKey)
                .processDefinitionName(processName)
                .businessKey(request.getBusinessKey())
                .startTime(LocalDateTime.now())
                .status("RUNNING")
                .startUserId(userId)
                .startUserName(startUserDisplayName)
                .currentNode(outcome.currentNodeName)
                .currentAssignee(startAssigneeDisplay)
                .candidateUsers(outcome.nextAssigneeSnapshot.getCandidateUserIds())
                // Opaque on purpose: the engine's text names the AP sync-webhook URL, and an AP CE
                // webhook URL *is* the credential (unauthenticated, reachable through Kong at
                // /api/ap/*). Handing it to every initiator would let them fire the automation
                // directly. The full reason is in the WARN log above.
                .firstStepError(outcome.firstStepError != null ? FIRST_STEP_NOT_COMPLETED : null)
                .functionUnitCatalogId(pin.catalogId())
                .functionUnitCode(pin.code())
                .functionUnitVersionLabel(pin.versionLabel())
                .build();
    }

    /**
     * Fetches highest semantic-version deployed+enabled catalog row for code (same
     * as /deployed/latest)
     */
    private Optional<ActiveCatalogPin> fetchActiveCatalogForStart(String code) {
        try {
            String enc = URLEncoder.encode(code, StandardCharsets.UTF_8);
            String url = adminCenterUrl + "/api/v1/admin/function-units/code/" + enc + "/active-for-start";
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Optional.empty();
            }
            Map<String, Object> payload = ApiResponseBodyUnwrap.unwrapDataMap(response.getBody());
            String id = (String) payload.get("id");
            if (id == null || id.isEmpty()) {
                return Optional.empty();
            }
            Object ver = payload.get("version");
            String versionLabel = ver != null ? String.valueOf(ver) : "";
            String c = (String) payload.get("code");
            return Optional.of(new ActiveCatalogPin(id, c != null ? c : code, versionLabel));
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            log.warn("fetchActiveCatalogForStart HTTP error for {}: {}", code, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("fetchActiveCatalogForStart failed for {}: {}", code, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extracts <process id="..."> from BPMN XML
     */
    private String extractProcessIdFromBpmn(String bpmnXml) {
        if (bpmnXml == null)
            return null;
        try {
            int processTagIdx = bpmnXml.indexOf("<process ");
            if (processTagIdx == -1) {
                processTagIdx = bpmnXml.indexOf("<bpmn:process ");
            }
            if (processTagIdx == -1)
                return null;
            int tagEnd = bpmnXml.indexOf('>', processTagIdx);
            if (tagEnd == -1)
                return null;
            String tag = bpmnXml.substring(processTagIdx, tagEnd + 1);
            return ProcessStartAssigneeResolver.extractAttribute(tag, "id");
        } catch (Exception e) {
            log.warn("Failed to extract process id from BPMN: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Writes start-form variables to up_change_history for application detail
     * change-history display.
     * Records only user-visible form fields; excludes engine/snapshot internal keys
     * to reduce noise.
     */
    private void recordInitialSubmitChangeHistory(String processInstanceId,
            String taskInstanceId,
            String taskDefinitionKey,
            String userId,
            Map<String, Object> userChanges) {
        if (userChanges == null || userChanges.isEmpty()) {
            return;
        }
        ChangeHistoryContext context = ChangeHistoryContext.builder()
                .processInstanceId(processInstanceId)
                .taskInstanceId(taskInstanceId)
                .stageId(taskDefinitionKey)
                .userId(userId)
                .build();
        // Record top-level field changes
        try {
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : userChanges.entrySet()) {
                String k = e.getKey();
                if ("__subTables__".equals(k)) {
                    continue; // handled separately below
                }
                filtered.put(k, e.getValue());
            }
            if (!filtered.isEmpty()) {
                changeHistoryComponent.recordFieldChanges(context, Collections.emptyMap(), filtered);
            }
        } catch (Exception e) {
            log.warn("Failed to record initial submit change history for process {}: {}",
                    processInstanceId, e.getMessage());
        }
        // Record sub-table (subform) rows submitted during process initiation as
        // ROW_ADD.
        // Text-key aliases are preferred; when only numeric binding IDs are present,
        // merge
        // all rows into one comparison keyed by the first binding ID to avoid
        // duplication.
        try {
            Object subTablesObj = userChanges.get("__subTables__");
            if (subTablesObj instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> subTables = (Map<String, Object>) subTablesObj;
                // Separate numeric (binding ID) keys from text (table name) keys
                List<String> numericKeys = new ArrayList<>();
                List<String> textKeys = new ArrayList<>();
                for (String key : subTables.keySet()) {
                    if (key.matches("\\d+")) {
                        numericKeys.add(key);
                    } else {
                        textKeys.add(key);
                    }
                }
                if (!textKeys.isEmpty()) {
                    // Text keys exist — record only those (normalization merges aliases, dedup
                    // handles duplicates)
                    for (String subTableKey : textKeys) {
                        recordStartSubTableAdds(context, subTables, subTableKey);
                    }
                } else if (!numericKeys.isEmpty()) {
                    // Only numeric keys — merge all rows and record under one virtual name
                    List<SubTableChange> allChanges = new ArrayList<>();
                    java.util.Set<Object> seen = new java.util.HashSet<>();
                    for (String subTableKey : numericKeys) {
                        Object rowsObj = subTables.get(subTableKey);
                        if (!(rowsObj instanceof List<?>))
                            continue;
                        for (Object row : (List<?>) rowsObj) {
                            if (!(row instanceof Map<?, ?>))
                                continue;
                            @SuppressWarnings("unchecked")
                            Map<String, Object> rowMap = (Map<String, Object>) row;
                            // Identity comes from the key this slice's table declares, resolved per
                            // slice — a table keyed by `correspondence_id` is matched by no list of
                            // likely column names.
                            String rowId = ChangeHistoryComponent.resolveRowIdentifier(rowMap,
                                    changeHistoryComponent.designerPrimaryKeyFieldsForSliceKey(subTableKey));
                            if (rowId != null && seen.add(rowId)) {
                                allChanges.add(SubTableChange.builder()
                                        .changeType("ROW_ADD")
                                        .rowIdentifier(rowId)
                                        .oldValues(null)
                                        .newValues(new HashMap<>(rowMap))
                                        .build());
                            }
                        }
                    }
                    if (!allChanges.isEmpty()) {
                        changeHistoryComponent.recordSubTableChangesWithName(
                                context, numericKeys.get(0), allChanges);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to record initial sub-table change history for process {}: {}",
                    processInstanceId, e.getMessage());
        }
    }

    private void recordStartSubTableAdds(ChangeHistoryContext context,
            Map<String, Object> subTables, String subTableKey) {
        Object rowsObj = subTables.get(subTableKey);
        if (!(rowsObj instanceof List<?>))
            return;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rowsObj;
        if (rows.isEmpty())
            return;
        List<SubTableChange> changes = new ArrayList<>();
        // Resolved once per slice: identity is the primary key this table declares, not a column
        // whose name happens to look like one.
        List<String> pkFields = changeHistoryComponent.designerPrimaryKeyFieldsForSliceKey(subTableKey);
        for (Map<String, Object> row : rows) {
            String rowId = ChangeHistoryComponent.resolveRowIdentifier(row, pkFields);
            changes.add(SubTableChange.builder()
                    .changeType("ROW_ADD")
                    .rowIdentifier(rowId)
                    .oldValues(null)
                    .newValues(new HashMap<>(row))
                    .build());
        }
        changeHistoryComponent.recordSubTableChanges(context, subTableKey, changes);
    }

    /**
     * Coerces portal form "user" objects (id / userId / user_id / value) to plain
     * strings so workflow-engine
     * ASSIGNEE_FROM_VARIABLE parse failures or oversized identifiers.
     */
    private void coerceUserRefVariablesForEngine(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return;
        }
        String[] keys = { "participant_assigner_user_id" };
        for (String key : keys) {
            Object v = variables.get(key);
            if (!(v instanceof Map<?, ?> m)) {
                continue;
            }
            String uid = null;
            for (String k : new String[] { "id", "userId", "user_id", "value" }) {
                Object part = m.get(k);
                if (part != null) {
                    String s = String.valueOf(part).trim();
                    if (!s.isEmpty()) {
                        uid = s;
                        break;
                    }
                }
            }
            if (uid != null) {
                if (uid.length() > 255) {
                    log.warn("coerceUserRefVariablesForEngine: {} value longer than 255 chars, skip coerce", key);
                } else {
                    variables.put(key, uid);
                }
            }
        }
    }

    /**
     * Computes sub-table condition variables from form data and injects them for
     * gateway conditions.
     * Supported variables:
     * requestItemsHasHighValue — any row with total_price > 10000 (Boolean)
     * totalPrice — sum of total_price (Double, for BPMN comparisons)
     * maxItemPrice — max total_price across rows (Double)
     * itemCount — sub-table row count (Integer)
     */
    @SuppressWarnings("unchecked")
    private void computeSubTableConditionVariables(Map<String, Object> variables) {
        try {
            Object subTablesObj = variables.get("__subTables__");
            if (!(subTablesObj instanceof Map)) {
                variables.put("requestItemsHasHighValue", false);
                variables.put("totalPrice", 0.0);
                variables.put("maxItemPrice", 0.0);
                variables.put("itemCount", 0);
                log.info("[PriceCheck] No __subTables__ found, all price variables set to 0/false");
                return;
            }
            Map<String, Object> subTables = (Map<String, Object>) subTablesObj;

            boolean hasHighValue = false;
            double totalPrice = 0.0;
            double maxItemPrice = 0.0;
            int itemCount = 0;

            for (Object tableData : subTables.values()) {
                if (!(tableData instanceof List))
                    continue;
                List<Object> rows = (List<Object>) tableData;
                for (Object rowObj : rows) {
                    if (!(rowObj instanceof Map))
                        continue;
                    Map<String, Object> row = (Map<String, Object>) rowObj;
                    itemCount++;

                    // Support both snake_case and camelCase field names for total_price
                    Object priceVal = row.get("total_price");
                    if (priceVal == null)
                        priceVal = row.get("totalPrice");
                    if (priceVal == null)
                        priceVal = row.get("total_Price");
                    // Also check unit_price as fallback
                    if (priceVal == null)
                        priceVal = row.get("unit_price");
                    if (priceVal == null)
                        priceVal = row.get("unitPrice");
                    if (priceVal == null)
                        continue;

                    double price = 0;
                    if (priceVal instanceof Number) {
                        price = ((Number) priceVal).doubleValue();
                    } else {
                        try {
                            price = Double.parseDouble(priceVal.toString());
                        } catch (NumberFormatException e) {
                            log.debug("Failed to parse price value: {}", priceVal);
                        }
                    }

                    totalPrice += price;
                    if (price > maxItemPrice) {
                        maxItemPrice = price;
                    }
                    if (price > 10000) {
                        hasHighValue = true;
                    }
                }
            }

            variables.put("requestItemsHasHighValue", hasHighValue);
            variables.put("totalPrice", totalPrice);
            variables.put("maxItemPrice", maxItemPrice);
            variables.put("itemCount", itemCount);
            log.info("[PriceCheck] requestItemsHasHighValue={}, totalPrice={}, maxItemPrice={}, itemCount={}",
                    hasHighValue, totalPrice, maxItemPrice, itemCount);
        } catch (Exception e) {
            log.warn("[PriceCheck] Failed to compute price condition variables: {}", e.getMessage());
            variables.put("requestItemsHasHighValue", false);
            variables.put("totalPrice", 0.0);
            variables.put("maxItemPrice", 0.0);
            variables.put("itemCount", 0);
        }
    }
}
