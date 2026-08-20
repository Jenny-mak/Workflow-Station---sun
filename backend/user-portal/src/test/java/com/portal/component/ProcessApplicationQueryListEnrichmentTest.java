package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.entity.ProcessInstance;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.UserDisplayNameResolver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessApplicationQueryListEnrichmentTest {

    @Test
    void listPathDoesNotCallEngineWhenAssigneeAlreadyStored() {
        ProcessInstance instance = new ProcessInstance();
        instance.setId("pi-1");
        instance.setStatus("RUNNING");
        instance.setCurrentAssignee("user-1");
        instance.setCandidateUsers("user-2,user-3");

        UserDisplayNameResolver names = mock(UserDisplayNameResolver.class);

        WorkflowEngineClient engine = mock(WorkflowEngineClient.class);
        when(engine.isAvailable()).thenReturn(true);

        ProcessApplicationQueryComponent component = new ProcessApplicationQueryComponent(
                mock(ProcessInstanceRepository.class),
                engine,
                mock(EngineSubTableHydrator.class),
                names,
                mock(MiOverlayComponent.class),
                mock(SubTableEnrichmentComponent.class),
                mock(RequestIdEnricher.class),
                mock(MainTableViewInvolvementChecker.class),
                mock(MainTableViewAccessResolver.class),
                mock(JdbcTemplate.class));

        component.enrichRunningAssigneesFromEngine(List.of(instance));

        verify(engine, never()).getProcessInstanceTasks(anyString());
    }
}
