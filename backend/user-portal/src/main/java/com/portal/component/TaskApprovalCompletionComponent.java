package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.ChangeHistoryContext;
import com.portal.dto.SubTableChange;
import com.portal.dto.TaskCompleteRequest;
import com.portal.dto.TaskInfo;
import com.portal.entity.ProcessInstance;
import com.portal.exception.PortalException;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.util.SubTableNestingSanitizer;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Approval (APPROVE/REJECT) completion path: builds outcome variables, injects the MI collection,
 * completes the task in Flowable, then best-effort syncs portal {@link ProcessInstance} state and
 * records change history.
 * Extracted from {@link TaskProcessComponent} (which keeps the {@code completeTask} entry point and
 * its transaction boundary).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskApprovalCompletionComponent {

    private final WorkflowEngineClient workflowEngineClient;
    private final EngineSubTableHydrator engineSubTableHydrator;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ChangeHistoryComponent changeHistoryComponent;
    private final TaskFormComponent taskFormComponent;
    private final MiCollectionVariableBuilder miCollectionVariableBuilder;
    private final ProcessInstanceSyncComponent processInstanceSyncComponent;
    private final TaskPermissionEvaluator taskPermissionEvaluator;

    /** Lazy: server-side formula columns; null in {@code new}-constructed tests skips recalculation. */
    @Lazy
    @Autowired
    private ComputedFieldRecalculator computedFieldRecalculator;

    /** Lazy: re-derives the readonly Request ID; null in {@code new}-constructed tests skips stamping. */
    @Lazy
    @Autowired
    private RequestIdEnricher requestIdEnricher;

    /**
     * Handles approval completion
     * Via WorkflowEngineClient calling Flowable engine
     */
    void handleApproval(TaskInfo task, TaskCompleteRequest request, String userId, String portalUsername) {
        String taskId = task.getTaskId();
        String action = request.getAction();

        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }

        log.info("Using Flowable engine to complete task: {} with action: {}", taskId, action);

        // Start with variables from request if provided
        Map<String, Object> variables = new HashMap<>();
        if (request.getVariables() != null) {
            variables.putAll(request.getVariables());
        }

        // Merge explicit form payload before locking outcome variables. Form schemas may reuse names like
        // "decision" or "approvalStatus"; if we merge formData AFTER setting those keys, user/stale values win
        // and Flowable completion fails with engine error → PortalException 500.
        if (request.getFormData() != null) {
            variables.putAll(request.getFormData());
        }
        Map<String, Object> submittedSnapshot = taskFormComponent.copyChangeHistorySubmission(request.getFormData());
        if (submittedSnapshot == null) {
            submittedSnapshot = request.getFormData() == null
                ? new HashMap<>()
                : new HashMap<>(request.getFormData());
        }
        // Only a sub-table explicitly submitted through formData and authorized by the current
        // Task Form contract can represent user intent. variables may contain engine/MI state.
        Map<String, Object> initialFilteredSubmission = taskFormComponent.filterTaskSubmissionForChangeHistory(
            task.getProcessInstanceId(), task.getTaskDefinitionKey(), submittedSnapshot, variables);
        boolean submittedSubTables = initialFilteredSubmission != null
            && initialFilteredSubmission.containsKey("__subTables__");
        Map<String, Object> explicitlySubmittedSubTables = submittedSubTables
            && submittedSnapshot.get("__subTables__") instanceof Map<?, ?> submittedMap
                ? copyStringKeyMap(submittedMap)
                : Map.of();

        variables.put("action", action);
        if ("APPROVE".equals(action)) {
            variables.put("decision", "yes");
            variables.put("approvalStatus", "APPROVED");
            log.info("Set decision=yes for APPROVE action");
        } else if ("REJECT".equals(action)) {
            variables.put("decision", "no");
            variables.put("approvalStatus", "REJECTED");
            log.info("Set decision=no for REJECT action");
        }
        if (request.getComment() != null && !request.getComment().isEmpty()) {
            variables.put("approverComments", request.getComment());
        }

        // Approval submit is often incremental; __subTables__ may only exist on TaskInfo (merged ProcessInstance).
        // Without merge here, injectMiCollectionFromBpmn sees no sub-table rows → empty MI collection → zero child tasks.
        miCollectionVariableBuilder.mergeSubTablesFromTaskInfoForMi(task, variables);
        Object subTablesAfterMerge = variables.get("__subTables__");
        if (!(subTablesAfterMerge instanceof Map<?, ?> subMap) || subMap.isEmpty()) {
            log.warn("[MI] After TaskInfo merge, variables have no __subTables__ (taskId={}, processInstanceId={}). "
                    + "Multi-instance injection will not be able to build row collection.",
                    task.getTaskId(), task.getProcessInstanceId());
        }

        // If MI sub-process prerequisite, read collection variable and assignee field from BPMN and build collection
        miCollectionVariableBuilder.injectMiCollectionFromBpmn(
                task.getProcessDefinitionKey(), task.getTaskDefinitionKey(), task.getProcessInstanceId(), variables);

        // Guard against wiping a service task's output: a bare/empty approval (e.g. an action button that
        // never loaded or edited the grid) can carry an empty __subTables__ slice which, sent to Flowable,
        // overwrites populated engine rows with []. Fill empty outbound slices from the live engine first.
        preserveEngineSubTablesOnComplete(
            task.getProcessInstanceId(), variables, explicitlySubmittedSubTables);

        Map<String, Object> variablesForEngine =
                mergeApprovalVariables(task.getProcessInstanceId(), variables);

        log.info("Variables before calling workflowEngineClient: {}", variablesForEngine);

        String onBehalfOfUserId = null;
        if (taskPermissionEvaluator.isSingleTaskDelegatee(task, userId, portalUsername)
                && task.getAssignee() != null && !task.getAssignee().isBlank()) {
            onBehalfOfUserId = task.getAssignee();
        }

        Optional<Map<String, Object>> result = workflowEngineClient.completeTask(
                taskId, userId, action, variablesForEngine, onBehalfOfUserId);

        if (result.isEmpty()) {
            throw new PortalException("500", "Failed to complete task: " + taskId);
        }

        Map<String, Object> data = result.get();
        if (!Boolean.TRUE.equals(data.get("success"))) {
            String message = data.get("message") != null ? (String) data.get("message") : "Failed to complete task";
            if (TaskPermissionEvaluator.isEngineTaskInactiveMessage(message)) {
                throw new PortalException("409",
                        "The task is no longer active in the workflow engine (completed, cancelled, or superseded). "
                                + "Please refresh your todo list.");
            }
            throw new PortalException("500", message);
        }

        log.info("Task {} completed via Flowable by user {} with action {} (approvalStatus: {})",
                taskId, userId, action, variablesForEngine.get("approvalStatus"));

        
        AtomicReference<Map<String, Object>> preSyncVariablesRef = new AtomicReference<>(Map.of());

        // Sync approval variables to local ProcessInstance for Completed Tasks / My Requests
        // Must copy into a new HashMap; in-place edit breaks Hibernate JSON dirty detection
        // Same reference makes Hibernate dirty-check think unchanged and skip UPDATE
        try {
            String syncProcessId = task.getProcessInstanceId();
            Optional<ProcessInstance> syncOpt = processInstanceRepository.findById(syncProcessId);
            if (syncOpt.isPresent()) {
                ProcessInstance syncInstance = syncOpt.get();
                Map<String, Object> existingVars = syncInstance.getVariables();
                
                preSyncVariablesRef.set(existingVars != null ? new HashMap<>(existingVars) : Map.of());

                Map<String, Object> mergedVars = new HashMap<>(variablesForEngine);

                taskFormComponent.mergeCompletedTaskSnapshotIntoVariables(
                        taskId, userId, task.getTaskDefinitionKey(), syncProcessId, mergedVars);
                // Prevent geometric __subTables__ bloat: collapse deep nested copies to the canonical
                // one-level structure before persisting the approval write-back.
                SubTableNestingSanitizer.stripDeepNestedSubTables(mergedVars);
                recalculateComputedFields(syncInstance.getFunctionUnitCode(), mergedVars);
                stampRequestId(syncInstance.getFunctionUnitCode(), mergedVars);
                syncInstance.setVariables(mergedVars);

                processInstanceRepository.save(syncInstance);

                log.info("Synced {} approval variables back to local ProcessInstance {}",
                        mergedVars.size(), syncProcessId);
            }
        } catch (PortalException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("Data access failure syncing approval variables to local ProcessInstance (task {}): {}",
                    taskId, e.getMessage());
            throw e;
        } catch (OptimisticLockException e) {
            log.warn("Optimistic lock failure syncing approval variables (task {}): {}", taskId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("Failed to sync approval variables to local ProcessInstance: {}", e.getMessage());
            ProcessInstanceSyncComponent.rethrowIfRollbackOnlyAfterCatch(e, taskId);
        }

        // Record Change_History for field and sub-table changes during approval (best-effort)
        try {
            String chProcessId = task.getProcessInstanceId();
            Optional<ProcessInstance> chOpt = processInstanceRepository.findById(chProcessId);
            if (chOpt.isPresent()) {
                ProcessInstance chInstance = chOpt.get();
                Map<String, Object> chOldVars = new HashMap<>(preSyncVariablesRef.get());
                // Audit the immutable user payload through the current form's editable-field contract.
                // Variables now also contain workflow outcomes, MI collection data and preservation overlays.
                Map<String, Object> chSubmitted = taskFormComponent.filterTaskSubmissionForChangeHistory(
                    chProcessId, task.getTaskDefinitionKey(), submittedSnapshot, variables);
                if (chSubmitted == null) {
                    chSubmitted = new HashMap<>(submittedSnapshot);
                }
                if (!chSubmitted.isEmpty()) {
                    ChangeHistoryContext chContext = ChangeHistoryContext.builder()
                            .processInstanceId(chProcessId)
                            .taskInstanceId(taskId)
                            .stageId(task.getTaskDefinitionKey())
                            .userId(userId)
                            .build();
                    // Record top-level field changes
                    changeHistoryComponent.recordFieldChanges(chContext, chOldVars, chSubmitted);
                    // Saves already write their own immutable history. Completion therefore
                    // compares with the latest pre-sync state, recording only edits made since
                    // the last save instead of replaying saved UPDATEs as new ROW_ADD events.
                    // Never audit a sub-table added only by MI/engine hydration on completion.
                    Object chNewSubTables = chSubmitted.get("__subTables__");
                    if (submittedSubTables && chNewSubTables != null) {
                        Object historyBaseline = resolveSubTableHistoryBaseline(preSyncVariablesRef.get());
                        Object filteredBaseline = taskFormComponent.filterTaskSubTableBaselineForChangeHistory(
                            chProcessId, task.getTaskDefinitionKey(), historyBaseline);
                        recordSubTableChangeHistory(chContext,
                            filteredBaseline,
                                chNewSubTables);
                    } else if (chNewSubTables != null) {
                        log.debug("Skipping sub-table change history for task {}: data was merged internally, not submitted by the user",
                                taskId);
                    }
                }
            }
        } catch (PortalException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("Data access failure recording change history during task completion (task {}): {}",
                    taskId, e.getMessage());
            throw e;
        } catch (OptimisticLockException e) {
            log.warn("Optimistic lock failure recording change history (task {}): {}", taskId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("Failed to record change history during task completion (task {}): {}",
                    taskId, e.getMessage());
            ProcessInstanceSyncComponent.rethrowIfRollbackOnlyAfterCatch(e, taskId);
        }

        // After task completion, check for active tasks; none may mean process completed
        // Compensation when ProcessCompletionListener notification fails and portal state drifts
        try {
            String processInstanceId = task.getProcessInstanceId();

            // Check process status via workflowEngineClient
            Optional<Map<String, Object>> processStatus = workflowEngineClient.getProcessInstanceStatus(processInstanceId);
            if (processStatus.isPresent()) {
                Map<String, Object> status = processStatus.get();
                Boolean isCompleted = (Boolean) status.get("completed");

                if (Boolean.TRUE.equals(isCompleted)) {
                    log.info("Process {} is completed after task completion, syncing portal process instance", processInstanceId);

                    // Update process instance status
                    Optional<ProcessInstance> optInstance = processInstanceRepository.findById(processInstanceId);
                    if (optInstance.isPresent()) {
                        ProcessInstance instance = optInstance.get();
                        if ("RUNNING".equals(instance.getStatus())) {
                            instance.setStatus("COMPLETED");
                            LocalDateTime finishedAt = LocalDateTime.now();
                            instance.setEndTime(finishedAt);
                            instance.setCompletedAt(finishedAt);
                            instance.setCurrentNode(null);
                            instance.setCurrentAssignee(null);
                            processInstanceRepository.save(instance);
                            log.info("Process instance {} updated to COMPLETED with currentNode: {}",
                                    processInstanceId, instance.getCurrentNode());
                        }
                    }
                } else {
                    // Process still running; try next task info
                    String nextTaskName = (String) status.get("nextTaskName");
                    String nextAssignee = (String) status.get("nextAssignee");
                    String nextCandidateUsers = (String) status.get("nextCandidateUsers");
                    if (nextTaskName != null) {
                        processInstanceSyncComponent.updateProcessInstanceAssignee(
                                processInstanceId, nextAssignee, nextCandidateUsers, nextTaskName);
                        log.info("Process {} continues with next task: {}", processInstanceId, nextTaskName);
                    } else {
                        // No next user task; may be at non-user task (e.g. end event)
                        // Try to load current activity
                        log.info("No next user task found for process {}, checking for current activity", processInstanceId);
                        Optional<Map<String, Object>> currentActivity = processInstanceSyncComponent.getCurrentActivity(processInstanceId);
                        if (currentActivity.isPresent()) {
                            String currentActivityName = (String) currentActivity.get().get("activityName");
                            String currentActivityType = (String) currentActivity.get().get("activityType");
                            log.info("Current activity for process {}: {} (type: {})",
                                    processInstanceId, currentActivityName, currentActivityType);

                            // Skip SequenceFlow: name is a condition label (e.g. Yes/No), not currentNode
                            if ("SequenceFlow".equals(currentActivityType)) {
                                log.warn("Current activity is SequenceFlow (name: {}), skipping currentNode update for process {}",
                                        currentActivityName, processInstanceId);
                            } else {
                                // Update process instance current node
                                Optional<ProcessInstance> optInstance = processInstanceRepository.findById(processInstanceId);
                                if (optInstance.isPresent()) {
                                    ProcessInstance instance = optInstance.get();
                                    instance.setCurrentNode(currentActivityName);
                                    instance.setCurrentAssignee(null);

                                    // End event means process completed
                                    if ("endEvent".equals(currentActivityType) || "EndEvent".equals(currentActivityType)) {
                                        log.info("Current activity is end event, marking process {} as COMPLETED", processInstanceId);
                                        instance.setStatus("COMPLETED");
                                        LocalDateTime finishedAt = LocalDateTime.now();
                                        instance.setEndTime(finishedAt);
                                        instance.setCompletedAt(finishedAt);
                                        instance.setCurrentNode(null);
                                    }

                                    processInstanceRepository.save(instance);
                                    log.info("Updated process instance {} currentNode to: {}, status: {}",
                                            processInstanceId, instance.getCurrentNode(), instance.getStatus());
                                }
                            }
                        }
                    }
                }
            }
        } catch (PortalException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("Data access failure checking process status after task completion (task {}): {}",
                    taskId, e.getMessage());
            throw e;
        } catch (OptimisticLockException e) {
            log.warn("Optimistic lock failure checking process status after task completion (task {}): {}",
                    taskId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("Unexpected failure checking process status after task completion (task {}): {}",
                    taskId, e.getMessage());
            ProcessInstanceSyncComponent.rethrowIfRollbackOnlyAfterCatch(e, taskId);
        }
    }

    /**
     * Prevents a task completion from wiping a service task's sub-table output. When the outbound
     * {@code variables} carry a {@code __subTables__} with an empty slice (e.g. a bare approval that
     * never loaded or edited the grid), sending it to Flowable would overwrite the populated engine
    * rows with {@code []}. Fills only internally produced empty/missing slices from the live engine's
    * {@code __subTables__}; explicitly submitted slices, including delete-all, are left untouched. Best-effort — a failed
     * engine round-trip leaves {@code variables} unchanged. Shares the fill-empty merge with the
     * read paths via {@link EngineSubTableHydrator}; the {@code hasEmptySlice} pre-check keeps this
     * write path from making the engine round-trip when there is nothing to protect.
     */
    void preserveEngineSubTablesOnComplete(String processInstanceId, Map<String, Object> variables) {
        preserveEngineSubTablesOnComplete(processInstanceId, variables, Map.of());
    }
    @SuppressWarnings("unchecked")
    void preserveEngineSubTablesOnComplete(String processInstanceId,
                                           Map<String, Object> variables,
                                           Map<String, Object> explicitlySubmittedSubTables) {
        if (processInstanceId == null || variables == null) {
            return;
        }
        // Only a populated __subTables__ can overwrite the engine; if absent, Flowable keeps its own value.
        if (!(variables.get("__subTables__") instanceof Map<?, ?> outMap) || outMap.isEmpty()) {
            return;
        }
        Set<String> explicitKeys = explicitlySubmittedSubTables != null
                ? explicitlySubmittedSubTables.keySet() : Set.of();
        boolean hasEmptySlice = outMap.entrySet().stream()
                .anyMatch(entry -> !explicitKeys.contains(String.valueOf(entry.getKey()))
                        && (!(entry.getValue() instanceof List<?> rows) || rows.isEmpty()));
        if (!hasEmptySlice) {
            return; // every outbound slice already carries rows — nothing to protect, skip the round-trip
        }
        engineSubTableHydrator.mergeFromEngine(processInstanceId, (Map<String, Object>) outMap)
                .ifPresent(result -> {
                    Map<String, Object> merged = new LinkedHashMap<>(result.mergedSubTables());
                    if (explicitlySubmittedSubTables != null) {
                        merged.putAll(explicitlySubmittedSubTables);
                    }
                    variables.put("__subTables__", merged);
                    log.info("[MI] Preserved engine __subTables__ slices on completion for process {} "
                            + "(filled empty outbound slice(s) to avoid overwriting service-task output)", processInstanceId);
                });
    }
    private static Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) copy.put(String.valueOf(key), value);
        });
        return copy;
    }
    // ========== Sub-table change history helpers ==========
    /** Compare completion against the latest process state because incremental saves already emitted history. */
    static Object resolveSubTableHistoryBaseline(Map<String, Object> preSyncVariables) {
        return preSyncVariables != null ? preSyncVariables.get("__subTables__") : null;
    }

    @SuppressWarnings("unchecked")
    private void recordSubTableChangeHistory(ChangeHistoryContext context,
                                              Object oldSubTablesObj,
                                              Object newSubTablesObj) {
        if (newSubTablesObj == null) {
            log.debug("Sub-table change history skipped: newSubTablesObj is null");
            return;
        }
        try {
            Map<String, List<Map<String, Object>>> oldRowsByTable =
                    ChangeHistoryComponent.normalizeSubTableRowsByHistoryName(oldSubTablesObj,
                            changeHistoryComponent::designerPrimaryKeyFieldsForSliceKey);

            // Build newRows from ALL keys (including numeric binding IDs that normalizeSubTableRowsByHistoryName skips).
            Map<String, List<Map<String, Object>>> newRowsByTable = new HashMap<>();
            // First pass: use the normal normalization (text-key aliases)
            Map<String, List<Map<String, Object>>> normalizedNew =
                    ChangeHistoryComponent.normalizeSubTableRowsByHistoryName(newSubTablesObj,
                            changeHistoryComponent::designerPrimaryKeyFieldsForSliceKey);
            newRowsByTable.putAll(normalizedNew);

            // Collect rows from numeric (binding ID) keys
            Map<String, List<Map<String, Object>>> numericNewRows = new HashMap<>();
            if (newSubTablesObj instanceof Map<?, ?> rawNew) {
                for (Map.Entry<?, ?> entry : rawNew.entrySet()) {
                    String key = entry.getKey() != null ? entry.getKey().toString() : "";
                    if (!key.matches("\\d+")) continue;
                    if (!(entry.getValue() instanceof List<?> rows)) continue;
                    for (Object row : rows) {
                        if (!(row instanceof Map<?, ?> rowMap)) continue;
                        numericNewRows.computeIfAbsent(key, k -> new ArrayList<>())
                                .add((Map<String, Object>) rowMap);
                    }
                }
            }

            if (!numericNewRows.isEmpty()) {
                if (oldRowsByTable.isEmpty()) {
                    // Both old and new only have numeric keys — merge all into one comparison
                    // to avoid duplicates from the same rows appearing under different binding IDs.
                    log.debug("Both old and new are numeric-only: merging all binding IDs into one comparison");
                    List<Map<String, Object>> mergedOldRows = new ArrayList<>();
                    List<Map<String, Object>> mergedNewRows = new ArrayList<>();
                    Set<Object> seenOld = new HashSet<>();
                    Set<Object> seenNew = new HashSet<>();

                    // Collect old numeric rows (dedup by rowId)
                    if (oldSubTablesObj instanceof Map<?, ?> rawOld) {
                        for (Map.Entry<?, ?> entry : rawOld.entrySet()) {
                            String key = entry.getKey() != null ? entry.getKey().toString() : "";
                            if (!key.matches("\\d+")) continue;
                            if (!(entry.getValue() instanceof List<?> rows)) continue;
                            // Identity per slice, from the key that slice's table declares.
                            List<String> oldPk =
                                    changeHistoryComponent.designerPrimaryKeyFieldsForSliceKey(key);
                            for (Object row : rows) {
                                if (!(row instanceof Map<?, ?> rowMap)) continue;
                                Object rowId = ChangeHistoryComponent.resolveRowIdentifier(
                                        (Map<String, Object>) rowMap, oldPk);
                                if (rowId != null && seenOld.add(rowId)) {
                                    mergedOldRows.add((Map<String, Object>) rowMap);
                                }
                            }
                        }
                    }

                    // Collect new numeric rows (dedup by rowId)
                    for (Map.Entry<String, List<Map<String, Object>>> entry : numericNewRows.entrySet()) {
                        List<String> newPk = changeHistoryComponent
                                .designerPrimaryKeyFieldsForSliceKey(entry.getKey());
                        for (Map<String, Object> row : entry.getValue()) {
                            Object rowId = ChangeHistoryComponent.resolveRowIdentifier(row, newPk);
                            if (rowId != null && seenNew.add(rowId)) {
                                mergedNewRows.add(row);
                            }
                        }
                    }

                    // Use the binding ID as a single virtual table name for recording
                    String virtualTable = numericNewRows.keySet().iterator().next();
                    oldRowsByTable = Map.of(virtualTable, mergedOldRows);
                    newRowsByTable = Map.of(virtualTable, mergedNewRows);
                } else {
                    // Match numeric-key rows to old table groups by row ID
                    for (Map.Entry<String, List<Map<String, Object>>> entry : numericNewRows.entrySet()) {
                        List<String> newPk = changeHistoryComponent
                                .designerPrimaryKeyFieldsForSliceKey(entry.getKey());
                        for (Map<String, Object> row : entry.getValue()) {
                            Object rowId = ChangeHistoryComponent.resolveRowIdentifier(row, newPk);
                            if (rowId == null) continue;
                            for (Map.Entry<String, List<Map<String, Object>>> oldEntry : oldRowsByTable.entrySet()) {
                                // The old side is keyed by ITS own table's primary key, which need
                                // not be the same column as the new slice's.
                                List<String> oldPk = changeHistoryComponent
                                        .designerPrimaryKeyFieldsForSliceKey(oldEntry.getKey());
                                for (Map<String, Object> oldRow : oldEntry.getValue()) {
                                    if (java.util.Objects.equals(rowId,
                                            ChangeHistoryComponent.resolveRowIdentifier(oldRow, oldPk))) {
                                        newRowsByTable.computeIfAbsent(oldEntry.getKey(), k -> new ArrayList<>()).add(row);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                log.debug("Merged numeric-key rows: old tables={}, new tables={}",
                        oldRowsByTable.keySet(), newRowsByTable.keySet());
            }

            log.debug("Sub-table comparison: old tables={}, new tables={}",
                    oldRowsByTable.keySet(), newRowsByTable.keySet());
            int totalChanges = 0;
            for (Map.Entry<String, List<Map<String, Object>>> subTableEntry : newRowsByTable.entrySet()) {
                String subTableKey = subTableEntry.getKey();
                List<Map<String, Object>> newRows = subTableEntry.getValue();
                List<Map<String, Object>> oldRows = oldRowsByTable.getOrDefault(subTableKey, List.of());
                // Pair rows by the identity this table actually declares, resolved per slice.
                List<SubTableChange> changes = computeSubTableRowChanges(oldRows, newRows,
                        changeHistoryComponent.designerPrimaryKeyFieldsForSliceKey(subTableKey));
                log.debug("  table={}: oldRows={}, newRows={}, changes={}",
                        subTableKey, oldRows.size(), newRows.size(), changes.size());
                totalChanges += changes.size();
                if (!changes.isEmpty()) {
                    // Use pre-normalized name to bypass the numeric-key skip in recordSubTableChanges
                    changeHistoryComponent.recordSubTableChangesWithName(
                            context, subTableKey, changes);
                }
            }
            log.debug("Sub-table change history recorded: {} total changes", totalChanges);
        } catch (Exception e) {
            log.warn("Failed to record sub-table changes during task completion: {}", e.getMessage(), e);
        }
    }

    static List<SubTableChange> computeSubTableRowChanges(
            List<Map<String, Object>> oldRows,
            List<Map<String, Object>> newRows) {
        return computeSubTableRowChanges(oldRows, newRows, null);
    }

    /**
     * @param designerPrimaryKeyFields the table's configured primary key, so rows are paired by the
     *                                 identity the designer declared rather than by a guessed
     *                                 column name
     */
    static List<SubTableChange> computeSubTableRowChanges(
            List<Map<String, Object>> oldRows,
            List<Map<String, Object>> newRows,
            List<String> designerPrimaryKeyFields) {
        return SubTableChangeHistoryDiff.compute(oldRows, newRows, designerPrimaryKeyFields);
    }

    /**
     * Merges the portal's stored variables with this approval submission, then recomputes formula
     * columns on the full record.
     *
     * <p>Approval payloads are often incremental: recomputing on {@code submission} alone would treat
     * fields not present in this form as blank and overwrite correct stored values. Flowable also
     * needs authoritative computed values before gateway conditions run.
     */
    private Map<String, Object> mergeApprovalVariables(String processInstanceId, Map<String, Object> submission) {
        Map<String, Object> merged = new HashMap<>();
        Optional<ProcessInstance> processInstance = processInstanceRepository.findById(processInstanceId);
        String functionUnitCode = processInstance.map(ProcessInstance::getFunctionUnitCode).orElse(null);
        processInstance.map(ProcessInstance::getVariables).ifPresent(existing -> {
            if (existing != null) {
                merged.putAll(existing);
            }
        });
        if (submission != null) {
            merged.putAll(submission);
        }
        recalculateComputedFields(functionUnitCode, merged);
        return merged;
    }

    private void recalculateComputedFields(String functionUnitCode, Map<String, Object> variables) {
        ComputedFieldRecalculator recalculator = computedFieldRecalculator;
        if (recalculator == null || functionUnitCode == null || functionUnitCode.isBlank() || variables == null) {
            return;
        }
        recalculator.recalculate(functionUnitCode, variables);
    }

    /**
     * Re-derive the readonly Request ID before the approval write-back is persisted, so an approval
     * that edits a contributing main-table field cannot leave the stored identifier stale.
     */
    private void stampRequestId(String functionUnitCode, Map<String, Object> variables) {
        RequestIdEnricher enricher = requestIdEnricher;
        if (enricher == null || functionUnitCode == null || functionUnitCode.isBlank() || variables == null) {
            return;
        }
        enricher.stampRequestId(functionUnitCode, variables);
    }
}
