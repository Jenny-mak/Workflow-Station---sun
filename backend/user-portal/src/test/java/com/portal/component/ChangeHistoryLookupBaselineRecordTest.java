package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.client.WorkflowEngineClient;
import com.portal.dto.ChangeHistoryContext;
import com.portal.repository.ChangeHistoryRepository;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.testsupport.PortalTransactionTestSupport;
import com.platform.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * After the submission filter projects lookup objects to the designer display
 * column, a later stage that still stores the object in process variables must
 * not emit a field update for the same visible value.
 */
class ChangeHistoryLookupBaselineRecordTest {

    private ChangeHistoryRepository changeHistoryRepository;
    private ChangeHistoryComponent component;

    @BeforeEach
    void setUp() {
        changeHistoryRepository = mock(ChangeHistoryRepository.class);
        when(changeHistoryRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        component = new ChangeHistoryComponent(
                changeHistoryRepository,
                mock(ProcessInstanceRepository.class),
                mock(UserRepository.class),
                mock(WorkflowEngineClient.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                mock(UserPortalAuditEnricher.class),
                mock(UserPortalAuditProcessInstanceMatcher.class),
                PortalTransactionTestSupport.noopPlatformTransactionManager());
    }

    @Test
    void projectedLookupDisplayDoesNotRecordARepeatOfTheSameVisibleValue() {
        ChangeHistoryContext context = ChangeHistoryContext.builder()
                .processInstanceId("p1")
                .taskInstanceId("t1")
                .stageId("stage-2")
                .userId("u1")
                .build();
        component.recordFieldChanges(context,
                Map.of("item_status", "Open"),
                Map.of("item_status", "Open"));
        verify(changeHistoryRepository, never()).saveAll(anyList());
    }

    @Test
    void aRealLookupDisplayChangeIsStillRecorded() {
        ChangeHistoryContext context = ChangeHistoryContext.builder()
                .processInstanceId("p1")
                .taskInstanceId("t1")
                .stageId("stage-2")
                .userId("u1")
                .build();
        component.recordFieldChanges(context,
                Map.of("item_status", "Open"),
                Map.of("item_status", "Closed"));
        verify(changeHistoryRepository).saveAll(anyList());
    }
}
