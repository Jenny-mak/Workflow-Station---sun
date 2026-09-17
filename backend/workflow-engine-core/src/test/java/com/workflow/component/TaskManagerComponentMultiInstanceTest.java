package com.workflow.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.dto.response.TaskAssignmentResult;
import com.workflow.entity.ExtendedTaskInfo;
import com.workflow.enums.AssignmentType;
import com.workflow.exception.WorkflowBusinessException;
import com.workflow.exception.WorkflowValidationException;
import com.workflow.repository.ExtendedTaskInfoRepository;
import com.workflow.service.UserPermissionService;
import org.flowable.bpmn.model.*;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 多实例 completeTask 功能单元测试
 *
 * 说明：原本针对 TaskManagerComponent 的门面方法。TaskManagerComponent 已拆分为
 * 门面 + 协作类，completeTask 的实际逻辑现位于 {@link TaskCompletionService}，
 * 其中多实例检测/注入/回写逻辑由 {@link TaskMultiInstanceService} 执行
 * （TaskCompletionService 以 @Lazy 调用）。因此本测试直接对 TaskCompletionService
 * 注入被测 mock，并注入一个【真实】TaskMultiInstanceService（同样喂同一批 mock），
 * 保证 MI 行为真实执行、原断言（subTableDataInjector / multiInstanceDataResolver 交互）依旧有效。
 *
 * 测试场景：
 * 1. 前置任务完成时检测多实例子流程并注入数据
 * 2. 子任务完成时检测多实例标记并回写数据
 * 3. 边界条件：子表数据为空、处理人缺失、乐观锁冲突
 */
@ExtendWith(MockitoExtension.class)
class TaskManagerComponentMultiInstanceTest {

    @Mock
    private TaskService taskService;

    @Mock
    private RuntimeService runtimeService;

    @Mock
    private RepositoryService repositoryService;

    @Mock
    private ExtendedTaskInfoRepository extendedTaskInfoRepository;

    @Mock
    private SubTableDataInjector subTableDataInjector;

    @Mock
    private MultiInstanceDataResolver multiInstanceDataResolver;

    @Mock
    private UserPermissionService userPermissionService;

    @Mock
    private BpmnActionParser bpmnActionParser;

    // completeTask 主体现在落在 TaskCompletionService；@InjectMocks 注入其同名字段
    // （taskService/runtimeService/repositoryService/extendedTaskInfoRepository/userPermissionService）。
    @InjectMocks
    private TaskCompletionService taskCompletionService;

    // MI 检测/注入/回写逻辑真正执行处；用真实实例（喂同一批 mock）保证断言真实校验 MI 行为。
    private TaskMultiInstanceService taskMultiInstanceService;

    private static final String TASK_ID = "task-001";
    private static final String USER_ID = "user-001";
    private static final String PROCESS_INSTANCE_ID = "process-001";
    private static final String PROCESS_DEFINITION_ID = "process-def-001";
    private static final String TASK_DEFINITION_KEY = "approveTask";
    
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @BeforeEach
    void setUp() {
        // 构建真实的 TaskMultiInstanceService 并注入同一批 mock，使 MI 检测/注入/回写真实执行。
        taskMultiInstanceService = new TaskMultiInstanceService();
        ReflectionTestUtils.setField(taskMultiInstanceService, "runtimeService", runtimeService);
        ReflectionTestUtils.setField(taskMultiInstanceService, "repositoryService", repositoryService);
        ReflectionTestUtils.setField(taskMultiInstanceService, "extendedTaskInfoRepository", extendedTaskInfoRepository);
        ReflectionTestUtils.setField(taskMultiInstanceService, "bpmnActionParser", bpmnActionParser);
        ReflectionTestUtils.setField(taskMultiInstanceService, "subTableDataInjector", subTableDataInjector);
        ReflectionTestUtils.setField(taskMultiInstanceService, "multiInstanceDataResolver", multiInstanceDataResolver);

        // TaskCompletionService 以 @Lazy 调用 MI 协作类；注入真实实例。
        ReflectionTestUtils.setField(taskCompletionService, "taskMultiInstanceService", taskMultiInstanceService);

        // completeTask 经 taskActionService.engineActorMatchesPortalUser 做完成授权（assignee 等于 userId 时短路通过）。
        // 用真实 TaskActionService 保证授权判断真实执行（equals 短路，不触达其它依赖）。
        TaskActionService taskActionService = new TaskActionService();
        ReflectionTestUtils.setField(taskActionService, "taskService", taskService);
        ReflectionTestUtils.setField(taskActionService, "userPermissionService", userPermissionService);
        ReflectionTestUtils.setField(taskActionService, "taskClaimSupport", new TaskClaimSupport());
        ReflectionTestUtils.setField(taskCompletionService, "taskActionService", taskActionService);

        // 默认 mock 设置
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId(anyString())).thenReturn(taskQuery);

        lenient().when(runtimeService.getVariable(eq(PROCESS_INSTANCE_ID), anyString())).thenReturn(null);
        lenient().when(subTableDataInjector.physicalTableExistsInCurrentSchema(anyString())).thenReturn(true);
    }
    
    /**
     * 测试场景 1：前置任务完成时，下一节点为多实例子流程，应调用 SubTableDataInjector
     */
    @Test
    void testCompleteTask_NextNodeIsMultiInstanceSubProcess_ShouldInjectData() throws Exception {
        // Given: 设置任务和流程实例
        Task task = createMockTask(TASK_ID, PROCESS_INSTANCE_ID, PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY);
        TaskQuery taskQuery = taskService.createTaskQuery();
        when(taskQuery.singleResult()).thenReturn(task);
        
        // 设置扩展任务信息（非多实例子任务）
        ExtendedTaskInfo extendedTaskInfo = createExtendedTaskInfo(TASK_ID, false);
        when(extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(TASK_ID))
            .thenReturn(Optional.of(extendedTaskInfo));
        
        // 设置 BPMN 模型：下一节点为多实例子流程
        BpmnModel bpmnModel = createBpmnModelWithMultiInstanceSubProcess();
        when(repositoryService.getBpmnModel(PROCESS_DEFINITION_ID)).thenReturn(bpmnModel);
        
        // 设置流程变量
        Map<String, Object> processVariables = new HashMap<>();
        processVariables.put("mainRecordId", 100L);
        when(runtimeService.getVariables(PROCESS_INSTANCE_ID)).thenReturn(processVariables);
        
        // When: 完成任务
        Map<String, Object> variables = new HashMap<>();
        variables.put("approved", true);
        
        TaskAssignmentResult result = taskCompletionService.completeTask(TASK_ID, USER_ID, variables);
        
        // Then: 验证 SubTableDataInjector 被调用
        verify(subTableDataInjector, times(1)).injectSubTableData(
            eq(PROCESS_INSTANCE_ID),
            eq("fu_participants"),
            eq("main_record_id"),
            eq(100L),
            eq("assignee_user_id"),
            eq("multiInstance_fu_participants_collection")
        );
        
        // 验证任务完成
        verify(taskService, times(1)).complete(eq(TASK_ID), eq(variables));
        assertThat(result.isSuccess()).isTrue();
    }
    
    /**
     * 测试场景 2：子任务完成时，检测到多实例标记，应调用 MultiInstanceDataResolver 回写数据
     */
    @Test
    void testCompleteTask_MultiInstanceSubTask_ShouldWriteBackData() throws Exception {
        // Given: 设置多实例子任务
        Task task = createMockTask(TASK_ID, PROCESS_INSTANCE_ID, PROCESS_DEFINITION_ID, "MI_UserTask_45");
        TaskQuery taskQuery = taskService.createTaskQuery();
        when(taskQuery.singleResult()).thenReturn(task);
        
        // 设置扩展任务信息（多实例子任务）
        ExtendedTaskInfo extendedTaskInfo = createExtendedTaskInfo(TASK_ID, true);
        when(extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(TASK_ID))
            .thenReturn(Optional.of(extendedTaskInfo));
        
        // 设置 BPMN 模型（无下一个多实例子流程）
        BpmnModel bpmnModel = createBpmnModelWithoutMultiInstance();
        when(repositoryService.getBpmnModel(PROCESS_DEFINITION_ID)).thenReturn(bpmnModel);
        
        // When: 完成子任务，提供表单数据和 rowVersion
        Map<String, Object> formData = new HashMap<>();
        formData.put("name", "张三");
        formData.put("phone", "138xxxx1234");
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("formData", formData);
        variables.put("rowVersion", 1L);
        
        TaskAssignmentResult result = taskCompletionService.completeTask(TASK_ID, USER_ID, variables);
        
        // Then: 验证 MultiInstanceDataResolver 被调用
        verify(multiInstanceDataResolver, times(1)).writeBackSubTableRow(
            eq(TASK_ID),
            eq(formData),
            eq(1L)
        );
        
        // 验证任务完成
        verify(taskService, times(1)).complete(eq(TASK_ID), eq(variables));
        assertThat(result.isSuccess()).isTrue();
    }
    
    /**
     * 测试场景 3：子任务完成时，未提供 formData，应记录警告但不影响任务完成
     */
    @Test
    void testCompleteTask_MultiInstanceSubTask_NoFormData_ShouldLogWarning() throws Exception {
        // Given: 设置多实例子任务
        Task task = createMockTask(TASK_ID, PROCESS_INSTANCE_ID, PROCESS_DEFINITION_ID, "MI_UserTask_45");
        TaskQuery taskQuery = taskService.createTaskQuery();
        when(taskQuery.singleResult()).thenReturn(task);
        
        // 设置扩展任务信息（多实例子任务）
        ExtendedTaskInfo extendedTaskInfo = createExtendedTaskInfo(TASK_ID, true);
        when(extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(TASK_ID))
            .thenReturn(Optional.of(extendedTaskInfo));
        
        // 设置 BPMN 模型
        BpmnModel bpmnModel = createBpmnModelWithoutMultiInstance();
        when(repositoryService.getBpmnModel(PROCESS_DEFINITION_ID)).thenReturn(bpmnModel);
        
        // When: 完成子任务，但未提供 formData
        Map<String, Object> variables = new HashMap<>();
        
        TaskAssignmentResult result = taskCompletionService.completeTask(TASK_ID, USER_ID, variables);
        
        // Then: 验证 MultiInstanceDataResolver 未被调用
        verify(multiInstanceDataResolver, never()).writeBackSubTableRow(any(), any(), any());
        
        // 验证任务仍然完成
        verify(taskService, times(1)).complete(eq(TASK_ID));
        assertThat(result.isSuccess()).isTrue();
    }
    
    /**
     * 测试场景 4：子任务完成时，乐观锁冲突，应抛出异常
     */
    @Test
    void testCompleteTask_MultiInstanceSubTask_OptimisticLockConflict_ShouldThrowException() throws Exception {
        // Given: 设置多实例子任务
        Task task = createMockTask(TASK_ID, PROCESS_INSTANCE_ID, PROCESS_DEFINITION_ID, "MI_UserTask_45");
        TaskQuery taskQuery = taskService.createTaskQuery();
        when(taskQuery.singleResult()).thenReturn(task);
        
        // 设置扩展任务信息（多实例子任务）
        ExtendedTaskInfo extendedTaskInfo = createExtendedTaskInfo(TASK_ID, true);
        when(extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(TASK_ID))
            .thenReturn(Optional.of(extendedTaskInfo));
        
        // 设置 BPMN 模型（使用 lenient 避免 unnecessary stubbing 错误）
        BpmnModel bpmnModel = createBpmnModelWithoutMultiInstance();
        lenient().when(repositoryService.getBpmnModel(PROCESS_DEFINITION_ID)).thenReturn(bpmnModel);
        
        // 模拟乐观锁冲突
        Map<String, Object> formData = new HashMap<>();
        formData.put("name", "张三");
        
        doThrow(new MultiInstanceDataResolver.OptimisticLockException("数据已被修改，请刷新后重试"))
            .when(multiInstanceDataResolver).writeBackSubTableRow(eq(TASK_ID), eq(formData), eq(1L));
        
        // When & Then: 完成子任务应抛出乐观锁异常
        Map<String, Object> variables = new HashMap<>();
        variables.put("formData", formData);
        variables.put("rowVersion", 1L);
        
        assertThatThrownBy(() -> taskCompletionService.completeTask(TASK_ID, USER_ID, variables))
            .isInstanceOf(MultiInstanceDataResolver.OptimisticLockException.class)
            .hasMessageContaining("数据已被修改");
        
        // 验证任务未完成
        // Flowable 7.2.0 新增 complete(String taskId, String userId) 重载，无类型的 any() 会与
        // complete(String, Map) 产生歧义；用有类型匹配器锁定原本要验证的那个重载。
        verify(taskService, never()).complete(anyString(), anyMap());
    }
    
    /**
     * 测试场景 5：前置任务完成时，下一节点不是多实例子流程，不应调用 SubTableDataInjector
     */
    @Test
    void testCompleteTask_NextNodeIsNotMultiInstance_ShouldNotInjectData() throws Exception {
        // Given: 设置任务和流程实例
        Task task = createMockTask(TASK_ID, PROCESS_INSTANCE_ID, PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY);
        TaskQuery taskQuery = taskService.createTaskQuery();
        when(taskQuery.singleResult()).thenReturn(task);
        
        // 设置扩展任务信息（非多实例子任务）
        ExtendedTaskInfo extendedTaskInfo = createExtendedTaskInfo(TASK_ID, false);
        when(extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(TASK_ID))
            .thenReturn(Optional.of(extendedTaskInfo));
        
        // 设置 BPMN 模型：下一节点为普通 UserTask
        BpmnModel bpmnModel = createBpmnModelWithoutMultiInstance();
        when(repositoryService.getBpmnModel(PROCESS_DEFINITION_ID)).thenReturn(bpmnModel);
        
        // When: 完成任务
        Map<String, Object> variables = new HashMap<>();
        variables.put("approved", true);
        
        TaskAssignmentResult result = taskCompletionService.completeTask(TASK_ID, USER_ID, variables);
        
        // Then: 验证 SubTableDataInjector 未被调用
        verify(subTableDataInjector, never()).injectSubTableData(any(), any(), any(), any(), any(), any());
        
        // 验证任务完成
        verify(taskService, times(1)).complete(eq(TASK_ID), eq(variables));
        assertThat(result.isSuccess()).isTrue();
    }
    
    // ==================== 辅助方法 ====================
    
    private Task createMockTask(String taskId, String processInstanceId, 
                               String processDefinitionId, String taskDefinitionKey) {
        Task task = mock(Task.class, withSettings().lenient());
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        when(task.getProcessDefinitionId()).thenReturn(processDefinitionId);
        when(task.getTaskDefinitionKey()).thenReturn(taskDefinitionKey);
        // completeTask 会先跑孤儿发起人修复：无 assignee 时会查 ProcessInstance；未 stub 会 NPE
        when(task.getAssignee()).thenReturn(USER_ID);
        return task;
    }
    
    private ExtendedTaskInfo createExtendedTaskInfo(String taskId, boolean isMultiInstance) throws Exception {
        Map<String, Object> extendedProperties = new HashMap<>();
        if (isMultiInstance) {
            extendedProperties.put("multiInstance", true);
            extendedProperties.put("subTableRowId", 101L);
            extendedProperties.put("subTableName", "fu_participants");
        }
        
        String extendedPropertiesJson = objectMapper.writeValueAsString(extendedProperties);
        
        return ExtendedTaskInfo.builder()
            .id(1L)
            .taskId(taskId)
            .processInstanceId(PROCESS_INSTANCE_ID)
            .processDefinitionId(PROCESS_DEFINITION_ID)
            .taskDefinitionKey(TASK_DEFINITION_KEY)
            .taskName("测试任务")
            .assignmentType(AssignmentType.USER)
            .assignmentTarget(USER_ID)
            .status("ASSIGNED")
            .createdTime(LocalDateTime.now())
            .extendedProperties(extendedPropertiesJson)
            .build();
    }
    
    /**
     * 创建包含多实例子流程的 BPMN 模型
     */
    private BpmnModel createBpmnModelWithMultiInstanceSubProcess() {
        BpmnModel bpmnModel = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("Process_1");
        
        // 创建前置任务
        UserTask approveTask = new UserTask();
        approveTask.setId(TASK_DEFINITION_KEY);
        approveTask.setName("批准收集");
        
        // 创建多实例子流程
        SubProcess subProcess = new SubProcess();
        subProcess.setId("MultiInstance_SubTable_45");
        subProcess.setName("多实例-参与人信息收集");
        
        // 设置多实例特性
        MultiInstanceLoopCharacteristics loopCharacteristics = new MultiInstanceLoopCharacteristics();
        loopCharacteristics.setSequential(false);

        // KK / Flowable 导出常用 flowable:collection 属性 → BpmnModel.inputDataItem（非 extensionElements）
        loopCharacteristics.setInputDataItem("multiInstance_fu_participants_collection");

        ExtensionElement elementVariableElement = new ExtensionElement();
        elementVariableElement.setName("elementVariable");
        elementVariableElement.setNamespace("http://flowable.org/bpmn");
        elementVariableElement.setElementText("currentItem");

        loopCharacteristics.addExtensionElement(elementVariableElement);
        
        subProcess.setLoopCharacteristics(loopCharacteristics);
        
        // 在子流程内部创建 UserTask
        UserTask subUserTask = new UserTask();
        subUserTask.setId("MI_UserTask_45");
        subUserTask.setName("补充个人信息");
        
        // 添加子表配置扩展属性
        ExtensionElement propertiesElement = new ExtensionElement();
        propertiesElement.setName("properties");
        propertiesElement.setNamespace("http://custom.namespace");
        
        ExtensionElement subTableNameProperty = new ExtensionElement();
        subTableNameProperty.setName("property");
        subTableNameProperty.setNamespace("http://custom.namespace");
        subTableNameProperty.addAttribute(new ExtensionAttribute("name", "subTableName"));
        subTableNameProperty.addAttribute(new ExtensionAttribute("value", "fu_participants"));
        
        ExtensionElement assigneeFieldProperty = new ExtensionElement();
        assigneeFieldProperty.setName("property");
        assigneeFieldProperty.setNamespace("http://custom.namespace");
        assigneeFieldProperty.addAttribute(new ExtensionAttribute("name", "assigneeField"));
        assigneeFieldProperty.addAttribute(new ExtensionAttribute("value", "assignee_user_id"));
        
        propertiesElement.addChildElement(subTableNameProperty);
        propertiesElement.addChildElement(assigneeFieldProperty);
        subUserTask.addExtensionElement(propertiesElement);
        
        subProcess.addFlowElement(subUserTask);
        
        // 创建连线
        SequenceFlow flow = new SequenceFlow();
        flow.setId("flow1");
        flow.setSourceRef(TASK_DEFINITION_KEY);
        flow.setTargetRef("MultiInstance_SubTable_45");
        
        approveTask.setOutgoingFlows(Collections.singletonList(flow));
        
        // 添加元素到流程
        process.addFlowElement(approveTask);
        process.addFlowElement(subProcess);
        process.addFlowElement(flow);
        
        bpmnModel.addProcess(process);
        
        return bpmnModel;
    }
    
    /**
     * 创建不包含多实例子流程的 BPMN 模型
     */
    private BpmnModel createBpmnModelWithoutMultiInstance() {
        BpmnModel bpmnModel = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("Process_1");
        
        // 创建任务
        UserTask userTask = new UserTask();
        userTask.setId(TASK_DEFINITION_KEY);
        userTask.setName("测试任务");
        
        // 创建下一个普通任务
        UserTask nextTask = new UserTask();
        nextTask.setId("nextTask");
        nextTask.setName("下一个任务");
        
        // 创建连线
        SequenceFlow flow = new SequenceFlow();
        flow.setId("flow1");
        flow.setSourceRef(TASK_DEFINITION_KEY);
        flow.setTargetRef("nextTask");
        
        userTask.setOutgoingFlows(Collections.singletonList(flow));
        
        // 添加元素到流程
        process.addFlowElement(userTask);
        process.addFlowElement(nextTask);
        process.addFlowElement(flow);
        
        bpmnModel.addProcess(process);
        
        return bpmnModel;
    }
}
