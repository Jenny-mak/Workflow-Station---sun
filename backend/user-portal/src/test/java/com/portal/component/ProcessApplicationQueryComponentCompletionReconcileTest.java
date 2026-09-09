package com.portal.component;

import com.portal.dto.ProcessInstanceInfo;
import com.portal.entity.ProcessInstance;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.UserDisplayNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessApplicationQueryComponentCompletionReconcileTest {

    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private com.portal.client.WorkflowEngineClient workflowEngineClient;
    @Mock private EngineSubTableHydrator engineSubTableHydrator;
    @Mock private UserDisplayNameResolver userDisplayNameResolver;
    @Mock private MiOverlayComponent miOverlayComponent;
    @Mock private SubTableEnrichmentComponent subTableEnrichmentComponent;
    @Mock private RequestIdEnricher requestIdEnricher;
    @Mock private MainTableViewInvolvementChecker mainTableViewInvolvementChecker;
    @Mock private MainTableViewAccessResolver mainTableViewAccessResolver;
    @Mock private JdbcTemplate jdbcTemplate;

    private ProcessApplicationQueryComponent component;

    @BeforeEach
    void setUp() {
        component = new ProcessApplicationQueryComponent(
                processInstanceRepository,
                workflowEngineClient,
                engineSubTableHydrator,
                userDisplayNameResolver,
                miOverlayComponent,
                subTableEnrichmentComponent,
                requestIdEnricher,
                mainTableViewInvolvementChecker,
                mainTableViewAccessResolver,
                org.mockito.Mockito.mock(FunctionUnitAccessComponent.class),
                jdbcTemplate,
                org.mockito.Mockito.mock(MiOuterStepResolver.class));
    }

    @Test
    void getProcessDetail_reconcilesRunningPortalRowWhenEngineAlreadyCompleted() {
        String processId = "93b53f43-8fb4-11f1-b8d6-9a88796d717a";
        ProcessInstance instance = ProcessInstance.builder()
                .id(processId)
                .processInstanceId(processId)
                .processDefinitionKey("test-key")
                .startUserId("system")
                .status("RUNNING")
                .build();

        when(processInstanceRepository.findById(processId)).thenReturn(Optional.of(instance));
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        when(workflowEngineClient.getProcessInstanceTasks(processId)).thenReturn(Optional.of(Map.of("tasks", java.util.List.of())));
        when(workflowEngineClient.getProcessInstanceStatus(processId)).thenReturn(Optional.of(Map.of(
                "completed", true,
                "state", "COMPLETED",
                "lastActivityName", "End"
        )));
        when(processInstanceRepository.save(any(ProcessInstance.class))).thenAnswer(inv -> inv.getArgument(0));

        ProcessInstanceInfo info = component.getProcessDetail(processId);

        assertThat(info).isNotNull();
        assertThat(info.getStatus()).isEqualTo("COMPLETED");
        assertThat(info.getCurrentNode()).isNull();

        ArgumentCaptor<ProcessInstance> captor = ArgumentCaptor.forClass(ProcessInstance.class);
        verify(processInstanceRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void getProcessDetail_keepsRunningWhenEngineStillActive() {
        String processId = "proc-running";
        ProcessInstance instance = ProcessInstance.builder()
                .id(processId)
                .processInstanceId(processId)
                .processDefinitionKey("test-key")
                .startUserId("user-dev")
                .status("RUNNING")
                .build();

        when(processInstanceRepository.findById(processId)).thenReturn(Optional.of(instance));
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        when(workflowEngineClient.getProcessInstanceTasks(processId)).thenReturn(Optional.of(Map.of("tasks", java.util.List.of())));
        when(workflowEngineClient.getProcessInstanceStatus(processId)).thenReturn(Optional.of(Map.of(
                "completed", false,
                "state", "RUNNING"
        )));

        ProcessInstanceInfo info = component.getProcessDetail(processId);

        assertThat(info).isNotNull();
        assertThat(info.getStatus()).isEqualTo("RUNNING");
        verify(processInstanceRepository, never()).save(any());
    }
}
