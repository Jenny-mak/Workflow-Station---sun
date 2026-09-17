package com.workflow.listener;

import com.workflow.component.BpmnActionParser;
import com.workflow.entity.ExtendedTaskInfo;
import com.workflow.enums.AssignmentType;
import com.workflow.repository.ExtendedTaskInfoRepository;
import com.workflow.service.TaskAssigneeResolver;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.event.impl.FlowableEntityEventImpl;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskAssignmentListener writes wf_extended_task_info for non-MI user tasks")
class TaskAssignmentListenerRegularExtendedTest {

    private static final String TASK_ID = "task-regular-pool";
    private static final String PROCESS_INSTANCE_ID = "pi-regular";
    private static final String PROCESS_DEFINITION_ID = "pd-regular";
    private static final String TASK_DEFINITION_KEY = "Review";
    private static final String INITIATOR_ID = "user-e2e-zhangwei";
    private static final String ROLE_ID = "MANAGER";
    private static final String BU_ID = "E2E_FINANCE";

    @Mock
    private TaskAssigneeResolver taskAssigneeResolver;
    @Mock
    private TaskService taskService;
    @Mock
    private RuntimeService runtimeService;
    @Mock
    private RepositoryService repositoryService;
    @Mock
    private BpmnActionParser bpmnActionParser;
    @Mock
    private ExtendedTaskInfoRepository extendedTaskInfoRepository;

    @InjectMocks
    private TaskAssignmentListener listener;

    @BeforeEach
    void stubParser() {
        lenient().when(bpmnActionParser.getUserTaskExtensionPropertyValue(any(), any(), any()))
                .thenReturn(null);
        lenient().when(bpmnActionParser.getMultiInstanceSubProcessSubTableName(any(), any()))
                .thenReturn(null);
        when(extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(TASK_ID)).thenReturn(Optional.empty());
        when(extendedTaskInfoRepository.save(any(ExtendedTaskInfo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("FIXED_BU_ROLE candidate pool inserts CANDIDATE_USERS extended row")
    void fixedBuRoleCandidatesPersistExtendedRow() {
        TaskEntity task = mockTask();
        when(repositoryService.getBpmnModel(PROCESS_DEFINITION_ID)).thenReturn(
                bpmnWith("FIXED_BU_ROLE", ROLE_ID, BU_ID));
        when(runtimeService.getVariables(PROCESS_INSTANCE_ID)).thenReturn(Map.of("initiator", INITIATOR_ID));
        when(taskAssigneeResolver.resolveWithRoleIds(
                eq("FIXED_BU_ROLE"), eq(List.of(ROLE_ID)), eq(BU_ID), eq(INITIATOR_ID), isNull(), isNull()))
                .thenReturn(TaskAssigneeResolver.ResolveResult.builder()
                        .candidateUsers(Arrays.asList("user-e2e-lina", "user-e2e-manager2"))
                        .requiresClaim(true)
                        .build());

        listener.onEvent(taskCreated(task));

        ArgumentCaptor<ExtendedTaskInfo> captor = ArgumentCaptor.forClass(ExtendedTaskInfo.class);
        verify(extendedTaskInfoRepository).save(captor.capture());
        ExtendedTaskInfo saved = captor.getValue();
        assertThat(saved.getTaskId()).isEqualTo(TASK_ID);
        assertThat(saved.getAssignmentType()).isEqualTo(AssignmentType.CANDIDATE_USERS);
        assertThat(saved.getAssignmentTarget()).isEqualTo("user-e2e-lina,user-e2e-manager2");
        assertThat(saved.getStatus()).isEqualTo("ASSIGNED");
        assertThat(saved.getExtendedProperties()).isNull();
    }

    @Test
    @DisplayName("Direct assignee at create time inserts USER extended row")
    void preassignedUserPersistsExtendedRow() {
        TaskEntity task = mockTask();
        when(task.getAssignee()).thenReturn("user-direct");

        listener.onEvent(taskCreated(task));

        ArgumentCaptor<ExtendedTaskInfo> captor = ArgumentCaptor.forClass(ExtendedTaskInfo.class);
        verify(extendedTaskInfoRepository).save(captor.capture());
        assertThat(captor.getValue().getAssignmentType()).isEqualTo(AssignmentType.USER);
        assertThat(captor.getValue().getAssignmentTarget()).isEqualTo("user-direct");
    }

    private static TaskEntity mockTask() {
        TaskEntity task = mock(TaskEntity.class);
        when(task.getId()).thenReturn(TASK_ID);
        when(task.getProcessInstanceId()).thenReturn(PROCESS_INSTANCE_ID);
        when(task.getProcessDefinitionId()).thenReturn(PROCESS_DEFINITION_ID);
        when(task.getTaskDefinitionKey()).thenReturn(TASK_DEFINITION_KEY);
        when(task.getName()).thenReturn("Review");
        lenient().when(task.getAssignee()).thenReturn(null);
        return task;
    }

    private static FlowableEntityEventImpl taskCreated(TaskEntity task) {
        FlowableEntityEventImpl event = mock(FlowableEntityEventImpl.class);
        when(event.getType()).thenReturn(FlowableEngineEventType.TASK_CREATED);
        when(event.getEntity()).thenReturn(task);
        return event;
    }

    private static BpmnModel bpmnWith(String assigneeType, String roleId, String businessUnitId) {
        BpmnModel bpmnModel = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("Process_1");
        UserTask userTask = new UserTask();
        userTask.setId(TASK_DEFINITION_KEY);
        userTask.setName("Review");
        ExtensionElement propertiesElement = new ExtensionElement();
        propertiesElement.setName("properties");
        propertiesElement.setNamespace("http://custom.bpmn.io/schema");
        List<ExtensionElement> propertyElements = new ArrayList<>();
        propertyElements.add(namedProperty("assigneeType", assigneeType));
        propertyElements.add(namedProperty("roleId", roleId));
        propertyElements.add(namedProperty("businessUnitId", businessUnitId));
        Map<String, List<ExtensionElement>> childElements = new HashMap<>();
        childElements.put("property", propertyElements);
        propertiesElement.setChildElements(childElements);
        Map<String, List<ExtensionElement>> extensionElements = new HashMap<>();
        extensionElements.put("properties", List.of(propertiesElement));
        userTask.setExtensionElements(extensionElements);
        process.addFlowElement(userTask);
        bpmnModel.addProcess(process);
        return bpmnModel;
    }

    private static ExtensionElement namedProperty(String name, String value) {
        ExtensionElement property = new ExtensionElement();
        property.setName("property");
        property.setNamespace("http://custom.bpmn.io/schema");
        property.addAttribute(attribute("name", name));
        property.addAttribute(attribute("value", value));
        return property;
    }

    private static ExtensionAttribute attribute(String name, String value) {
        ExtensionAttribute attr = new ExtensionAttribute();
        attr.setName(name);
        attr.setValue(value);
        return attr;
    }
}
