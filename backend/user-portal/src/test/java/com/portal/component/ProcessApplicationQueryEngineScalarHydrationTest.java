package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.entity.ProcessInstance;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.UserDisplayNameResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the "My Applications" detail read path for plain (non-sub-table) service-task outputs:
 * an Activepieces node writes {@code output_text} into the Flowable engine, but the portal's
 * {@code up_process_instance} store still holds the {@code null} the start form submitted, so the
 * field rendered blank. A straight-through automation ends immediately, so the hydration must also
 * run for {@code COMPLETED} instances — and must never overwrite a value the user actually entered.
 */
@DisplayName("ProcessApplicationQueryComponent.getProcessDetail engine-scalar hydration")
class ProcessApplicationQueryEngineScalarHydrationTest {

    private static final String PID = "pi-1";

    private ProcessInstance storedInstance(String status, Map<String, Object> variables) {
        ProcessInstance instance = new ProcessInstance();
        instance.setId(PID);
        instance.setStatus(status);
        instance.setVariables(variables);
        return instance;
    }

    private WorkflowEngineClient engineWith(Map<String, Object> engineVars) {
        WorkflowEngineClient client = mock(WorkflowEngineClient.class);
        when(client.isAvailable()).thenReturn(true);
        when(client.getProcessInstance(anyString()))
                .thenReturn(Optional.of(Map.of("variables", engineVars)));
        return client;
    }

    private ProcessApplicationQueryComponent newComponent(
            ProcessInstanceRepository repository, WorkflowEngineClient client) {
        return new ProcessApplicationQueryComponent(
                repository,
                client,
                new EngineSubTableHydrator(client),
                mock(UserDisplayNameResolver.class),
                mock(MiOverlayComponent.class),
                mock(SubTableEnrichmentComponent.class),
                mock(RequestIdEnricher.class),
                mock(MainTableViewInvolvementChecker.class),
                mock(MainTableViewAccessResolver.class),
                mock(FunctionUnitAccessComponent.class),
                mock(JdbcTemplate.class),
                mock(MiOuterStepResolver.class));
    }

    @Test
    @DisplayName("fills a null store variable from the engine on a COMPLETED straight-through run")
    void fillsNullScalarFromEngineWhenCompleted() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("input_json", "{\"a\":1}");
        stored.put("output_text", null); // service task's write-back never reached the portal store
        ProcessInstance instance = storedInstance("COMPLETED", stored);

        ProcessInstanceRepository repository = mock(ProcessInstanceRepository.class);
        when(repository.findById(PID)).thenReturn(Optional.of(instance));
        WorkflowEngineClient client = engineWith(Map.of(
                "input_json", "{\"a\":1}",
                "output_text", "{\"a\":1}"));

        ProcessInstanceInfo info = newComponent(repository, client).getProcessDetail(PID);

        assertEquals("{\"a\":1}", info.getVariables().get("output_text"),
                "engine-only service-task output must reach the detail DTO");
        assertEquals("{\"a\":1}", instance.getVariables().get("output_text"),
                "the merge must be persisted back to the portal store");
        verify(repository).save(instance);
    }

    @Test
    @DisplayName("never overwrites a value the portal store already holds")
    void keepsPortalValueOverEngineValue() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("remark", "typed by the user");
        ProcessInstance instance = storedInstance("COMPLETED", stored);

        ProcessInstanceRepository repository = mock(ProcessInstanceRepository.class);
        when(repository.findById(PID)).thenReturn(Optional.of(instance));
        WorkflowEngineClient client = engineWith(Map.of("remark", "stale engine value"));

        ProcessInstanceInfo info = newComponent(repository, client).getProcessDetail(PID);

        assertEquals("typed by the user", info.getVariables().get("remark"));
        // No null slot to fill -> no engine round-trip and no write at all.
        verify(client, never()).getProcessInstance(anyString());
        verify(repository, never()).save(any(ProcessInstance.class));
    }

    @Test
    @DisplayName("leaves __subTables__ to the dedicated sub-table hydrator")
    void doesNotTouchSubTables() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("__subTables__", null);
        stored.put("output_text", null);
        ProcessInstance instance = storedInstance("COMPLETED", stored);

        ProcessInstanceRepository repository = mock(ProcessInstanceRepository.class);
        when(repository.findById(PID)).thenReturn(Optional.of(instance));
        WorkflowEngineClient client = engineWith(Map.of(
                "__subTables__", Map.of("50111", java.util.List.of(Map.of("id", "r-1"))),
                "output_text", "done"));

        ProcessInstanceInfo info = newComponent(repository, client).getProcessDetail(PID);

        assertEquals("done", info.getVariables().get("output_text"));
        assertNull(info.getVariables().get("__subTables__"),
                "__subTables__ has its own per-slice merge rules and must not be bulk-copied here");
    }
}
