package com.portal.properties;

import com.portal.client.WorkflowEngineClient;
import com.portal.component.ChangeHistoryComponent;
import com.portal.component.DelegatedTaskQueryComponent;
import com.portal.component.MiCollectionVariableBuilder;
import com.portal.component.MiOverlayComponent;
import com.portal.component.MiParticipantEnrichmentComponent;
import com.portal.component.SubTablePhysicalMetadataCache;
import com.portal.component.ProcessInstanceSyncComponent;
import com.portal.component.SubTableRowAssignmentComponent;
import com.portal.component.TaskApprovalCompletionComponent;
import com.portal.component.TaskFormComponent;
import com.portal.component.TaskHistoryComponent;
import com.portal.component.TaskPermissionEvaluator;
import com.portal.component.TaskProcessComponent;
import com.portal.component.TaskQueryComponent;
import com.portal.component.VirtualGroupAccessComponent;
import com.portal.component.WorkspaceTaskFilterComponent;
import com.portal.dto.TaskCompleteRequest;
import com.portal.dto.TaskInfo;
import com.portal.entity.DelegationRule;
import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
import com.portal.exception.PortalException;
import com.portal.repository.BusinessUnitRepository;
import com.portal.repository.DelegationAuditRepository;
import com.portal.repository.DelegationRuleRepository;
import com.portal.repository.ProcessHistoryRepository;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.PortalWorkspaceAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务处理权限属性测试
 * 验证只有有权限的用户才能处理任务
 * 
 * 注意：TaskQueryComponent 现在通过 WorkflowEngineClient 从 Flowable 获取任务
 */
class TaskProcessProperties {

    @Mock
    private DelegationRuleRepository delegationRuleRepository;

    @Mock
    private DelegationAuditRepository delegationAuditRepository;

    @Mock
    private ProcessInstanceRepository processInstanceRepository;
    
    @Mock
    private ProcessHistoryRepository processHistoryRepository;
    
    @Mock
    private WorkflowEngineClient workflowEngineClient;
    
    @Mock
    private com.portal.service.TaskActionService taskActionService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private VirtualGroupAccessComponent virtualGroupAccessComponent;

    @Mock
    private PortalWorkspaceAuthService portalWorkspaceAuthService;

    @Mock
    private BusinessUnitRepository businessUnitRepository;

    @Mock
    private ChangeHistoryComponent changeHistoryComponent;

    @Mock
    private TaskFormComponent taskFormComponent;

    private TaskQueryComponent taskQueryComponent;
    private TaskProcessComponent taskProcessComponent;
    private Random random;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        com.portal.component.RequestIdEnricher requestIdEnricher =
            org.mockito.Mockito.mock(com.portal.component.RequestIdEnricher.class);
        com.portal.component.EngineSubTableHydrator engineSubTableHydrator =
            new com.portal.component.EngineSubTableHydrator(workflowEngineClient);
        taskQueryComponent = new TaskQueryComponent(
            processInstanceRepository,
            workflowEngineClient,
            engineSubTableHydrator,
            taskActionService,
            new DelegatedTaskQueryComponent(workflowEngineClient, delegationRuleRepository),
            new WorkspaceTaskFilterComponent(
                workflowEngineClient, virtualGroupAccessComponent, portalWorkspaceAuthService, businessUnitRepository),
            new MiParticipantEnrichmentComponent(jdbcTemplate),
            new TaskHistoryComponent(workflowEngineClient, processHistoryRepository),
            requestIdEnricher
        );
        TaskPermissionEvaluator taskPermissionEvaluator =
            new TaskPermissionEvaluator(delegationRuleRepository, workflowEngineClient);
        ProcessInstanceSyncComponent processInstanceSyncComponent =
            new ProcessInstanceSyncComponent(workflowEngineClient, processInstanceRepository);
        MiCollectionVariableBuilder miCollectionVariableBuilder =
            new MiCollectionVariableBuilder(workflowEngineClient, jdbcTemplate);
        TaskApprovalCompletionComponent taskApprovalCompletionComponent = new TaskApprovalCompletionComponent(
            workflowEngineClient,
            engineSubTableHydrator,
            processInstanceRepository,
            changeHistoryComponent,
            taskFormComponent,
            miCollectionVariableBuilder,
            processInstanceSyncComponent
        );
        SubTableRowAssignmentComponent subTableRowAssignmentComponent = new SubTableRowAssignmentComponent(
            workflowEngineClient,
            processInstanceRepository,
            jdbcTemplate,
            taskPermissionEvaluator
        );
        MiOverlayComponent miOverlayComponent = new MiOverlayComponent(
            workflowEngineClient,
            new SubTablePhysicalMetadataCache(jdbcTemplate)
        );
        taskProcessComponent = new TaskProcessComponent(
            taskQueryComponent,
            delegationAuditRepository,
            workflowEngineClient,
            taskPermissionEvaluator,
            subTableRowAssignmentComponent,
            taskApprovalCompletionComponent,
            processInstanceSyncComponent,
            miOverlayComponent
        );
        random = new Random();

        // 默认返回空委托列表
        when(delegationRuleRepository.findActiveDelegationsForDelegate(any(), any()))
                .thenReturn(Collections.emptyList());

        when(portalWorkspaceAuthService.listWorkspaceContexts(any())).thenReturn(Collections.emptyList());
        
        // Mock WorkflowEngineClient 为可用状态
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        
        // Mock 转办任务成功
        when(workflowEngineClient.transferTask(any(), any(), any(), any()))
                .thenReturn(Optional.of(Map.of("success", true)));
        
        // Mock 认领任务成功
        when(workflowEngineClient.claimTask(any(), any()))
                .thenReturn(Optional.of(Map.of("success", true)));
        
        // Mock 取消认领任务成功
        when(workflowEngineClient.unclaimTask(any(), any()))
                .thenReturn(Optional.of(Map.of("success", true)));
        
        // Mock 委托任务成功
        when(workflowEngineClient.delegateTask(any(), any(), any(), any()))
                .thenReturn(Optional.of(Map.of("success", true)));
        
        // Mock 完成任务成功
        when(workflowEngineClient.completeTask(any(), any(), any(), any()))
                .thenReturn(Optional.of(Map.of("success", true)));
        
        // Mock 用户权限查询 - 返回包含虚拟组的权限信息
        // 使用 Answer 动态生成权限数据，使虚拟组与用户ID关联
        when(workflowEngineClient.getUserTaskPermissions(anyString()))
                .thenAnswer(invocation -> {
                    String userId = invocation.getArgument(0);
                    Map<String, Object> permissions = new HashMap<>();
                    // 虚拟组ID格式: group_<userId>
                    permissions.put("virtualGroupIds", List.of("group_" + userId));
                    return Optional.of(permissions);
                });
        
        // Mock 任务权限检查 - 默认返回 true
        when(workflowEngineClient.checkTaskPermission(anyString(), anyString()))
                .thenReturn(Optional.of(true));
    }

    /**
     * 属性1: 直接分配的任务只有被分配人可以处理
     */
    @RepeatedTest(20)
    void directAssignedTaskCanOnlyBeProcessedByAssignee() {
        String assignee = "user_" + random.nextInt(1000);
        String otherUser = "other_" + random.nextInt(1000);
        String taskId = "task_" + random.nextInt(1000);

        TaskInfo task = createTask(taskId, "USER", assignee);

        // 被分配人可以处理
        assertTrue(taskProcessComponent.canProcessTask(task, assignee));

        // 其他用户不能处理
        assertFalse(taskProcessComponent.canProcessTask(task, otherUser));
    }

    /**
     * 属性2: 虚拟组任务可以被组成员认领和处理
     */
    @RepeatedTest(20)
    void virtualGroupTaskCanBeClaimedByGroupMember() {
        String userId = "user_" + random.nextInt(1000);
        String groupId = "group_" + userId; // 模拟用户所属的组
        String taskId = "task_" + random.nextInt(1000);

        TaskInfo task = createTask(taskId, "VIRTUAL_GROUP", groupId);

        // 组成员可以认领
        assertTrue(taskProcessComponent.canClaimTask(task, userId));

        // 组成员可以处理
        assertTrue(taskProcessComponent.canProcessTask(task, userId));
    }

    /**
     * 属性3: 委托任务可以被委托人处理
     */
    @RepeatedTest(20)
    void delegatedTaskCanBeProcessedByDelegate() {
        String delegatorId = "delegator_" + random.nextInt(1000);
        String delegateId = "delegate_" + random.nextInt(1000);
        String taskId = "task_" + random.nextInt(1000);

        // 设置委托规则
        DelegationRule rule = DelegationRule.builder()
                .delegatorId(delegatorId)
                .delegateId(delegateId)
                .delegationType(DelegationType.ALL)
                .status(DelegationStatus.ACTIVE)
                .build();
        when(delegationRuleRepository.findActiveDelegationsForDelegate(eq(delegateId), any()))
                .thenReturn(List.of(rule));

        TaskInfo task = createTask(taskId, "USER", delegatorId);

        // 委托人可以处理
        assertTrue(taskProcessComponent.canProcessTask(task, delegateId));
    }

    /**
     * 属性4: 认领任务后从 Flowable 获取更新后的任务状态
     */
    @RepeatedTest(20)
    void claimingTaskReturnsUpdatedTaskFromFlowable() {
        String userId = "user_" + random.nextInt(1000);
        String groupId = "group_" + userId;
        String taskId = "task_" + random.nextInt(1000);

        // Mock 认领后从 Flowable 获取的任务
        Map<String, Object> claimedTaskMap = createTaskMap(taskId, "USER", userId);
        mockFlowableTaskResponse(taskId, claimedTaskMap);

        TaskInfo claimedTask = taskProcessComponent.claimTask(taskId, userId);

        assertEquals(userId, claimedTask.getAssignee());
    }

    /**
     * 属性5: 直接分配的任务不能被认领（Flowable 返回失败）
     */
    @RepeatedTest(20)
    void directAssignedTaskCannotBeClaimed() {
        String assignee = "user_" + random.nextInt(1000);
        String taskId = "task_" + random.nextInt(1000);

        // Mock Flowable 返回认领失败（直接分配的任务不能认领）
        when(workflowEngineClient.claimTask(eq(taskId), eq(assignee)))
                .thenReturn(Optional.of(Map.of("success", false, "message", "直接分配的任务不能被认领")));

        assertThrows(PortalException.class, () -> 
            taskProcessComponent.claimTask(taskId, assignee));
    }

    /**
     * 属性6: 无权限用户不能认领任务（Flowable 返回失败）
     */
    @RepeatedTest(20)
    void unauthorizedUserCannotClaimTask() {
        String groupId = "group_specific";
        String unauthorizedUser = "unauthorized_" + random.nextInt(1000);
        String taskId = "task_" + random.nextInt(1000);

        // Mock Flowable 返回认领失败（无权限）
        when(workflowEngineClient.claimTask(eq(taskId), eq(unauthorizedUser)))
                .thenReturn(Optional.of(Map.of("success", false, "message", "用户没有认领此任务的权限")));

        assertThrows(PortalException.class, () -> 
            taskProcessComponent.claimTask(taskId, unauthorizedUser));
    }

    /**
     * 属性7: 委托任务成功调用 Flowable
     */
    @RepeatedTest(20)
    void delegatingTaskCallsFlowable() {
        String delegatorId = "delegator_" + random.nextInt(1000);
        String delegateId = "delegate_" + random.nextInt(1000);
        String taskId = "task_" + random.nextInt(1000);

        // Mock 任务存在
        Map<String, Object> taskMap = createTaskMap(taskId, "USER", delegatorId);
        mockFlowableTaskResponse(taskId, taskMap);

        // 委托任务应该成功
        assertDoesNotThrow(() -> 
            taskProcessComponent.delegateTask(taskId, delegatorId, delegateId, "出差委托"));
    }

    /**
     * 属性8: 转办任务成功调用 Flowable
     */
    @RepeatedTest(20)
    void transferringTaskCallsFlowable() {
        String fromUserId = "from_" + random.nextInt(1000);
        String toUserId = "to_" + random.nextInt(1000);
        String taskId = "task_" + random.nextInt(1000);

        // Mock 任务存在
        Map<String, Object> taskMap = createTaskMap(taskId, "USER", fromUserId);
        mockFlowableTaskResponse(taskId, taskMap);

        // 转办任务应该成功
        assertDoesNotThrow(() -> 
            taskProcessComponent.transferTask(taskId, fromUserId, toUserId, "工作交接"));
    }

    /**
     * 属性9: 无权限用户不能完成任务
     */
    @RepeatedTest(20)
    void unauthorizedUserCannotCompleteTask() {
        String assignee = "user_" + random.nextInt(1000);
        String unauthorizedUser = "unauthorized_" + random.nextInt(1000);
        String taskId = "task_" + random.nextInt(1000);

        // Mock 任务存在
        Map<String, Object> taskMap = createTaskMap(taskId, "USER", assignee);
        mockFlowableTaskResponse(taskId, taskMap);

        TaskCompleteRequest request = TaskCompleteRequest.builder()
                .taskId(taskId)
                .action("APPROVE")
                .comment("同意")
                .build();

        assertThrows(PortalException.class, () -> 
            taskProcessComponent.completeTask(request, unauthorizedUser));
    }

    /**
     * 属性10: 不存在的任务操作应该抛出异常
     */
    @Test
    void operationOnNonexistentTaskShouldThrowException() {
        String taskId = "nonexistent_task";
        String userId = "user_1";

        // Mock 任务不存在
        when(workflowEngineClient.getTaskById(taskId))
                .thenReturn(Optional.empty());

        assertThrows(PortalException.class, () -> 
            taskProcessComponent.claimTask(taskId, userId));

        TaskCompleteRequest request = TaskCompleteRequest.builder()
                .taskId(taskId)
                .action("APPROVE")
                .build();
        assertThrows(PortalException.class, () -> 
            taskProcessComponent.completeTask(request, userId));
    }

    /**
     * 属性11: 委托和转办需要目标用户
     */
    @RepeatedTest(20)
    void delegateAndTransferRequireTargetUser() {
        String userId = "user_" + random.nextInt(1000);
        String taskId = "task_" + random.nextInt(1000);

        // Mock 任务存在
        Map<String, Object> taskMap = createTaskMap(taskId, "USER", userId);
        mockFlowableTaskResponse(taskId, taskMap);

        // 委托没有目标用户
        TaskCompleteRequest delegateRequest = TaskCompleteRequest.builder()
                .taskId(taskId)
                .action("DELEGATE")
                .build();
        assertThrows(PortalException.class, () -> 
            taskProcessComponent.completeTask(delegateRequest, userId));

        // 转办没有目标用户
        TaskCompleteRequest transferRequest = TaskCompleteRequest.builder()
                .taskId(taskId)
                .action("TRANSFER")
                .build();
        assertThrows(PortalException.class, () -> 
            taskProcessComponent.completeTask(transferRequest, userId));
    }
    
    /**
     * 属性12: Flowable 引擎不可用时应该抛出异常
     */
    @Test
    void shouldThrowExceptionWhenFlowableUnavailable() {
        when(workflowEngineClient.isAvailable()).thenReturn(false);
        
        String taskId = "task_1";
        String userId = "user_1";

        IllegalStateException exception = assertThrows(IllegalStateException.class, 
            () -> taskProcessComponent.claimTask(taskId, userId));
        
        assertTrue(exception.getMessage().contains("Flowable engine unavailable")
                || exception.getMessage().contains("Flowable 引擎不可用"));
    }

    @Test
    void completingTaskBeforeMultiInstanceSubProcessInjectsCollectionVariable() {
        String taskId = "task_mi";
        String userId = "user_1";
        String processDefinitionKey = "Process_1";
        String taskDefinitionKey = "Activity_assignment";

        Map<String, Object> taskMap = createTaskMap(taskId, "USER", userId);
        taskMap.put("processDefinitionKey", processDefinitionKey);
        taskMap.put("taskDefinitionKey", taskDefinitionKey);
        taskMap.put("variables", Map.of(
                "__subTables__", Map.of("66", List.of(
                        Map.of("id", 101L, "assignee", "assignee_1", "name", "row 1"),
                        Map.of("rowId", 102L, "assignee", "assignee_2", "name", "row 2")
                ))
        ));
        mockFlowableTaskResponse(taskId, taskMap);
        when(workflowEngineClient.getBpmnXml(processDefinitionKey)).thenReturn(Optional.of("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:flowable="http://flowable.org/bpmn"
                    xmlns:custom="http://workflow.platform/schema/custom">
                  <bpmn:process id="Process_1" isExecutable="true">
                    <bpmn:userTask id="Activity_assignment" name="assignment">
                      <bpmn:outgoing>Flow_to_mi</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:sequenceFlow id="Flow_to_mi" sourceRef="Activity_assignment" targetRef="Activity_multi" />
                    <bpmn:subProcess id="Activity_multi" name="multi">
                      <bpmn:multiInstanceLoopCharacteristics flowable:collection="multiInstance_subtable_collection"
                          flowable:elementVariable="currentItem" />
                      <bpmn:userTask id="Activity_inner" name="sub form">
                        <bpmn:extensionElements>
                          <custom:properties>
                            <custom:property name="assigneeField" value="assignee" />
                          </custom:properties>
                        </bpmn:extensionElements>
                      </bpmn:userTask>
                    </bpmn:subProcess>
                  </bpmn:process>
                </bpmn:definitions>
                """));

        TaskCompleteRequest request = TaskCompleteRequest.builder()
                .taskId(taskId)
                .action("APPROVE")
                .variables(Map.of(
                        "__subTables__", Map.of("66", List.of(
                                Map.of("id", 101L, "assignee", "assignee_1", "name", "row 1"),
                                Map.of("rowId", 102L, "assignee", "assignee_2", "name", "row 2")
                        ))
                ))
                .build();

        taskProcessComponent.completeTask(request, userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workflowEngineClient).completeTask(eq(taskId), eq(userId), eq("APPROVE"), variablesCaptor.capture());
        Object collectionObj = variablesCaptor.getValue().get("multiInstance_subtable_collection");
        assertInstanceOf(List.class, collectionObj);

        List<?> collection = (List<?>) collectionObj;
        assertEquals(2, collection.size());
        assertEquals(101L, ((Map<?, ?>) collection.get(0)).get("rowId"));
        assertEquals("assignee_1", ((Map<?, ?>) collection.get(0)).get("assignee"));
        assertEquals(102L, ((Map<?, ?>) collection.get(1)).get("rowId"));
        assertEquals("assignee_2", ((Map<?, ?>) collection.get(1)).get("assignee"));
    }

    /**
     * Mock Flowable 任务响应
     */
    private void mockFlowableTaskResponse(String taskId, Map<String, Object> taskMap) {
        Map<String, Object> response = new HashMap<>();
        response.put("data", taskMap);
        
        when(workflowEngineClient.getTaskById(taskId))
                .thenReturn(Optional.of(response));
    }

    /**
     * 创建测试任务 Map
     */
    private Map<String, Object> createTaskMap(String taskId, String assignmentType, String assignee) {
        Map<String, Object> task = new HashMap<>();
        task.put("taskId", taskId);
        task.put("taskName", "测试任务 " + taskId);
        task.put("taskDescription", "任务描述");
        task.put("processInstanceId", "PI_" + taskId);
        task.put("processDefinitionId", "test_process");
        task.put("assignmentType", assignmentType);
        task.put("currentAssignee", assignee);
        task.put("priority", "NORMAL");
        task.put("status", "PENDING");
        task.put("createdTime", LocalDateTime.now());
        task.put("isOverdue", false);
        return task;
    }

    /**
     * 创建测试任务
     */
    private TaskInfo createTask(String taskId, String assignmentType, String assignee) {
        return TaskInfo.builder()
                .taskId(taskId)
                .taskName("测试任务 " + taskId)
                .description("任务描述")
                .processInstanceId("PI_" + taskId)
                .processDefinitionKey("test_process")
                .processDefinitionName("测试流程")
                .assignmentType(assignmentType)
                .assignee(assignee)
                .priority("NORMAL")
                .status("PENDING")
                .createTime(LocalDateTime.now())
                .isOverdue(false)
                .build();
    }
}
