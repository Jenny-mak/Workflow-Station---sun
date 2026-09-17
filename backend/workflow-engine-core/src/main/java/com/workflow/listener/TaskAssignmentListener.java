package com.workflow.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.component.BpmnActionParser;
import com.workflow.component.UserTaskExtendedInfoWriter;
import com.workflow.enums.AssigneeAnchor;
import com.workflow.enums.AssigneeType;
import com.workflow.enums.AssignmentType;
import com.platform.messaging.support.NotificationDispatchHelper;
import com.workflow.repository.ExtendedTaskInfoRepository;
import com.workflow.service.LastUserTaskAssigneeQuery;
import com.workflow.service.TaskAssigneeResolver;
import com.workflow.util.AssigneeRoleIdsSupport;
import com.workflow.util.RollbackAssigneeFallbackSupport;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.workflow.listener.AssigneeUserIdNormalizer.extractUserIdFromRefMap;
import static com.workflow.listener.AssigneeUserIdNormalizer.sanitizeFlowableUserIds;
import static com.workflow.listener.AssigneeUserIdNormalizer.splitUserList;
import static com.workflow.listener.UserTaskExtensionPropertyReader.getExtensionProperty;

/**
 * Resolves assignee when a task is created, based on BPMN extension attributes such as {@code assigneeType}.
 * <p>See {@code .kiro/docs/assignee-type-convergence.md} for semantics.</p>
 */
@Slf4j
@Component
public class TaskAssignmentListener implements FlowableEventListener {

    @Autowired
    @Lazy
    private TaskAssigneeResolver taskAssigneeResolver;

    @Autowired
    @Lazy
    private LastUserTaskAssigneeQuery lastUserTaskAssigneeQuery;

    @Autowired
    @Lazy
    private TaskService taskService;

    @Autowired
    @Lazy
    private RuntimeService runtimeService;

    @Autowired
    @Lazy
    private HistoryService historyService;

    @Autowired
    @Lazy
    private RepositoryService repositoryService;

    @Autowired
    @Lazy
    private BpmnActionParser bpmnActionParser;

    @Autowired
    @Lazy
    private ExtendedTaskInfoRepository extendedTaskInfoRepository;

    @Autowired
    @Lazy
    private NotificationDispatchHelper notificationDispatchHelper;

    @Autowired
    @Lazy
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Lazy
    private MultiInstanceTaskWriter multiInstanceTaskWriter;

    @Autowired
    @Lazy
    private com.workflow.client.AdminCenterClient adminCenterClient;

    @Autowired
    @Lazy
    private com.workflow.component.ExceptionHandlerComponent exceptionHandlerComponent;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 分派失败留痕的 task local 变量名：失败原因（human-readable）。 */
    public static final String VAR_ASSIGNMENT_FAILURE = "assignmentFailure";
    /** 分派失败留痕的 task local 变量名：失败类别（INFRA=服务故障可自动补分派 / CONFIG=配置错误需人工 / ERROR=未预期异常）。 */
    public static final String VAR_ASSIGNMENT_FAILURE_KIND = "assignmentFailureKind";

    // ---- Package-private accessors so collaborators can read injected services without owning them.
    // Unit tests instantiate this listener via the no-arg constructor and inject mocks into these fields
    // by reflection (no Spring context); reading via the owner keeps those tests working unchanged. ----

    TaskService taskService() {
        return taskService;
    }

    RuntimeService runtimeService() {
        return runtimeService;
    }

    RepositoryService repositoryService() {
        return repositoryService;
    }

    BpmnActionParser bpmnActionParser() {
        return bpmnActionParser;
    }

    ExtendedTaskInfoRepository extendedTaskInfoRepository() {
        return extendedTaskInfoRepository;
    }

    JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    TaskAssigneeResolver taskAssigneeResolver() {
        return taskAssigneeResolver;
    }

    /**
     * Lazily resolves the MI writer collaborator. Falls back to a plain instance when the {@code @Lazy} bean
     * was not injected (e.g. unit tests that construct this listener directly without a Spring context).
     */
    private MultiInstanceTaskWriter miWriter() {
        MultiInstanceTaskWriter writer = multiInstanceTaskWriter;
        if (writer == null) {
            writer = new MultiInstanceTaskWriter();
            multiInstanceTaskWriter = writer;
        }
        return writer;
    }

    /**
     * Non-MI user tasks still need {@code wf_extended_task_info} for claim/delegate.
     * Existing rows (including MI JSON) are left unchanged.
     */
    private void persistRegularExtendedTask(TaskEntity task, AssignmentType type, String target) {
        if (extendedTaskInfoRepository == null || task == null) {
            return;
        }
        try {
            UserTaskExtendedInfoWriter.persistIfAbsent(extendedTaskInfoRepository, task, type, target);
        } catch (Exception e) {
            log.warn("Failed to persist wf_extended_task_info for {}: {}", task.getId(), e.getMessage());
        }
    }

    private static List<String> sanitizeCandidateIds(List<String> candidateUsers) {
        List<String> ids = new ArrayList<>();
        if (candidateUsers == null) {
            return ids;
        }
        for (String candidateUser : candidateUsers) {
            if (candidateUser != null && !candidateUser.isBlank()) {
                ids.add(candidateUser.trim());
            }
        }
        return ids;
    }

    /**
     * 分派失败留痕：写 task local 变量（可查询、TaskOrphanRepairService 修复后清除）并落 ExceptionRecord
     * （HIGH/CRITICAL 走既有 notificationManager 告警）。
     *
     * <p>留痕动作整体 try-catch：留痕失败绝不能反过来中断任务创建主流程。
     * FALLBACK(external): 该兜底只影响可观测性（少一条痕迹），不产生错误业务数据。</p>
     */
    private void recordAssignmentFailure(String taskId, String processInstanceId, String taskDefinitionKey,
                                         String errorMessage, String failureKind, Exception cause) {
        try {
            if (taskService != null) {
                taskService.setVariableLocal(taskId, VAR_ASSIGNMENT_FAILURE, errorMessage);
                taskService.setVariableLocal(taskId, VAR_ASSIGNMENT_FAILURE_KIND, failureKind);
            }
            if (exceptionHandlerComponent != null) {
                // INFRA 无 cause 时包装成 AdminCenterUnavailableException，让 determineSeverity 分到 HIGH 告警。
                Exception recorded = cause != null ? cause
                        : "INFRA".equals(failureKind)
                                ? new com.workflow.exception.AdminCenterUnavailableException(
                                        "Task assignment failed (INFRA): " + errorMessage, null)
                                : new IllegalStateException(
                                        "Task assignment failed (" + failureKind + "): " + errorMessage);
                exceptionHandlerComponent.recordException(recorded, processInstanceId, taskId,
                        taskDefinitionKey, null);
            }
        } catch (Exception e) {
            log.error("Failed to record assignment failure trace for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Map the workspace-context {@code activeBusinessUnitId} (a BU id) to a BU code via AdminCenter.
     * When the client bean was not injected (unit tests constructing this listener directly), the
     * original id is returned unchanged rather than NPE-ing.
     */
    String mapActiveBusinessUnitIdToCode(String businessUnitId) {
        if (businessUnitId == null || businessUnitId.isBlank() || adminCenterClient == null) {
            return businessUnitId;
        }
        String code = adminCenterClient.getBusinessUnitCodeById(businessUnitId.trim());
        return (code != null && !code.isBlank()) ? code : businessUnitId;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() == FlowableEngineEventType.TASK_CREATED) {
            handleTaskCreated(event);
        }
    }

    private void handleTaskCreated(FlowableEvent event) {
        if (!(event instanceof FlowableEntityEvent)) {
            return;
        }

        FlowableEntityEvent entityEvent = (FlowableEntityEvent) event;
        Object entity = entityEvent.getEntity();

        if (!(entity instanceof TaskEntity)) {
            return;
        }

        TaskEntity task = (TaskEntity) entity;
        String taskId = task.getId();
        String processInstanceId = task.getProcessInstanceId();
        String taskDefinitionKey = task.getTaskDefinitionKey();
        String processDefinitionId = task.getProcessDefinitionId();

        log.info("Task created: taskId={}, taskName={}, taskDefKey={}, processInstanceId={}",
                taskId, task.getName(), taskDefinitionKey, processInstanceId);

        if (task.getAssignee() != null && !task.getAssignee().isEmpty()) {
            log.info("Task {} already has assignee: {}", taskId, task.getAssignee());
            notifyNewTask(task.getAssignee(), taskId, task.getName(), processInstanceId);
            // BPMN often resolves assignee before this listener runs; we still need wf_extended_task_info
            // with multiInstance + subTableRowId or My Request / MI status API only sees completed historic rows.
            try {
                miWriter().ensureMultiInstanceExtendedTaskForPreassignedTask(this, task, taskId, processInstanceId,
                        processDefinitionId, taskDefinitionKey, null);
            } catch (Exception e) {
                log.warn("ensureMultiInstanceExtendedTaskForPreassignedTask failed for {}: {}", taskId, e.getMessage());
            }
            persistRegularExtendedTask(task, AssignmentType.USER, task.getAssignee());
            return;
        }

        try {
            String assigneeTypeRaw = null;
            String roleId = null;
            String roleIdsRaw = null;
            String businessUnitId = null;
            String assigneeValue = null;
            String assigneeAnchorExt = null;
            String manualAssignVariable = null;
            String manualAssignBuVariable = null;
            String manualAssignRoleVariable = null;
            String assigneeVariable = null;
            Map<String, Object> cachedVariables = null;

            if (processDefinitionId != null && taskDefinitionKey != null) {
                BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
                if (bpmnModel != null) {
                    FlowElement flowElement = bpmnModel.getFlowElement(taskDefinitionKey);
                    if (flowElement instanceof UserTask ut) {
                        assigneeTypeRaw = getExtensionProperty(ut, "assigneeType");
                        roleId = getExtensionProperty(ut, "roleId");
                        roleIdsRaw = getExtensionProperty(ut, "roleIds");
                        businessUnitId = getExtensionProperty(ut, "businessUnitId");
                        assigneeValue = getExtensionProperty(ut, "assigneeValue");
                        assigneeAnchorExt = getExtensionProperty(ut, "assigneeAnchor");
                        manualAssignVariable = getExtensionProperty(ut, "manualAssignVariable");
                        manualAssignBuVariable = getExtensionProperty(ut, "manualAssignBuVariable");
                        manualAssignRoleVariable = getExtensionProperty(ut, "manualAssignRoleVariable");
                        assigneeVariable = getExtensionProperty(ut, "assigneeVariable");

                        if ((assigneeTypeRaw == null || assigneeTypeRaw.isEmpty()) && ut.getAssignee() != null
                                && !ut.getAssignee().isBlank()) {
                            String ga = ut.getAssignee().trim();
                            if (isInitiatorExpression(ga)) {
                                assigneeTypeRaw = "INITIATOR";
                            }
                        }

                        log.info("Found BPMN extension: assigneeType={}, roleId={}, roleIds={}, businessUnitId={}, assigneeAnchor={}",
                                assigneeTypeRaw, roleId, roleIdsRaw, businessUnitId, assigneeAnchorExt);
                    }
                }
            }

            // Flowable's in-memory BpmnModel often misses designer custom namespace extensions; read from deployed XML
            // (consistent with actionIds fallback pattern)
            if (processDefinitionId != null && taskDefinitionKey != null) {
                assigneeTypeRaw = firstNonBlank(assigneeTypeRaw,
                        bpmnActionParser.getUserTaskExtensionPropertyValue(processDefinitionId, taskDefinitionKey,
                                "assigneeType"));
                roleId = firstNonBlank(roleId,
                        bpmnActionParser.getUserTaskExtensionPropertyValue(processDefinitionId, taskDefinitionKey,
                                "roleId"));
                roleIdsRaw = firstNonBlank(roleIdsRaw,
                        bpmnActionParser.getUserTaskExtensionPropertyValue(processDefinitionId, taskDefinitionKey,
                                "roleIds"));
                businessUnitId = firstNonBlank(businessUnitId,
                        bpmnActionParser.getUserTaskExtensionPropertyValue(processDefinitionId, taskDefinitionKey,
                                "businessUnitId"));
                assigneeValue = firstNonBlank(assigneeValue,
                        bpmnActionParser.getUserTaskExtensionPropertyValue(processDefinitionId, taskDefinitionKey,
                                "assigneeValue"));
                assigneeAnchorExt = firstNonBlank(assigneeAnchorExt,
                        bpmnActionParser.getUserTaskExtensionPropertyValue(processDefinitionId, taskDefinitionKey,
                                "assigneeAnchor"));
                manualAssignVariable = firstNonBlank(manualAssignVariable,
                        bpmnActionParser.getUserTaskExtensionPropertyValue(processDefinitionId, taskDefinitionKey,
                                "manualAssignVariable"));
                manualAssignBuVariable = firstNonBlank(manualAssignBuVariable,
                        bpmnActionParser.getUserTaskExtensionPropertyValue(processDefinitionId, taskDefinitionKey,
                                "manualAssignBuVariable"));
                manualAssignRoleVariable = firstNonBlank(manualAssignRoleVariable,
                        bpmnActionParser.getUserTaskExtensionPropertyValue(processDefinitionId, taskDefinitionKey,
                                "manualAssignRoleVariable"));
                assigneeVariable = firstNonBlank(assigneeVariable,
                        bpmnActionParser.getUserTaskExtensionPropertyValue(processDefinitionId, taskDefinitionKey,
                                "assigneeVariable"));
            }

            if (assigneeTypeRaw == null || assigneeTypeRaw.isEmpty()) {
                cachedVariables = runtimeService.getVariables(processInstanceId);
                assigneeTypeRaw = getStringVariable(cachedVariables, "assigneeType");
                roleId = firstNonBlank(roleId, getStringVariable(cachedVariables, "roleId"));
                businessUnitId = firstNonBlank(businessUnitId, getStringVariable(cachedVariables, "businessUnitId"));
                assigneeValue = firstNonBlank(assigneeValue, getStringVariable(cachedVariables, "assigneeValue"));
                assigneeAnchorExt = firstNonBlank(assigneeAnchorExt, getStringVariable(cachedVariables, "assigneeAnchor"));
            }

            assigneeTypeRaw = normalizeLegacyAssigneeType(assigneeTypeRaw, assigneeValue);
            if (assigneeTypeRaw != null && "INITIATOR".equalsIgnoreCase(assigneeTypeRaw.trim())) {
                assigneeValue = null;
            }

            if (assigneeTypeRaw == null || assigneeTypeRaw.isEmpty()) {
                log.warn("TaskAssignmentListener: task {} has no assigneeType in BPMN extensions, process variables, or delegateAssigneeVariable — task will remain unassigned (no assignee, no candidates). processInstanceId={}, taskDefKey={}, processDefId={}",
                        taskId, processInstanceId, taskDefinitionKey, processDefinitionId);
                return;
            }

            if ("ELEMENT_VARIABLE".equalsIgnoreCase(assigneeTypeRaw.trim())) {
                miWriter().handleElementVariableAssignment(this, task, taskId, processInstanceId,
                        processDefinitionId, taskDefinitionKey);
                return;
            }

            Map<String, Object> processVariables = cachedVariables != null
                    ? cachedVariables
                    : runtimeService.getVariables(processInstanceId);

            String initiatorId = getStringVariable(processVariables, "initiator");
            if (initiatorId == null || initiatorId.isEmpty()) {
                ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .singleResult();
                if (pi != null && pi.getStartUserId() != null && !pi.getStartUserId().isBlank()) {
                    initiatorId = pi.getStartUserId().trim();
                    log.info("Task {}: no initiator variable; using process startUserId as fallback: {}",
                            taskId, initiatorId);
                }
            }
            if (initiatorId == null || initiatorId.isEmpty()) {
                HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .singleResult();
                if (hpi != null && hpi.getStartUserId() != null && !hpi.getStartUserId().isBlank()) {
                    initiatorId = hpi.getStartUserId().trim();
                    log.info("Task {}: no initiator variable; using historic startUserId as fallback: {}",
                            taskId, initiatorId);
                }
            }
            if (initiatorId == null || initiatorId.isEmpty()) {
                log.warn("No initiator found for process instance {} (variable empty and no startUserId)",
                        processInstanceId);
                return;
            }

            AssigneeType resolvedType = AssigneeType.fromCode(assigneeTypeRaw.trim());
            if (resolvedType == null) {
                log.error("Unknown or deprecated assigneeType '{}' for task {}", assigneeTypeRaw, taskId);
                return;
            }

            if (resolvedType == AssigneeType.MANUAL_ASSIGN) {
                miWriter().updateCurrentItemProgress(this, processVariables, processDefinitionId, taskDefinitionKey,
                        task.getName());
                applyResolveResult(taskId, task, processInstanceId, processDefinitionId, taskDefinitionKey,
                        resolveManualAssign(taskDefinitionKey, manualAssignVariable, manualAssignBuVariable,
                                manualAssignRoleVariable, processVariables, initiatorId),
                        assigneeTypeRaw);
                return;
            }

            if (resolvedType == AssigneeType.ASSIGNEE_FROM_VARIABLE) {
                String varName = firstNonBlank(assigneeVariable, assigneeValue);
                TaskAssigneeResolver.ResolveResult vr = resolveAssigneeFromVariable(varName, processVariables);
                miWriter().updateCurrentItemProgress(this, processVariables, processDefinitionId, taskDefinitionKey,
                        task.getName());
                applyResolveResult(taskId, task, processInstanceId, processDefinitionId, taskDefinitionKey, vr,
                        assigneeTypeRaw);
                return;
            }

            if (roleId == null || roleId.isEmpty()) {
                roleId = assigneeValue;
            }
            if ((businessUnitId == null || businessUnitId.isEmpty()) && resolvedType == AssigneeType.BU_ROLE) {
                businessUnitId = assigneeValue;
            }

            if (resolvedType == AssigneeType.BU_ROLE && (businessUnitId == null || businessUnitId.isEmpty())) {
                log.warn("TaskAssignmentListener: BU_ROLE task {} has no businessUnitId — task will be created without assignee/candidates. roleId={}, activeBusinessUnitId from process vars={}",
                        taskId, roleId, getStringVariable(
                                cachedVariables != null ? cachedVariables
                                        : runtimeService.getVariables(processInstanceId), "activeBusinessUnitId"));
            }

            AssigneeAnchor anchor = computeAnchor(assigneeTypeRaw.trim(), resolvedType, assigneeAnchorExt);
            String anchorUserId = null;
            if (resolvedType.requiresAnchorUserId()) {
                anchorUserId = resolveAnchorUserId(anchor, initiatorId, processInstanceId);
            }

            // activeBusinessUnitId is the workspace-context variable, still stored as a BU *id*.
            // The task-assignment chain is code-based (BPMN role/BU are codes), so convert it to a
            // BU code before handing it to the resolver (used as the hierarchy anchor BU).
            String activeBusinessUnitIdRaw = getStringVariable(processVariables, "activeBusinessUnitId");
            String activeBusinessUnitId = mapActiveBusinessUnitIdToCode(activeBusinessUnitIdRaw);

            List<String> resolvedRoleIds = AssigneeRoleIdsSupport.parseRoleIds(roleIdsRaw, roleId);

            log.info("Resolving assignee for task {}: rawType={}, resolvedType={}, anchor={}, anchorUser={}, roleIds={}, buId={}, activeBu={}",
                    taskId, assigneeTypeRaw, resolvedType, anchor, anchorUserId, resolvedRoleIds, businessUnitId, activeBusinessUnitId);

            TaskAssigneeResolver.ResolveResult result = taskAssigneeResolver.resolveWithRoleIds(
                    assigneeTypeRaw.trim(), resolvedRoleIds, businessUnitId, initiatorId, anchorUserId, activeBusinessUnitId);

            log.info("TaskAssignmentListener: resolve result for task {}: assignee={}, candidateUsers={}, error={}",
                    taskId, result != null ? result.getAssignee() : "null",
                    result != null ? result.getCandidateUsers() : "null",
                    result != null ? result.getErrorMessage() : "null");

            miWriter().updateCurrentItemProgress(this, processVariables, processDefinitionId, taskDefinitionKey,
                    task.getName());
            applyResolveResult(taskId, task, processInstanceId, processDefinitionId, taskDefinitionKey, result,
                    assigneeTypeRaw);
        } catch (Exception e) {
            log.error("Error handling task assignment for task {}: {}", taskId, e.getMessage(), e);
            // 任务照常创建（isFailOnException=false），但必须留痕：否则任务静默无主，
            // 直到用户报"My Request 显示 -"才被发现。
            String kind = e instanceof com.workflow.exception.AdminCenterUnavailableException ? "INFRA" : "ERROR";
            recordAssignmentFailure(taskId, processInstanceId, taskDefinitionKey, e.getMessage(), kind, e);
        }
    }

    private void applyResolveResult(String taskId, TaskEntity task, String processInstanceId,
                                    String processDefinitionId, String taskDefinitionKey,
                                    TaskAssigneeResolver.ResolveResult result, String assigneeTypeRaw) {
        if (result == null) {
            return;
        }
        if (result.getErrorMessage() != null) {
            log.error("Failed to resolve assignee for task {}: {}", taskId, result.getErrorMessage());
            if (tryRollbackPreviousHandlerFallback(taskId, task, processInstanceId, processDefinitionId,
                    taskDefinitionKey)) {
                return;
            }
            recordAssignmentFailure(taskId, processInstanceId, taskDefinitionKey,
                    result.getErrorMessage(), result.isInfraFailure() ? "INFRA" : "CONFIG", null);
            return;
        }
        if (result.getAssignee() != null && !result.getAssignee().isBlank()) {
            String resolvedAssignee = result.getAssignee().trim();
            taskService.setAssignee(taskId, resolvedAssignee);
            log.info("Task {} assigned to user: {}", taskId, resolvedAssignee);
            notifyNewTask(resolvedAssignee, taskId, task.getName(), processInstanceId);
            // BU_ROLE / INITIATOR / ... often have empty assignee at task creation; setAssignee happens here.
            // Must also write the MI extended task, otherwise multi-instance status only sees
            // ELEMENT_VARIABLE predecessor records and portal sub-table still shows COMPLETED/end.
            try {
                miWriter().ensureMultiInstanceExtendedTaskForPreassignedTask(this, task, taskId, processInstanceId,
                        processDefinitionId, taskDefinitionKey, resolvedAssignee);
            } catch (Exception e) {
                log.warn("ensureMultiInstanceExtendedTaskForPreassignedTask after resolve failed for {}: {}",
                        taskId, e.getMessage());
            }
            persistRegularExtendedTask(task, AssignmentType.USER, resolvedAssignee);
            return;
        }
        List<String> cands = result.getCandidateUsers();
        if (cands != null && !cands.isEmpty()) {
            for (String candidateUser : cands) {
                if (candidateUser != null && !candidateUser.isBlank()) {
                    taskService.addCandidateUser(taskId, candidateUser.trim());
                }
            }
            log.info("Task {} set candidate users: {}", taskId, cands);
            persistRegularExtendedTask(task, AssignmentType.CANDIDATE_USERS,
                    String.join(",", sanitizeCandidateIds(cands)));
            for (String candidateUser : cands) {
                if (candidateUser != null && !candidateUser.isBlank()) {
                    notifyCandidateTask(candidateUser.trim(), taskId, task.getName(), processInstanceId);
                }
            }
        } else {
            log.error("Task {}: no assignee and no candidates (assigneeType={})", taskId, assigneeTypeRaw);
            if (!tryRollbackPreviousHandlerFallback(taskId, task, processInstanceId, processDefinitionId,
                    taskDefinitionKey)) {
                recordAssignmentFailure(taskId, processInstanceId, taskDefinitionKey,
                        "No assignee and no candidates resolved (assigneeType=" + assigneeTypeRaw + ")",
                        "CONFIG", null);
            }
        }
    }

    /**
     * After rollback, BPMN assignee rules may fail (e.g. empty BU role). Assign the task to whoever
     * last completed this activity in the same process instance; if none, use process initiator.
     */
    private boolean tryRollbackPreviousHandlerFallback(String taskId, TaskEntity task,
                                                       String processInstanceId,
                                                       String processDefinitionId,
                                                       String taskDefinitionKey) {
        if (!isRollbackAssigneeFallbackActive(processInstanceId, taskDefinitionKey)) {
            return false;
        }
        clearRollbackAssigneeFallbackFlags(processInstanceId);

        Optional<String> prior = lastUserTaskAssigneeQuery.findLastCompletedAssigneeForActivity(
                processInstanceId, taskDefinitionKey);
        if (prior.isEmpty()) {
            prior = resolveInitiatorUserIdOptional(processInstanceId);
            if (prior.isPresent()) {
                log.info("Rollback assignee fallback for task {}: no prior assignee on activity {}; using initiator {}",
                        taskId, taskDefinitionKey, prior.get());
            }
        } else {
            log.info("Rollback assignee fallback for task {}: assigning previous handler {} on activity {}",
                    taskId, prior.get(), taskDefinitionKey);
        }
        if (prior.isEmpty()) {
            log.warn("Rollback assignee fallback for task {}: no previous handler or initiator found", taskId);
            return false;
        }

        String assignee = prior.get();
        taskService.setAssignee(taskId, assignee);
        notifyNewTask(assignee, taskId, task.getName(), processInstanceId);
        try {
            miWriter().ensureMultiInstanceExtendedTaskForPreassignedTask(this, task, taskId, processInstanceId,
                    processDefinitionId, taskDefinitionKey, assignee);
        } catch (Exception e) {
            log.warn("ensureMultiInstanceExtendedTaskForPreassignedTask after rollback fallback failed for {}: {}",
                    taskId, e.getMessage());
        }
        persistRegularExtendedTask(task, AssignmentType.USER, assignee);
        return true;
    }

    private boolean isRollbackAssigneeFallbackActive(String processInstanceId, String taskDefinitionKey) {
        if (processInstanceId == null || taskDefinitionKey == null) {
            return false;
        }
        Object active = runtimeService.getVariable(processInstanceId, RollbackAssigneeFallbackSupport.VAR_FALLBACK_ACTIVE);
        if (!Boolean.TRUE.equals(active)) {
            return false;
        }
        Object target = runtimeService.getVariable(processInstanceId,
                RollbackAssigneeFallbackSupport.VAR_TARGET_ACTIVITY_ID);
        return target != null && taskDefinitionKey.equals(String.valueOf(target).trim());
    }

    private void clearRollbackAssigneeFallbackFlags(String processInstanceId) {
        if (processInstanceId == null) {
            return;
        }
        runtimeService.removeVariable(processInstanceId, RollbackAssigneeFallbackSupport.VAR_FALLBACK_ACTIVE);
        runtimeService.removeVariable(processInstanceId, RollbackAssigneeFallbackSupport.VAR_TARGET_ACTIVITY_ID);
    }

    private Optional<String> resolveInitiatorUserIdOptional(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> vars = runtimeService.getVariables(processInstanceId);
            String fromVar = getStringVariable(vars, "initiator");
            if (fromVar != null && !fromVar.isBlank()) {
                return Optional.of(fromVar.trim());
            }
            ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (pi != null && pi.getStartUserId() != null && !pi.getStartUserId().isBlank()) {
                return Optional.of(pi.getStartUserId().trim());
            }
            HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (hpi != null && hpi.getStartUserId() != null && !hpi.getStartUserId().isBlank()) {
                return Optional.of(hpi.getStartUserId().trim());
            }
        } catch (Exception e) {
            log.debug("Could not resolve initiator for rollback fallback on {}: {}", processInstanceId, e.getMessage());
        }
        return Optional.empty();
    }

    private TaskAssigneeResolver.ResolveResult resolveManualAssign(String taskDefKey,
                                                                   String manualAssignVariable,
                                                                   String manualAssignBuVariable,
                                                                   String manualAssignRoleVariable,
                                                                   Map<String, Object> variables,
                                                                   String initiatorId) {
        String defKey = taskDefKey != null ? taskDefKey : "task";
        String userVar = manualAssignVariable != null && !manualAssignVariable.isBlank()
                ? manualAssignVariable.trim()
                : "manualAssignee_" + defKey;
        String uid = getStringVariable(variables, userVar);
        if (uid != null && !uid.isBlank()) {
            return TaskAssigneeResolver.ResolveResult.builder()
                    .assignee(uid.trim())
                    .assigneeType(AssigneeType.MANUAL_ASSIGN)
                    .requiresClaim(false)
                    .build();
        }
        String buVar = manualAssignBuVariable != null && !manualAssignBuVariable.isBlank()
                ? manualAssignBuVariable.trim()
                : "manualAssignBu_" + defKey;
        String roleVar = manualAssignRoleVariable != null && !manualAssignRoleVariable.isBlank()
                ? manualAssignRoleVariable.trim()
                : "manualAssignRole_" + defKey;
        // The task-assignment chain is code-based: these manual-assign process variables must carry a
        // BU *code* and role *code* (not ids), consistent with BPMN-fixed assignee config.
        String buId = getStringVariable(variables, buVar);
        String rid = getStringVariable(variables, roleVar);
        if (buId == null || buId.isBlank() || rid == null || rid.isBlank()) {
            return TaskAssigneeResolver.ResolveResult.builder()
                    .assigneeType(AssigneeType.MANUAL_ASSIGN)
                    .errorMessage("MANUAL_ASSIGN: set variable " + userVar + " or both " + buVar + " and " + roleVar)
                    .build();
        }
        return taskAssigneeResolver.resolve(AssigneeType.BU_ROLE, rid, buId, initiatorId, null);
    }

    private TaskAssigneeResolver.ResolveResult resolveAssigneeFromVariable(String varName,
                                                                            Map<String, Object> variables) {
        if (varName == null || varName.isBlank()) {
            return TaskAssigneeResolver.ResolveResult.builder()
                    .assigneeType(AssigneeType.ASSIGNEE_FROM_VARIABLE)
                    .errorMessage("ASSIGNEE_FROM_VARIABLE requires assigneeVariable or assigneeValue")
                    .build();
        }
        Object raw = variables != null ? variables.get(varName.trim()) : null;
        List<String> ids = new ArrayList<>();
        if (raw == null) {
            return taskAssigneeResolver.resolveFromUserIdList(AssigneeType.ASSIGNEE_FROM_VARIABLE, ids);
        }
        if (raw instanceof Collection<?> col) {
            for (Object o : col) {
                if (o == null) {
                    continue;
                }
                if (o instanceof Map<?, ?> m) {
                    String uid = extractUserIdFromRefMap(m);
                    if (uid != null) {
                        ids.addAll(splitUserList(uid));
                    } else {
                        log.warn("ASSIGNEE_FROM_VARIABLE: collection element map has no id/userId/user_id/value; skip (avoid Map#toString)");
                    }
                } else {
                    ids.addAll(splitUserList(o.toString()));
                }
            }
        } else if (raw instanceof Map<?, ?> map) {
            String uid = extractUserIdFromRefMap(map);
            if (uid != null) {
                ids.addAll(splitUserList(uid));
            } else {
                log.warn("ASSIGNEE_FROM_VARIABLE: map has no id/userId/user_id/value; skip (avoid Map#toString length overflow in Flowable identity link)");
            }
        } else {
            ids.addAll(splitUserList(raw.toString()));
        }
        ids = sanitizeFlowableUserIds(ids);
        return taskAssigneeResolver.resolveFromUserIdList(AssigneeType.ASSIGNEE_FROM_VARIABLE, ids);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private AssigneeAnchor computeAnchor(String assigneeTypeRaw, AssigneeType type, String extAnchor) {
        if (extAnchor != null && !extAnchor.isBlank()) {
            return AssigneeAnchor.fromCode(extAnchor);
        }
        if (type == AssigneeType.HIERARCHY_ROLE) {
            return inferHierarchyAnchorFromRaw(assigneeTypeRaw);
        }
        return AssigneeAnchor.INITIATOR;
    }

    private static AssigneeAnchor inferHierarchyAnchorFromRaw(String raw) {
        if (raw == null) {
            return AssigneeAnchor.INITIATOR;
        }
        String u = raw.trim().toUpperCase();
        if ("CURRENT_BU_ROLE".equals(u) || "CURRENT_PARENT_BU_ROLE".equals(u)) {
            return AssigneeAnchor.LAST_TASK_ASSIGNEE;
        }
        if ("DEPT_OTHERS".equals(u) || "DEPTOTHERS".equals(u)
                || "PARENT_DEPT".equals(u) || "PARENTDEPT".equals(u)) {
            return AssigneeAnchor.LAST_TASK_ASSIGNEE;
        }
        return AssigneeAnchor.INITIATOR;
    }

    private String resolveAnchorUserId(AssigneeAnchor anchor, String initiatorId, String processInstanceId) {
        if (anchor == AssigneeAnchor.INITIATOR) {
            return initiatorId;
        }
        return lastUserTaskAssigneeQuery.findLastCompletedUserTaskAssignee(processInstanceId)
                .orElse(initiatorId);
    }

    private static String normalizeLegacyAssigneeType(String assigneeType, String assigneeValue) {
        if (assigneeType == null) {
            return null;
        }
        String t = assigneeType.trim();
        if ("initiator".equalsIgnoreCase(t)) {
            return "INITIATOR";
        }
        if ("expression".equalsIgnoreCase(t) && assigneeValue != null) {
            if (isInitiatorExpression(assigneeValue.trim())) {
                return "INITIATOR";
            }
        }
        return assigneeType;
    }

    private static boolean isInitiatorExpression(String expr) {
        if (expr == null || expr.isEmpty()) {
            return false;
        }
        String e = expr.trim();
        if ("${initiator}".equals(e) || "${initiatorId}".equalsIgnoreCase(e)) {
            return true;
        }
        return e.matches("(?i)^\\$\\{\\s*initiator\\s*}$") || e.matches("(?i)^\\$\\{\\s*initiatorId\\s*}$");
    }

    private String getStringVariable(Map<String, Object> variables, String key) {
        if (variables == null) {
            return null;
        }
        Object value = variables.get(key);
        return value != null ? value.toString() : null;
    }

    @Override
    public boolean isFailOnException() {
        return false;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }

    void notifyNewTask(String userId, String taskId, String taskName, String processInstanceId) {
        if (userId == null || userId.isBlank() || notificationDispatchHelper == null) {
            return;
        }
        String label = taskName != null && !taskName.isBlank() ? taskName : taskId;
        notificationDispatchHelper.publishToUserAfterCommit(
                userId.trim(),
                "TASK",
                "New Task",
                String.format("You have a new task \"%s\". Process: %s", label, processInstanceId != null ? processInstanceId : "-"),
                "/tasks/" + taskId,
                "workflow-engine");
    }

    void notifyCandidateTask(String userId, String taskId, String taskName, String processInstanceId) {
        if (userId == null || userId.isBlank() || notificationDispatchHelper == null) {
            return;
        }
        String label = taskName != null && !taskName.isBlank() ? taskName : taskId;
        notificationDispatchHelper.publishToUserAfterCommit(
                userId.trim(),
                "TASK",
                "New Candidate Task",
                String.format("You have been added as a candidate for task \"%s\". You can claim it from your pending tasks. Process: %s",
                        label, processInstanceId != null ? processInstanceId : "-"),
                "/tasks/" + taskId,
                "workflow-engine");
    }
}
