package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.ProcessDefinitionInfo;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.dto.ProcessStartRequest;
import com.portal.entity.FavoriteProcess;
import com.portal.entity.ProcessDraft;
import com.portal.entity.ProcessInstance;
import com.portal.entity.ActionDefinition;
import com.portal.repository.FavoriteProcessRepository;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.repository.ActionDefinitionRepository;
import com.portal.service.ProcessAssigneeSnapshot;
import com.platform.common.i18n.I18nService;
import com.platform.common.util.ApiResponseBodyUnwrap;
import com.platform.common.util.SafeUrlInput;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Process facade for the portal: startable definitions, function-unit content cache, favorites,
 * drafts and small process actions, with one-line delegation to the extracted collaborators:
 * <ul>
 *   <li>{@link ProcessStartComponent} — process start flow</li>
 *   <li>{@link ProcessApplicationQueryComponent} — my applications / process detail / participant checks</li>
 *   <li>{@link SubTableEnrichmentComponent} — {@code __subTables__} physical-row + MI overlay hydration</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessComponent {

    private final FavoriteProcessRepository favoriteProcessRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ActionDefinitionRepository actionDefinitionRepository;
    private final FunctionUnitAccessComponent functionUnitAccessComponent;
    private final WorkflowEngineClient workflowEngineClient;
    private final ProcessDraftComponent processDraftComponent;
    private final RestTemplate restTemplate;
    private final I18nService i18nService;
    private final ProcessStartComponent processStartComponent;
    private final ProcessApplicationQueryComponent processApplicationQueryComponent;
    private final SubTableEnrichmentComponent subTableEnrichmentComponent;

    @Value("${admin-center.url:http://localhost:8090}")
    private String adminCenterUrl;

    /** Lazy: resolves the main-table Request ID config so the start page can recompute it live. */
    @Lazy
    @Autowired(required = false)
    private RequestIdEnricher requestIdEnricher;

    /**
     * In-memory cache for function unit content payloads (forms + BPMN + data tables).
     * Key: resolved functionUnitId. TTL: 5 minutes (matching FunctionUnitAccessComponent).
     * Same process key → same content for all tasks, so caching eliminates repeated
     * admin-center HTTP round-trips when users navigate between To Do detail pages.
     */
    private final Map<String, CachedFuContent> fuContentCache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedFuContent> eldest) {
                    return size() > 100;
                }
            });

    private static final long FU_CONTENT_CACHE_TTL_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(5);

    private record CachedFuContent(Map<String, Object> payload, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > FU_CONTENT_CACHE_TTL_MS;
        }
    }

    // ==================== Process definitions and start ====================

    /**
     * Returns startable process definitions
     * Loads deployed function units from admin center and filters by business role
     */
    public List<ProcessDefinitionInfo> getAvailableProcessDefinitions(String userId, String category, String keyword) {
        log.info("Getting available process definitions for user: {}", userId);
        List<ProcessDefinitionInfo> definitions = new ArrayList<>();

        try {
            // Fetch deployed function units from admin center
            String url = adminCenterUrl + "/api/v1/admin/function-units/deployed/latest";
            log.info("Fetching latest deployed function units from: {}", url);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null) {
                List<Map<String, Object>> units = ApiResponseBodyUnwrap.normalizeToListOfMaps(response);

                if (!units.isEmpty()) {
                    log.info("Got {} deployed function units", units.size());

                    // Filter function units by user business roles
                    List<Map<String, Object>> accessibleUnits = functionUnitAccessComponent.filterAccessibleFunctionUnits(userId, units);
                    log.info("After filtering, {} function units are accessible to user {}", accessibleUnits.size(), userId);

                    for (Map<String, Object> unit : accessibleUnits) {
                        ProcessDefinitionInfo info = ProcessDefinitionInfo.builder()
                                .id((String) unit.get("id"))
                                .key((String) unit.get("code"))
                                .name((String) unit.get("name"))
                                .description((String) unit.get("description"))
                                .category(i18nService.getMessage("portal.process.category.business"))
                                .version(unit.get("version") != null ? String.valueOf(unit.get("version")) : "1.0.0")
                                .icon((String) unit.get("iconSvg"))
                                .build();
                        definitions.add(info);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch deployed function units from admin center: {}", e.getMessage(), e);
        }

        // Return empty list when none accessible (no mock data)
        // Frontend shows no-available-processes message
        if (definitions.isEmpty()) {
            log.info("No accessible process definitions found for user: {}", userId);
        }

        // Filter
        if (category != null && !category.isEmpty()) {
            definitions.removeIf(d -> !d.getCategory().equals(category));
        }
        if (keyword != null && !keyword.isEmpty()) {
            final String kw = keyword;
            definitions.removeIf(d -> {
                String n = d.getName();
                String desc = d.getDescription();
                boolean nameMatch = n != null && n.contains(kw);
                boolean descMatch = desc != null && desc.contains(kw);
                return !nameMatch && !descMatch;
            });
        }

        // Mark favorites
        List<FavoriteProcess> favorites = favoriteProcessRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        Set<String> favoriteKeys = new HashSet<>();
        favorites.forEach(f -> favoriteKeys.add(f.getProcessDefinitionKey()));
        definitions.forEach(d -> d.setIsFavorite(favoriteKeys.contains(d.getKey())));

        return definitions;
    }

    /**
     * Starts process
     * @see ProcessStartComponent#startProcess(String, String, ProcessStartRequest)
     */
    @Transactional
    public ProcessInstanceInfo startProcess(String userId, String processKey, ProcessStartRequest request) {
        return processStartComponent.startProcess(userId, processKey, request);
    }

    // ==================== Process queries ====================

    /**
     * Returns my applications list
     * @see ProcessApplicationQueryComponent#getMyApplications(String, String, Pageable)
     */
    public Page<ProcessInstanceInfo> getMyApplications(String userId, String status, Pageable pageable) {
        return processApplicationQueryComponent.getMyApplications(userId, status, pageable);
    }

    /**
     * Returns my applications with optional keyword / sort / column filters.
     * @see ProcessApplicationQueryComponent#getMyApplications(String, String, String, String, String, java.util.Map, Pageable)
     */
    public Page<ProcessInstanceInfo> getMyApplications(
            String userId,
            String status,
            String keyword,
            String sortField,
            String sortDirection,
            Map<String, Map<String, Object>> filters,
            Pageable pageable) {
        return processApplicationQueryComponent.getMyApplications(
                userId, status, keyword, sortField, sortDirection, filters, pageable);
    }

    /**
     * My applications with optional groupBy / groupCounts.
     * @see ProcessApplicationQueryComponent#getMyApplications(String, String, String, String, String, java.util.Map, String, Pageable)
     */
    public ProcessApplicationQueryComponent.ApplicationListResult getMyApplications(
            String userId,
            String status,
            String keyword,
            String sortField,
            String sortDirection,
            Map<String, Map<String, Object>> filters,
            String groupBy,
            Pageable pageable) {
        return processApplicationQueryComponent.getMyApplications(
                userId, status, keyword, sortField, sortDirection, filters, groupBy, pageable);
    }

    public java.util.List<com.portal.util.PortalListColumnMeta> getApplicationColumns() {
        return com.portal.util.ProcessApplicationListSpec.COLUMNS;
    }

    public java.util.List<com.portal.util.PortalListColumnMeta> getDraftColumns() {
        return com.portal.util.ProcessDraftListSpec.COLUMNS;
    }

    /**
     * Returns process detail
     * @see ProcessApplicationQueryComponent#getProcessDetail(String)
     */
    public ProcessInstanceInfo getProcessDetail(String processId) {
        return processApplicationQueryComponent.getProcessDetail(processId);
    }

    /**
     * Merge persisted relation-table columns into {@code variables.__subTables__} rows (same as process detail).
     * @see SubTableEnrichmentComponent#enrichSubTablesVariablesFromPhysicalTables(String, Map)
     */
    public void enrichSubTablesVariablesFromPhysicalTables(String processInstanceId, Map<String, Object> variables) {
        subTableEnrichmentComponent.enrichSubTablesVariablesFromPhysicalTables(processInstanceId, variables);
    }

    /**
     * Check whether a portal userId is a participant of the given process.
     * @see ProcessApplicationQueryComponent#isProcessParticipant(String, ProcessInstanceInfo)
     */
    public boolean isProcessParticipant(String userId, ProcessInstanceInfo detail) {
        return processApplicationQueryComponent.isProcessParticipant(userId, detail);
    }

    /**
     * Process detail/history access (participants + Main Table View visibility parity).
     * @see ProcessApplicationQueryComponent#canAccessProcessDetail(String, ProcessInstanceInfo)
     */
    public boolean canAccessProcessDetail(String userId, ProcessInstanceInfo detail) {
        return processApplicationQueryComponent.canAccessProcessDetail(userId, detail);
    }

    // ==================== Process actions (withdraw, urge, favorite) ====================

    /**
     * Withdraws process
     */
    public boolean withdrawProcess(String userId, String processId, String reason) {
        Optional<ProcessInstance> optInstance = processInstanceRepository.findById(processId);
        if (optInstance.isEmpty()) {
            return false;
        }

        ProcessInstance instance = optInstance.get();
        if (!instance.getStartUserId().equals(userId)) {
            return false;
        }
        if (!"RUNNING".equals(instance.getStatus())) {
            return false;
        }

        // Set status to withdrawn
        instance.setStatus("WITHDRAWN");
        instance.setEndTime(LocalDateTime.now());
        processInstanceRepository.save(instance);

        // Cancel process instance via Flowable engine
        try {
            if (workflowEngineClient.isAvailable()) {
                Optional<Map<String, Object>> cancelResult = workflowEngineClient
                        .cancelProcessInstance(processId,
                                reason != null ? reason : i18nService.getMessage("portal.process.withdraw.default_reason"));
                if (cancelResult.isPresent()) {
                    log.info("Flowable process instance cancelled: {}", processId);
                } else {
                    log.warn("Failed to cancel Flowable process instance: {}, local status already updated", processId);
                }
            } else {
                log.warn("Workflow engine not available, skipped Flowable cancellation for process: {}", processId);
            }
        } catch (Exception e) {
            log.warn("Failed to cancel Flowable process instance {}: {}", processId, e.getMessage());
        }

        return true;
    }

    /**
     * Return running process to the first completed user-task node (initiator revision).
     * Only the process starter may invoke this (My Requests → Draft).
     */
    public boolean returnProcessToFirstStep(String userId, String processId, String comment) {
        Optional<ProcessInstance> optInstance = processInstanceRepository.findById(processId);
        if (optInstance.isEmpty()) {
            return false;
        }
        ProcessInstance instance = optInstance.get();
        if (!instance.getStartUserId().equals(userId)) {
            return false;
        }
        if (!"RUNNING".equals(instance.getStatus())) {
            return false;
        }
        if (!workflowEngineClient.isAvailable()) {
            log.warn("Workflow engine not available, cannot return process {} to first step", processId);
            return false;
        }

        Optional<String> activeTaskId = findActiveEngineTaskId(processId);
        if (activeTaskId.isEmpty()) {
            log.warn("No active task for process {} — cannot return to first step", processId);
            return false;
        }
        Optional<String> firstActivityId = findFirstReturnableActivityId(activeTaskId.get());
        if (firstActivityId.isEmpty()) {
            log.warn("No returnable first activity for task {} (process {})", activeTaskId.get(), processId);
            return false;
        }

        String reason = comment != null && !comment.isBlank()
                ? comment
                : i18nService.getMessage("portal.process.return_to_first.default_reason");

        Optional<Map<String, Object>> returnResult = workflowEngineClient.returnTask(
                activeTaskId.get(), firstActivityId.get(), userId, reason, "DRAFT");
        if (returnResult.isEmpty()) {
            return false;
        }
        Map<String, Object> data = returnResult.get();
        if (Boolean.FALSE.equals(data.get("success"))) {
            log.warn("Engine return-to-first failed for process {}: {}", processId, data.get("message"));
            return false;
        }

        refreshRunningProcessCurrentNodeFromEngine(processId, instance);
        log.info("Process {} returned to first step ({}) by initiator {}", processId, firstActivityId.get(), userId);
        return true;
    }

    @SuppressWarnings("unchecked")
    private Optional<String> findActiveEngineTaskId(String processInstanceId) {
        Optional<Map<String, Object>> tasksResult = workflowEngineClient.getProcessInstanceTasks(processInstanceId);
        if (tasksResult.isEmpty()) {
            return Optional.empty();
        }
        Object tasksObj = tasksResult.get().get("tasks");
        if (!(tasksObj instanceof List<?> tasks) || tasks.isEmpty()) {
            return Optional.empty();
        }
        Object first = tasks.get(0);
        if (!(first instanceof Map<?, ?> taskMap)) {
            return Optional.empty();
        }
        Object taskId = taskMap.get("taskId");
        if (taskId == null) {
            taskId = taskMap.get("id");
        }
        return taskId != null ? Optional.of(String.valueOf(taskId)) : Optional.empty();
    }

    private Optional<String> findFirstReturnableActivityId(String taskId) {
        Optional<List<Map<String, Object>>> activitiesOpt = workflowEngineClient.getReturnableActivities(taskId);
        if (activitiesOpt.isEmpty() || activitiesOpt.get().isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> activities = activitiesOpt.get();
        Map<String, Object> firstStep = activities.get(activities.size() - 1);
        Object activityId = firstStep.get("taskId");
        if (activityId == null) {
            activityId = firstStep.get("activityId");
        }
        return activityId != null ? Optional.of(String.valueOf(activityId)) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private void refreshRunningProcessCurrentNodeFromEngine(String processId, ProcessInstance instance) {
        if (!"RUNNING".equals(instance.getStatus()) || !workflowEngineClient.isAvailable()) {
            return;
        }
        try {
            Optional<Map<String, Object>> tasksResult = workflowEngineClient.getProcessInstanceTasks(processId);
            if (tasksResult.isEmpty()) {
                return;
            }
            Map<String, Object> tasksData = tasksResult.get();
            if (tasksData == null) {
                return;
            }
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) tasksData.get("tasks");
            if (tasks == null || tasks.isEmpty()) {
                return;
            }
            Map<String, Object> currentTask = tasks.get(0);
            String currentNodeName = (String) currentTask.get("taskName");
            ProcessAssigneeSnapshot snapshot = ProcessAssigneeSnapshot.fromEngineTask(currentTask);
            if (currentNodeName != null && !currentNodeName.isEmpty()) {
                instance.setCurrentNode(currentNodeName);
                instance.setCurrentAssignee(snapshot.getAssigneeUserId());
                instance.setCandidateUsers(snapshot.getCandidateUserIds());
                processInstanceRepository.save(instance);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh currentNode after return-to-first for process {}: {}", processId, e.getMessage());
        }
    }

    /**
     * Urges process
     */
    public boolean urgeProcess(String userId, String processId) {
        Optional<ProcessInstance> optInstance = processInstanceRepository.findById(processId);
        if (optInstance.isEmpty()) {
            return false;
        }

        ProcessInstance instance = optInstance.get();
        if (!instance.getStartUserId().equals(userId)) {
            return false;
        }
        if (!"RUNNING".equals(instance.getStatus())) {
            return false;
        }
        // Send urge notification — messaging can be integrated here
        log.info("Urging process: {} by user: {}", processId, userId);
        return true;
    }

    /**
     * Toggles favorite status
     */
    public boolean toggleFavorite(String userId, String processKey) {
        Optional<FavoriteProcess> existing = favoriteProcessRepository.findByUserIdAndProcessDefinitionKey(userId, processKey);
        if (existing.isPresent()) {
            favoriteProcessRepository.delete(existing.get());
            return false;
        } else {
            FavoriteProcess favorite = new FavoriteProcess();
            favorite.setUserId(userId);
            favorite.setProcessDefinitionKey(processKey);
            favorite.setCreatedAt(LocalDateTime.now());
            favoriteProcessRepository.save(favorite);
            return true;
        }
    }

    // ==================== Draft management (delegated to ProcessDraftComponent) ====================

    /**
     * Saves draft
     * @see ProcessDraftComponent#saveDraft(String, String, Map)
     */
    public ProcessDraft saveDraft(String userId, String processKey, Map<String, Object> formData) {
        return processDraftComponent.saveDraft(userId, processKey, formData);
    }

    /**
     * Loads draft
     * @see ProcessDraftComponent#getDraft(String, String)
     */
    public Optional<ProcessDraft> getDraft(String userId, String processKey) {
        return processDraftComponent.getDraft(userId, processKey);
    }

    /**
     * Deletes draft
     * @see ProcessDraftComponent#deleteDraft(String, String)
     */
    public void deleteDraft(String userId, String processKey) {
        processDraftComponent.deleteDraft(userId, processKey);
    }

    /**
     * Lists drafts for user
     * @see ProcessDraftComponent#getDraftList(String)
     */
    public List<Map<String, Object>> getDraftList(String userId) {
        return processDraftComponent.getDraftList(userId);
    }

    /**
     * @see ProcessDraftComponent#getDraftPage(String, int, int)
     */
    public com.portal.dto.PageResponse<Map<String, Object>> getDraftPage(String userId, int page, int size) {
        return processDraftComponent.getDraftPage(userId, page, size);
    }

    /**
     * @see ProcessDraftComponent#getDraftPage(String, int, int, String, String, java.util.Map, String)
     */
    public com.portal.dto.PageResponse<Map<String, Object>> getDraftPage(
            String userId,
            int page,
            int size,
            String sortField,
            String sortDirection,
            Map<String, Map<String, Object>> filters,
            String groupBy) {
        return processDraftComponent.getDraftPage(userId, page, size, sortField, sortDirection, filters, groupBy);
    }

    /**
     * Deletes draft by ID
     * @see ProcessDraftComponent#deleteDraftById(String, Long)
     */
    public void deleteDraftById(String userId, Long draftId) {
        processDraftComponent.deleteDraftById(userId, draftId);
    }

    /**
     * Returns full function unit content (BPMN, forms, action bindings, etc.)
     * Checks function unit is enabled; throws when disabled.
     * Results are cached in-memory (5 min TTL) to avoid repeated admin-center HTTP round-trips.
     */
    public Map<String, Object> getFunctionUnitContent(String userId, String functionUnitIdOrCode) {
        log.info("Getting function unit content for: {}, user: {}", functionUnitIdOrCode, userId);

        // Must use raw processKey/code/id: resolve-then-check passes UUID and breaks processKey cache eviction (FunctionUnitAccessComponent)
        functionUnitAccessComponent.checkFunctionUnitAccess(userId, functionUnitIdOrCode);
        String functionUnitId = functionUnitAccessComponent.resolveFunctionUnitId(functionUnitIdOrCode);
        log.info("Resolved function unit ID: {}", functionUnitId);

        // Check cache before making HTTP call to admin-center
        CachedFuContent cached = fuContentCache.get(functionUnitId);
        if (cached != null && !cached.isExpired()) {
            return cached.payload();
        }

        try {
            String url = adminCenterUrl + "/api/v1/admin/function-units/" + SafeUrlInput.requirePathToken(functionUnitId) + "/content";
            log.info("Fetching function unit content from: {}", url);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            Map<String, Object> payload = ApiResponseBodyUnwrap.unwrapDataMap(response);
            if (!payload.isEmpty()) {
                log.info("Got function unit content: name={}", payload.get("name"));
                Map<String, Object> enrichedPayload = enrichMiAssignments(payload);
                fuContentCache.put(functionUnitId, new CachedFuContent(enrichedPayload, System.currentTimeMillis()));
                return enrichedPayload;
            }

            return Collections.emptyMap();

        } catch (FunctionUnitAccessComponent.FunctionUnitDisabledException |
                 FunctionUnitAccessComponent.FunctionUnitAccessDeniedException e) {
            throw e;
        } catch (BpmnMiXmlSupport.MiAssignmentConfigurationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get function unit content for {}: {}", functionUnitId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", e.getMessage());
            return errorResult;
        }
    }

    /**
     * Returns full function unit content without permission check (internal).
     * Primary source: admin-center (function unit catalog with BPMN, forms, etc.).
     * If admin-center returns no BPMN, falls back to workflow-engine (deployed BPMN by functionUnitCode).
     * Results are cached in-memory (5 min TTL) to avoid repeated admin-center HTTP round-trips.
     */
    public Map<String, Object> getFunctionUnitContent(String functionUnitIdOrCode) {
        log.info("Getting function unit content for: {}", functionUnitIdOrCode);

        try {
            // Resolve function unit ID (code or ID)
            String functionUnitId = functionUnitIdOrCode;
            try {
                functionUnitId = functionUnitAccessComponent.resolveFunctionUnitId(functionUnitIdOrCode);
                log.info("Resolved function unit ID: {}", functionUnitId);
            } catch (Exception e) {
                log.warn("Could not resolve functionUnitId for {}, using as-is: {}", functionUnitIdOrCode, e.getMessage());
            }

            // Check cache before making HTTP call
            CachedFuContent cached = fuContentCache.get(functionUnitId);
            if (cached != null && !cached.isExpired()) {
                return cached.payload();
            }

            String url = adminCenterUrl + "/api/v1/admin/function-units/" + SafeUrlInput.requirePathToken(functionUnitId) + "/content";
            log.info("Fetching function unit content from: {}", url);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            Map<String, Object> payload = ApiResponseBodyUnwrap.unwrapDataMap(response);
            if (!payload.isEmpty()) {
                log.info("Got function unit content from admin-center: name={}", payload.get("name"));
                Map<String, Object> enrichedPayload = enrichMiAssignments(payload);
                attachRequestIdConfig(enrichedPayload);
                // Cache successful result
                fuContentCache.put(functionUnitId, new CachedFuContent(enrichedPayload, System.currentTimeMillis()));
                return enrichedPayload;
            }

            log.warn("Admin-center returned empty content for functionUnitId={}; attempting fallback to workflow-engine BPMN", functionUnitId);

            // Fallback: fetch BPMN from workflow-engine by functionUnitCode, wrap in same shape
            Map<String, Object> fallbackResult = loadBpmnFallbackFromEngine(functionUnitIdOrCode);
            if (fallbackResult != null && !fallbackResult.isEmpty() && !fallbackResult.containsKey("error")) {
                fallbackResult = enrichMiAssignments(fallbackResult);
                fuContentCache.put(functionUnitId, new CachedFuContent(fallbackResult, System.currentTimeMillis()));
            }
            return fallbackResult;

        } catch (BpmnMiXmlSupport.MiAssignmentConfigurationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get function unit content for {}: {}", functionUnitIdOrCode, e.getMessage(), e);
            // Attempt fallback before giving up
            try {
                Map<String, Object> fallback = loadBpmnFallbackFromEngine(functionUnitIdOrCode);
                if (fallback != null && !fallback.isEmpty()) {
                    log.info("Function unit content loaded via workflow-engine fallback for {}", functionUnitIdOrCode);
                    return fallback;
                }
            } catch (Exception ignored) { /* fall through to error result */ }
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", e.getMessage());
            return errorResult;
        }
    }

    private Map<String, Object> enrichMiAssignments(Map<String, Object> payload) {
        Map<String, Map<String, Object>> assignments = new LinkedHashMap<>();
        Object processesValue = payload.get("processes");
        if (processesValue instanceof List<?> processes) {
            for (Object processValue : processes) {
                if (!(processValue instanceof Map<?, ?> process)) {
                    continue;
                }
                Object data = process.get("data");
                if (!(data instanceof String bpmnXml) || bpmnXml.isBlank()) {
                    continue;
                }
                Map<String, Map<String, Object>> parsed =
                        BpmnMiXmlSupport.buildMiAssignmentsBySubTableName(bpmnXml);
                parsed.forEach((subTableName, config) -> {
                    Map<String, Object> previous = assignments.putIfAbsent(subTableName, config);
                    if (previous != null && !previous.equals(config)) {
                        throw new BpmnMiXmlSupport.MiAssignmentConfigurationException(
                                "CONFLICTING_MI_ASSIGNMENT_CONFIG: subTableName '" + subTableName
                                        + "' has conflicting MI assignment settings");
                    }
                });
            }
        }
        Map<String, Object> enriched = new LinkedHashMap<>(payload);
        enriched.put("miAssignments", assignments);
        return enriched;
    }

    /**
     * Attach the main-table Request ID config ({fieldNames, separator}) to the content payload
     * so the start (new request) page can recompute the readonly Request ID field live.
     * No-op when the main table has no config, or the enricher bean is unavailable (e.g. unit tests).
     */
    private void attachRequestIdConfig(Map<String, Object> payload) {
        if (payload == null || requestIdEnricher == null) {
            return;
        }
        Object code = payload.get("code");
        if (!(code instanceof String functionUnitCode) || functionUnitCode.isBlank()) {
            return;
        }
        try {
            RequestIdEnricher.RequestIdConfigView cfg = requestIdEnricher.resolveConfigView(functionUnitCode);
            if (cfg != null) {
                Map<String, Object> map = new HashMap<>();
                map.put("fieldNames", cfg.fieldNames());
                map.put("separator", cfg.separator());
                payload.put("requestIdConfig", map);
            }
        } catch (Exception e) {
            log.debug("Could not attach requestIdConfig for code {}: {}", functionUnitCode, e.getMessage());
        }
    }

    /**
     * Fallback: load BPMN from workflow-engine using functionUnitCode (or functionUnitId if it's a code).
     * Returns a payload compatible with getFunctionUnitContent shape: { name, processes: [{ data: bpmnXml }] }
     */
    private Map<String, Object> loadBpmnFallbackFromEngine(String functionUnitIdOrCode) {
        if (!workflowEngineClient.isAvailable()) {
            log.warn("Workflow engine not available for BPMN fallback");
            return Collections.emptyMap();
        }
        try {
            Optional<String> bpmnOpt = workflowEngineClient.getBpmnXml(functionUnitIdOrCode);
            if (bpmnOpt.isPresent() && bpmnOpt.get() != null && !bpmnOpt.get().isBlank()) {
                log.info("BPMN loaded from workflow-engine for key: {}", functionUnitIdOrCode);
                Map<String, Object> result = new HashMap<>();
                result.put("name", functionUnitIdOrCode);
                result.put("code", functionUnitIdOrCode);
                List<Map<String, Object>> processes = new ArrayList<>();
                Map<String, Object> processEntry = new HashMap<>();
                processEntry.put("data", bpmnOpt.get());
                processes.add(processEntry);
                result.put("processes", processes);
                return result;
            } else {
                log.warn("Workflow engine returned no BPMN for key: {}", functionUnitIdOrCode);
            }
        } catch (Exception e) {
            log.warn("Failed to load BPMN from workflow-engine for {}: {}", functionUnitIdOrCode, e.getMessage());
        }
        return Collections.emptyMap();
    }

    /**
     * Returns function unit content of a given type (form dialogs, etc.)
     *
     * Uses /function-units/{id}/content then filters client-side
     * Spring ResourceHttpRequestHandler intercepts some specific path patterns
     */
    public List<Map<String, Object>> getFunctionUnitContents(String functionUnitIdOrCode, String contentType) {
        log.info("Getting function unit contents for: {}, contentType: {}", functionUnitIdOrCode, contentType);

        try {
            // Resolve function unit ID (code or name)
            String functionUnitId = functionUnitAccessComponent.resolveFunctionUnitId(functionUnitIdOrCode);
            log.info("Resolved function unit ID: {}", functionUnitId);

            // Use generic /content endpoint for all content
            String url = adminCenterUrl + "/api/v1/admin/function-units/" + SafeUrlInput.requirePathToken(functionUnitId) + "/content";
            log.info("Fetching function unit content from: {}", url);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            Map<String, Object> payload = ApiResponseBodyUnwrap.unwrapDataMap(response);

            if (!payload.isEmpty()) {
                // Extract array for requested content type
                String key = contentType.equalsIgnoreCase("FORM") ? "forms" :
                            contentType.equalsIgnoreCase("PROCESS") ? "processes" :
                            contentType.equalsIgnoreCase("DATA_TABLE") ? "dataTables" : null;

                if (key != null && payload.containsKey(key)) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> contents = (List<Map<String, Object>>) payload.get(key);
                    log.info("Got {} contents of type {} from key '{}'", contents.size(), contentType, key);
                    return contents;
                } else {
                    log.warn("Payload does not contain key '{}' for contentType '{}'", key, contentType);
                }
            } else {
                log.warn("Got empty payload from admin center (unwrap)");
            }

            return Collections.emptyList();

        } catch (Exception e) {
            log.error("Failed to get function unit contents for {}: {}", functionUnitIdOrCode, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Returns process history
     * Calls workflow-engine process history with resolved user display names
     */
    public List<Map<String, Object>> getProcessHistory(String processId) {
        log.debug("ProcessComponent.getProcessHistory called for: {}", processId);

        if (!workflowEngineClient.isAvailable()) {
            log.warn("Workflow engine not available, returning empty history");
            return Collections.emptyList();
        }

        log.debug("Workflow engine is available, calling getProcessInstanceHistory");

        try {
            // Calls workflow-engine process instance history API by processInstanceId
            // Queries Flowable historic activities and resolves display names
            Optional<List<Map<String, Object>>> historyResult = workflowEngineClient.getProcessInstanceHistory(processId);

            if (historyResult.isPresent()) {
                List<Map<String, Object>> history = historyResult.get();
                log.debug("Got {} history records for process: {}", history.size(), processId);
                return history;
            } else {
                log.warn("Failed to get process history from workflow engine for process: {}", processId);
                return Collections.emptyList();
            }

        } catch (Exception e) {
            log.error("Failed to get process history for {}: {}", processId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Marks process as completed
     * Invoked by workflow-engine process completion listener
     */
    public void markProcessAsCompleted(String processId, String lastActivityName) {
        log.debug("ProcessComponent.markProcessAsCompleted called for: {} with lastActivity: {}",
                processId, lastActivityName);

        try {
            Optional<ProcessInstance> optInstance = processInstanceRepository.findById(processId);
            if (optInstance.isEmpty()) {
                log.warn("Process instance not found in local database: {}", processId);
                return;
            }

            ProcessInstance instance = optInstance.get();

            // Update only processes still RUNNING
            if ("RUNNING".equals(instance.getStatus())) {
                instance.setStatus("COMPLETED");
                LocalDateTime finishedAt = LocalDateTime.now();
                instance.setEndTime(finishedAt);
                instance.setCompletedAt(finishedAt);
                // Completed processes have no current step; see process history for last node
                instance.setCurrentNode(null);
                // Clear current assignee
                instance.setCurrentAssignee(null);
                processInstanceRepository.save(instance);
                log.info("Process instance {} marked as COMPLETED (current step cleared)", processId);
            } else {
                log.info("Process instance {} already has status: {}, skipping update",
                        processId, instance.getStatus());
            }

        } catch (Exception e) {
            log.error("Failed to mark process as completed for {}: {}", processId, e.getMessage(), e);
        }
    }

    /**
     * Get action definitions by IDs.
     * Delegates to ActionDefinitionRepository.
     */
    public List<ActionDefinition> getActionsByIds(List<String> ids) {
        List<ActionDefinition> actions = actionDefinitionRepository.findAllById(ids);
        if (actions.isEmpty()) {
            log.warn("No actions found for ids: {}", ids);
        }
        return actions;
    }
}
