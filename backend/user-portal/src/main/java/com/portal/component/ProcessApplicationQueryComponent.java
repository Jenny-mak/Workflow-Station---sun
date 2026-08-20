package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.component.MiOverlaySupport.MiRowProgress;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.entity.ProcessInstance;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.component.MainTableViewAccessResolver.AccessRule;
import com.portal.exception.PortalException;
import com.portal.service.ProcessAssigneeSnapshot;
import com.portal.service.UserDisplayNameResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * "My applications" list / process detail queries and participant checks.
 * Extracted from {@link ProcessComponent}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessApplicationQueryComponent {

    private final ProcessInstanceRepository processInstanceRepository;
    private final WorkflowEngineClient workflowEngineClient;
    private final EngineSubTableHydrator engineSubTableHydrator;
    private final UserDisplayNameResolver userDisplayNameResolver;
    private final MiOverlayComponent miOverlayComponent;
    private final SubTableEnrichmentComponent subTableEnrichmentComponent;
    private final RequestIdEnricher requestIdEnricher;
    private final MainTableViewInvolvementChecker mainTableViewInvolvementChecker;
    private final MainTableViewAccessResolver mainTableViewAccessResolver;
    private final JdbcTemplate jdbcTemplate;
    private final MiBpmnNameMapCache miBpmnNameMapCache = new MiBpmnNameMapCache();

    /** 聚合扇出线程池（引擎 HTTP，不碰 DB），移出共享 commonPool。见 {@link com.portal.config.PortalAsyncConfig}。 */
    @Autowired
    @Qualifier(com.portal.config.PortalAsyncConfig.AGGREGATION_EXECUTOR)
    private java.util.concurrent.Executor aggregationExecutor;

    /**
     * For running processes with incomplete local assignee data, backfill user/candidate ids from engine and persist.
     *
     * <p>The per-instance engine task lookup is a network round-trip; doing them sequentially makes the
     * "my applications" list O(N) HTTP calls in series (slow when a page has many running rows). We fan the
     * read-only fetches out concurrently, then apply the mutations + saves on the calling thread so JPA
     * writes keep their original single-threaded semantics.</p>
     */
    void enrichRunningAssigneesFromEngine(List<ProcessInstance> instances) {
        if (instances == null || instances.isEmpty() || !workflowEngineClient.isAvailable()) {
            return;
        }
        List<ProcessInstance> needEnrich = instances.stream()
                .filter(inst -> inst != null && "RUNNING".equals(inst.getStatus()))
                .filter(ListAssigneeEnrichmentPolicy::needsEngineBackfill)
                .toList();
        if (needEnrich.isEmpty()) {
            return;
        }

        // Fan out the read-only engine lookups concurrently.
        Map<ProcessInstance, CompletableFuture<Optional<ProcessAssigneeSnapshot>>> futures = new LinkedHashMap<>();
        for (ProcessInstance instance : needEnrich) {
            futures.put(instance, CompletableFuture.supplyAsync(() -> fetchAssigneeSnapshot(instance.getId()), aggregationExecutor));
        }

        // Apply mutations + persist on the calling thread (preserves prior write ordering / tx behavior).
        for (Map.Entry<ProcessInstance, CompletableFuture<Optional<ProcessAssigneeSnapshot>>> entry : futures.entrySet()) {
            ProcessInstance instance = entry.getKey();
            try {
                Optional<ProcessAssigneeSnapshot> snapshotOpt = entry.getValue().join();
                if (snapshotOpt.isEmpty()) {
                    continue;
                }
                ProcessAssigneeSnapshot snapshot = snapshotOpt.get();
                if (!ListAssigneeEnrichmentPolicy.applySnapshot(instance, snapshot)) {
                    continue;
                }
                persistAssigneeColumns(instance);
                log.debug("Enriched assignee snapshot for process {}: assignee={}, candidates={}",
                        instance.getId(), snapshot.getAssigneeUserId(), snapshot.getCandidateUserIds());
            } catch (Exception e) {
                log.warn("Failed to enrich assignee from engine for process {}: {}",
                        instance.getId(), e.getMessage());
            }
        }
    }

    /**
     * Two-column write so a list projection that omitted {@code __subTables__} cannot persist
     * a stripped variables blob through JPA {@code save}.
     */
    private void persistAssigneeColumns(ProcessInstance instance) {
        int updated = jdbcTemplate.update(
                "UPDATE up_process_instance SET current_assignee = ?, candidate_users = ?,"
                        + " lock_version = COALESCE(lock_version, 0) + 1 WHERE id = ?",
                instance.getCurrentAssignee(), instance.getCandidateUsers(), instance.getId());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Assignee snapshot update touched " + updated + " rows for " + instance.getId());
        }
    }

    /**
     * Read-only engine lookup of the current task assignee snapshot for a process instance.
     * Returns empty if the engine has no usable assignee/candidate data (caller skips the row).
     */
    private Optional<ProcessAssigneeSnapshot> fetchAssigneeSnapshot(String processInstanceId) {
        try {
            Optional<Map<String, Object>> tasksResult =
                    workflowEngineClient.getProcessInstanceTasks(processInstanceId);
            if (tasksResult.isEmpty() || tasksResult.get() == null) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) tasksResult.get().get("tasks");
            if (tasks == null || tasks.isEmpty()) {
                return Optional.empty();
            }
            ProcessAssigneeSnapshot snapshot = ProcessAssigneeSnapshot.fromEngineTask(tasks.get(0));
            if (snapshot.getAssigneeUserId() == null && snapshot.getCandidateUserIds() == null) {
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (Exception e) {
            log.warn("Failed to fetch assignee snapshot from engine for process {}: {}",
                    processInstanceId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns my applications list
     */
    public Page<ProcessInstanceInfo> getMyApplications(String userId, String status, Pageable pageable) {
        log.info("Getting applications for user: {}, status: {}", userId, status);

        Page<ProcessInstance> instancePage;
        if (status != null && !status.isEmpty()) {
            instancePage = processInstanceRepository.findByStartUserIdAndStatusOrderByStartTimeDesc(userId, status, pageable);
        } else {
            instancePage = processInstanceRepository.findByStartUserIdOrderByStartTimeDesc(userId, pageable);
        }

        List<ProcessInstance> pageContent = instancePage.getContent();
        enrichRunningAssigneesFromEngine(pageContent);

        Set<String> assigneeKeys = pageContent.stream()
                .flatMap(inst -> userDisplayNameResolver.collectAssigneeUserKeys(
                        inst.getCurrentAssignee(), inst.getCandidateUsers()).stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> userNameCache = userDisplayNameResolver.resolveBatch(assigneeKeys);

        // Resolve Request ID config once per function unit (no per-row DB hit).
        Set<String> functionUnitCodes = pageContent.stream()
                .map(ProcessInstance::getFunctionUnitCode)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        RequestIdEnricher.SpecCache requestIdSpecs = requestIdEnricher.resolveSpecs(functionUnitCodes);

        // 「当前步骤」MI 感知：currentNode 存的是内层任务名（如 "sub form1"）；若该任务在多实例子流程内，
        // 列表应展示外层多实例 subProcess 的 name（如 "multi"）。MI 结构是流程定义静态的，按
        // processDefinitionKey 只解析一次 BPMN（列表同 FU 的行共享 key，通常 1~few 次，不是每行 HTTP）。
        Map<String, Map<String, String>> miNameMapByProcessDefKey = buildMiNodeNameMaps(pageContent);

        // List API omits variables: JSONB may contain Jackson-unfriendly nesting → HttpMessageNotWritableException → SYS_INTERNAL_ERROR
        // Request ID is computed from the (still present) variables before they are nulled.
        List<ProcessInstanceInfo> instances = pageContent.stream()
                .map(inst -> toProcessInstanceInfoForList(inst, userNameCache, miNameMapByProcessDefKey))
                .peek(info -> info.setRequestId(
                        requestIdEnricher.buildRequestId(requestIdSpecs, info.getFunctionUnitCode(), info.getVariables())))
                .peek(info -> info.setVariables(null))
                .toList();

        return new PageImpl<>(instances, pageable, instancePage.getTotalElements());
    }

    /**
     * 为本页所有实例，按 processDefinitionKey 解析一次 BPMN，建「内层 MI 任务名 → 外层 MI subProcess name」映射。
     * 引擎不可用/无 BPMN 时该 key 映射为空 map（调用方回退 currentNode）。COMPLETED 实例不需要（列表显 '-'）。
     */
    Map<String, Map<String, String>> buildMiNodeNameMaps(List<ProcessInstance> pageContent) {
        Map<String, Map<String, String>> byKey = new HashMap<>();
        if (!workflowEngineClient.isAvailable()) {
            return byKey;
        }
        Set<String> keys = pageContent.stream()
                .filter(i -> !"COMPLETED".equals(i.getStatus()))
                .map(ProcessInstance::getProcessDefinitionKey)
                .filter(k -> k != null && !k.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String key : keys) {
            try {
                byKey.put(key, miBpmnNameMapCache.getOrLoad(key, () -> workflowEngineClient.getBpmnXml(key)
                        .map(BpmnMiXmlSupport::buildMiInnerTaskNameToSubProcessName)
                        .orElseGet(HashMap::new)));
            } catch (Exception e) {
                log.debug("buildMiNodeNameMaps: BPMN parse failed for processDefKey {}: {}", key, e.getMessage());
                byKey.put(key, new HashMap<>());
            }
        }
        return byKey;
    }

    /**
     * Entity to DTO without cache (single process query)
     */
    private ProcessInstanceInfo toProcessInstanceInfo(ProcessInstance instance) {
        return toProcessInstanceInfo(instance, new HashMap<>());
    }

    /**
     * My requests list: do not call workflow-engine per row for runtime tasks (one HTTP per row is too slow).
     * Assignee display name from {@link ProcessInstance#getCurrentAssignee()} / {@link ProcessInstance#getCandidateUsers()}
     * via {@link UserDisplayNameResolver} (single name; BU/Role OR pool {@code name1, name2, name3}).
     */
    ProcessInstanceInfo toProcessInstanceInfoForList(ProcessInstance instance, Map<String, String> userNameCache,
            Map<String, Map<String, String>> miNameMapByProcessDefKey) {
        String currentAssigneeName = userDisplayNameResolver.resolveCurrentAssigneeDisplay(
                instance.getCurrentAssignee(), instance.getCandidateUsers(), userNameCache);

        log.debug("toProcessInstanceInfoForList: processId={}, status={}, assignee={}, candidates={}, display={}",
                instance.getId(), instance.getStatus(),
                instance.getCurrentAssignee(), instance.getCandidateUsers(), currentAssigneeName);

        String currentNode = instance.getCurrentNode();
        if ("COMPLETED".equals(instance.getStatus())) {
            currentNode = null;
        }

        // 「当前步骤」MI 感知：若 currentNode 是多实例内层任务名，映射成外层多实例 subProcess name（如 "multi"）；
        // 否则 = currentNode（普通节点）。终态 currentNode 为 null → currentStepName 也为 null（前端显 '-'）。
        String currentStepName = currentNode;
        if (currentNode != null && miNameMapByProcessDefKey != null) {
            Map<String, String> miMap = miNameMapByProcessDefKey.get(instance.getProcessDefinitionKey());
            if (miMap != null) {
                currentStepName = miMap.getOrDefault(currentNode, currentNode);
            }
        }

        return ProcessInstanceInfo.builder()
                .id(instance.getId())
                .processDefinitionId(instance.getProcessDefinitionId())
                .processDefinitionKey(instance.getProcessDefinitionKey())
                .processDefinitionName(instance.getProcessDefinitionName())
                .businessKey(instance.getBusinessKey())
                .startTime(instance.getStartTime())
                .endTime(instance.getEndTime())
                .completedAt(instance.getCompletedAt())
                .title(instance.getTitle())
                .status(instance.getStatus())
                .startUserId(instance.getStartUserId())
                .startUserName(instance.getStartUserName())
                .currentNode(currentNode)
                .currentStepName(currentStepName)
                .currentAssignee(currentAssigneeName)
                .candidateUsers(instance.getCandidateUsers())
                .variables(instance.getVariables())
                .functionUnitCatalogId(instance.getFunctionUnitCatalogId())
                .functionUnitCode(instance.getFunctionUnitCode())
                .functionUnitVersionLabel(instance.getFunctionUnitVersionLabel())
                .build();
    }

    /**
     * Entity to DTO with method-level display-name cache (avoids list N+1)
     */
    private ProcessInstanceInfo toProcessInstanceInfo(ProcessInstance instance, Map<String, String> userNameCache) {
        String currentAssigneeName = userDisplayNameResolver.resolveCurrentAssigneeDisplay(
                instance.getCurrentAssignee(), instance.getCandidateUsers(), userNameCache);

        log.debug("toProcessInstanceInfo: processId={}, status={}, assignee={}, candidates={}, display={}",
                instance.getId(), instance.getStatus(),
                instance.getCurrentAssignee(), instance.getCandidateUsers(), currentAssigneeName);

        String currentNode = instance.getCurrentNode();
        if ("COMPLETED".equals(instance.getStatus())) {
            currentNode = null;
        }

        return ProcessInstanceInfo.builder()
                .id(instance.getId())
                .processDefinitionId(instance.getProcessDefinitionId())
                .processDefinitionKey(instance.getProcessDefinitionKey())
                .processDefinitionName(instance.getProcessDefinitionName())
                .businessKey(instance.getBusinessKey())
                .startTime(instance.getStartTime())
                .endTime(instance.getEndTime())
                .completedAt(instance.getCompletedAt())
                .title(instance.getTitle())
                .status(instance.getStatus())
                .startUserId(instance.getStartUserId())
                .startUserName(instance.getStartUserName())
                .currentNode(currentNode)
                .currentAssignee(currentAssigneeName)
                .candidateUsers(instance.getCandidateUsers())
                .variables(instance.getVariables())
                .functionUnitCatalogId(instance.getFunctionUnitCatalogId())
                .functionUnitCode(instance.getFunctionUnitCode())
                .functionUnitVersionLabel(instance.getFunctionUnitVersionLabel())
                .build();
    }

    /**
     * Returns process detail
     * When local DB lacks current node, fetch live from Flowable
     */
    /**
     * Gap-fills {@code __subTables__} slices from the live Flowable engine into the portal store when a
     * service task (e.g. Activepieces) produced rows the portal-written store never received. Only fills
     * slices the store is missing or has empty — user-submitted rows always win. Persists so a subsequent
     * task completion carries the rows rather than overwriting them with an empty grid. Best-effort.
     */
    @SuppressWarnings("unchecked")
    private void hydrateEngineSubTablesIntoStore(String processId, ProcessInstance instance, ProcessInstanceInfo info) {
        if (workflowEngineClient == null || !workflowEngineClient.isAvailable()) {
            return; // engine known-down: skip the read-path round-trip
        }
        Map<String, Object> vars = info.getVariables() != null
                ? new HashMap<>(info.getVariables()) : new HashMap<>();
        Map<String, Object> current = vars.get("__subTables__") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        engineSubTableHydrator.mergeFromEngine(processId, current).ifPresent(result -> {
            vars.put("__subTables__", result.mergedSubTables());
            if (result.rowCount() != null && vars.get("rowCount") == null) {
                vars.put("rowCount", result.rowCount());
            }
            info.setVariables(vars);
            instance.setVariables(vars);
            processInstanceRepository.save(instance);
            log.info("getProcessDetail: hydrated __subTables__ from engine for running process {}", processId);
        });
    }

    /**
     * Gap-fills scalar process variables a service task (e.g. an Activepieces node writing back
     * {@code output_text}) produced in the Flowable engine but that the portal's own
     * {@code up_process_instance} store — written only by form submissions — still holds as
     * {@code null} or misses entirely. Fill-only: any non-null portal value wins, so user input is
     * never overwritten. {@code __subTables__} is excluded — {@link #hydrateEngineSubTablesIntoStore}
     * owns it with its own per-slice merge rules. Persists so later reads see the output. Best-effort.
     *
     * <p>Unlike the sub-table hydration this also runs for ended instances: a straight-through
     * automation (start → service task → end) leaves the store frozen at the submitted values, and
     * the engine serves history variables for ended instances.</p>
     */
    private void hydrateEngineScalarsIntoStore(String processId, ProcessInstance instance, ProcessInstanceInfo info) {
        if (workflowEngineClient == null || !workflowEngineClient.isAvailable()) {
            return; // engine known-down: skip the read-path round-trip
        }
        try {
            Map<String, Object> engineRow = workflowEngineClient.getProcessInstance(processId).orElse(null);
            if (engineRow == null || !(engineRow.get("variables") instanceof Map<?, ?> engineVars)) {
                return;
            }
            Map<String, Object> vars = info.getVariables() != null
                    ? new HashMap<>(info.getVariables()) : new HashMap<>();
            List<String> filled = new ArrayList<>();
            for (Map.Entry<?, ?> e : engineVars.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                String key = String.valueOf(e.getKey());
                if ("__subTables__".equals(key) || vars.get(key) != null) {
                    continue;
                }
                vars.put(key, e.getValue());
                filled.add(key);
            }
            if (filled.isEmpty()) {
                return;
            }
            info.setVariables(vars);
            instance.setVariables(vars);
            processInstanceRepository.save(instance);
            log.info("getProcessDetail: hydrated engine-only variables {} for process {}", filled, processId);
        } catch (RuntimeException e) {
            log.debug("hydrateEngineScalarsIntoStore skipped for {}: {}", processId, e.getMessage());
        }
    }

    /**
     * True when the store holds at least one {@code null} variable value — the shape a service-task
     * output leaves behind (the submitted form writes the key with no value). Used to keep the engine
     * round-trip off the common path where nothing could be gap-filled anyway.
     */
    private boolean hasNullVariableValue(ProcessInstanceInfo info) {
        Map<String, Object> vars = info.getVariables();
        return vars != null && vars.containsValue(null);
    }

    public ProcessInstanceInfo getProcessDetail(String processId) {
        Optional<ProcessInstance> optInstance = processInstanceRepository.findById(processId);
        if (optInstance.isEmpty()) {
            return null;
        }

        ProcessInstance instance = optInstance.get();
        ProcessInstanceInfo info = toProcessInstanceInfo(instance);

        // Service-task outputs (e.g. an Activepieces task's __subTables__) live only in the Flowable
        // engine and are absent from the portal's up_process_instance store (written only by form
        // submissions). For a running instance, hydrate them so My Applications renders the rows and a
        // later completion persists them instead of overwriting with an empty grid.
        if ("RUNNING".equals(instance.getStatus())) {
            hydrateEngineSubTablesIntoStore(processId, instance, info);
        }
        // Same gap for plain (non-sub-table) variables the service task wrote back, e.g. an
        // Activepieces output mapped to output_text: the store keeps the null the start form
        // submitted. Runs for ended instances too — a straight-through automation never gets a
        // second portal write that would carry the value.
        if (hasNullVariableValue(info)) {
            hydrateEngineScalarsIntoStore(processId, instance, info);
        }

        // If the process is still running but the local DB has no currentNode stored,
        // try to fetch the live state from Flowable — this self-heals processes where
        // the auto-complete path set currentNode=null (e.g. no next task after initiator task).
        if ("RUNNING".equals(instance.getStatus()) &&
            (info.getCurrentNode() == null || info.getCurrentNode().isEmpty())) {
            try {
                if (workflowEngineClient.isAvailable()) {
                    Optional<Map<String, Object>> tasksResult = workflowEngineClient.getProcessInstanceTasks(processId);
                    if (tasksResult.isPresent()) {
                        Map<String, Object> tasksData = tasksResult.get();
                        if (tasksData != null) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> tasks = (List<Map<String, Object>>) tasksData.get("tasks");
                            if (tasks != null && !tasks.isEmpty()) {
                                Map<String, Object> currentTask = tasks.get(0);
                                String currentNodeName = (String) currentTask.get("taskName");
                                ProcessAssigneeSnapshot snapshot = ProcessAssigneeSnapshot.fromEngineTask(currentTask);
                                Map<String, String> assigneeCache = userDisplayNameResolver.resolveBatch(
                                        userDisplayNameResolver.collectAssigneeUserKeys(
                                                snapshot.getAssigneeUserId(), snapshot.getCandidateUserIds()));
                                String currentAssigneeDisplay = userDisplayNameResolver.resolveCurrentAssigneeDisplay(
                                        snapshot.getAssigneeUserId(), snapshot.getCandidateUserIds(), assigneeCache);

                                if (currentNodeName != null && !currentNodeName.isEmpty()) {
                                    info.setCurrentNode(currentNodeName);
                                    info.setCurrentAssignee(currentAssigneeDisplay);
                                    info.setCandidateUsers(snapshot.getCandidateUserIds());
                                    instance.setCurrentNode(currentNodeName);
                                    instance.setCurrentAssignee(snapshot.getAssigneeUserId());
                                    instance.setCandidateUsers(snapshot.getCandidateUserIds());
                                    processInstanceRepository.save(instance);
                                    log.info("getProcessDetail: refreshed currentNode={}, assignee={}, candidates={} from Flowable for process {}",
                                            currentNodeName, snapshot.getAssigneeUserId(),
                                            snapshot.getCandidateUserIds(), processId);
                                }
                            } else {
                                log.debug("getProcessDetail: no active tasks in Flowable for process {}, keeping null currentNode", processId);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("getProcessDetail: failed to refresh currentNode from Flowable for process {}: {}", processId, e.getMessage());
            }
        }

        // Self-heal: email/automation paths may finish in Flowable before portal completion callback lands.
        if ("RUNNING".equals(instance.getStatus())) {
            reconcilePortalStatusWithEngineCompletion(instance, info);
        }

        Map<String, Object> vars = info.getVariables();
        boolean hasSubTables =
                vars != null
                        && vars.get("__subTables__") instanceof Map<?, ?> subMap
                        && !subMap.isEmpty();
        Map<String, Map<String, MiRowProgress>> miProgress = Collections.emptyMap();
        if (hasSubTables || "RUNNING".equals(instance.getStatus())) {
            miProgress = miOverlayComponent.resolveMiRowProgress(processId, instance.getStatus());
        }
        if ("RUNNING".equals(instance.getStatus())) {
            miOverlayComponent.reconcileCurrentNodeWithMiOverlay(info, miProgress);
        }

        // 「当前步骤」(MI 感知)：流程正处于多实例子任务内部时，展示外层多实例 subProcess 的 name（如 "multi"），
        // 而非某个具体内层子任务名；普通节点则等于 currentNode。终态由前端显示 '-'（此处 currentNode 已为 null）。
        if ("RUNNING".equals(instance.getStatus())) {
            String miActivityName = (miProgress != null && !miProgress.isEmpty())
                    ? miOverlayComponent.getMiActivityName(processId) : null;
            info.setCurrentStepName(miActivityName != null ? miActivityName : info.getCurrentNode());
        } else {
            info.setCurrentStepName(info.getCurrentNode());
        }
        if (hasSubTables) {
            subTableEnrichmentComponent.enrichSubTablesWithAssignmentData(info, miProgress);
        }

        // Request ID for the detail Basic Info (same derivation as the list); computed from the
        // process variables which are still present on the detail DTO.
        if (info.getFunctionUnitCode() != null) {
            RequestIdEnricher.SpecCache specs = requestIdEnricher.resolveSpecs(
                    java.util.Set.of(info.getFunctionUnitCode()));
            info.setRequestId(requestIdEnricher.buildRequestId(
                    specs, info.getFunctionUnitCode(), info.getVariables()));
        }

        return info;
    }

    /**
     * Check whether a portal userId is a participant of the given process.
     * Quick local checks first; falls back to querying the workflow-engine
     * process history to see if the user was ever a task assignee.
     */
    public boolean isProcessParticipant(String userId, ProcessInstanceInfo detail) {
        if (detail == null || userId == null) return false;

        // 1. Process initiator
        if (userId.equals(detail.getStartUserId())) return true;

        // 2. Current assignee (may be display-name OR userId depending on data flow)
        if (userId.equals(detail.getCurrentAssignee())) return true;

        // 3. Candidate users (or-sign scenario, comma-separated)
        String candidates = detail.getCandidateUsers();
        if (candidates != null && !candidates.isBlank()) {
            for (String c : candidates.split(",")) {
                if (userId.equals(c.trim())) return true;
            }
        }

        // 4. Resolve userId to display name and compare against currentAssignee
        //    (currentAssignee is often stored as display name)
        try {
            String displayName = resolveUserDisplayName(userId);
            if (displayName != null && displayName.equals(detail.getCurrentAssignee())) return true;
        } catch (Exception e) {
            log.debug("Could not resolve display name for user {}: {}", userId, e.getMessage());
        }

        // 5. Fall back: check workflow-engine process history for any task
        //    where the user was ever an assignee (covers completed MI sub-tasks, etc.)
        try {
            if (workflowEngineClient.isAvailable()) {
                var historyOpt = workflowEngineClient.getProcessInstanceHistory(detail.getId());
                if (historyOpt.isPresent()) {
                    for (Map<String, Object> record : historyOpt.get()) {
                        Object operatorId = record.get("operatorId");
                        if (operatorId != null && userId.equals(String.valueOf(operatorId).trim())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check process history participation for user {} in process {}: {}",
                    userId, detail.getId(), e.getMessage());
        }

        return false;
    }

    /**
     * Whether a portal user may open process detail/history. Aligns with Main Table View list rules:
     * participants first; otherwise a published view the user can see (all rows when
     * {@code restrict_to_involved_users=false}, or involved-only when restricted).
     */
    public boolean canAccessProcessDetail(String userId, ProcessInstanceInfo detail) {
        if (detail == null || userId == null || userId.isBlank()) {
            return false;
        }
        if (isProcessParticipant(userId, detail)) {
            return true;
        }
        if (mainTableViewAccessResolver.isSystemAdministrator(userId)) {
            return true;
        }
        String functionUnitCode = detail.getFunctionUnitCode();
        if (functionUnitCode == null || functionUnitCode.isBlank()) {
            return false;
        }
        return canAccessViaPublishedMainTableViews(userId, functionUnitCode, detail.getId());
    }

    private boolean canAccessViaPublishedMainTableViews(
            String userId, String functionUnitCode, String processInstanceId) {
        List<Map<String, Object>> views = jdbcTemplate.queryForList("""
                SELECT v.id, v.restrict_to_involved_users
                FROM dw_main_table_view_configs v
                INNER JOIN dw_function_units fu ON fu.id = v.function_unit_id
                WHERE fu.code = ? AND v.status = 'PUBLISHED'
                """, functionUnitCode);
        if (views.isEmpty()) {
            return false;
        }
        ProcessInstance instance = processInstanceRepository.findById(processInstanceId).orElse(null);
        for (Map<String, Object> view : views) {
            Long viewId = ((Number) view.get("id")).longValue();
            boolean restrictToInvolved = Boolean.TRUE.equals(view.get("restrict_to_involved_users"));
            List<AccessRule> rules = loadMainTableViewAccessRules(viewId);
            if (!mainTableViewAccessResolver.canUserSeeView(userId, rules)) {
                continue;
            }
            if (!restrictToInvolved) {
                return true;
            }
            if (instance != null && mainTableViewInvolvementChecker.isUserInvolved(userId, instance)) {
                return true;
            }
        }
        return false;
    }

    private List<AccessRule> loadMainTableViewAccessRules(Long viewId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT target_type, target_id
                    FROM dw_main_table_view_access
                    WHERE view_config_id = ?
                    """, viewId);
            return mainTableViewAccessResolver.parseAccessRules(rows);
        } catch (PortalException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load view access rules for {}", viewId, e);
            throw new PortalException("500", "Failed to load process detail access rules", e);
        }
    }

    /**
     * Cached display-name resolution to avoid duplicate DB hits in one batch
     */
    private String resolveUserDisplayNameCached(String userId, Map<String, String> cache) {
        return userDisplayNameResolver.resolveCached(userId, cache);
    }

    /**
     * Resolves user display name
     */
    private String resolveUserDisplayName(String userId) {
        return userDisplayNameResolver.resolve(userId);
    }

    /**
     * When Flowable has already finished but portal still shows RUNNING (missed completion callback),
     * align local status so Application Detail diagram/history render completed path.
     */
    private void reconcilePortalStatusWithEngineCompletion(ProcessInstance instance, ProcessInstanceInfo info) {
        try {
            if (!workflowEngineClient.isAvailable()) {
                return;
            }
            Optional<Map<String, Object>> statusOpt = workflowEngineClient.getProcessInstanceStatus(instance.getId());
            if (statusOpt.isEmpty()) {
                return;
            }
            Object completedFlag = statusOpt.get().get("completed");
            if (!(completedFlag instanceof Boolean completed) || !completed) {
                return;
            }
            LocalDateTime finishedAt = LocalDateTime.now();
            instance.setStatus("COMPLETED");
            instance.setEndTime(finishedAt);
            instance.setCompletedAt(finishedAt);
            instance.setCurrentNode(null);
            instance.setCurrentAssignee(null);
            instance.setCandidateUsers(null);
            processInstanceRepository.save(instance);

            info.setStatus("COMPLETED");
            info.setCurrentNode(null);
            info.setCurrentAssignee(null);
            info.setCandidateUsers(null);
            info.setCurrentStepName(null);
            info.setEndTime(finishedAt);
            log.info("getProcessDetail: reconciled portal status to COMPLETED for finished engine instance {}",
                    instance.getId());
        } catch (Exception e) {
            log.warn("getProcessDetail: failed to reconcile completion status for {}: {}",
                    instance.getId(), e.getMessage());
        }
    }
}
