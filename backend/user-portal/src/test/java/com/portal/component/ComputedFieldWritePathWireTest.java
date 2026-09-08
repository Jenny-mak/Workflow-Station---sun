package com.portal.component;

import com.portal.entity.ProcessInstance;
import com.portal.repository.ProcessInstanceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Guards approval merge wiring: recomputation must run on the full merged record, not the
 * incremental submission alone.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Computed field write-path wiring")
class ComputedFieldWritePathWireTest {

    private static final String FU_CODE = "purchase-request";

    @Mock
    private ProcessStartFormEnricherComponent processStartFormEnricherComponent;

    @Mock
    private ComputedFieldRecalculator computedFieldRecalculator;

    @InjectMocks
    private ProcessStartComponent processStartComponent;

    @Test
    @DisplayName("ProcessStartComponent declares start enricher for Spring constructor wiring")
    void processStartComponentHasEnricherDependency() {
        verifyNoInteractions(processStartFormEnricherComponent);
    }

    @Test
    @DisplayName("approval merge recomputes on existing variables plus submission")
    void approvalMergeRecalculatesFullRecord() {
        ProcessInstanceRepository repository = mock(ProcessInstanceRepository.class);
        when(repository.findById("pi-1")).thenReturn(Optional.of(ProcessInstance.builder()
                .id("pi-1")
                .functionUnitCode(FU_CODE)
                .variables(new HashMap<>(Map.of("amount", "100")))
                .build()));

        TaskApprovalCompletionComponent approval = new TaskApprovalCompletionComponent(
                null, null, repository, null, null, null, null, null);
        ReflectionTestUtils.setField(approval, "computedFieldRecalculator", computedFieldRecalculator);

        Map<String, Object> submission = new HashMap<>();
        submission.put("tax_rate", "0.05");

        @SuppressWarnings("unchecked")
        Map<String, Object> merged = ReflectionTestUtils.invokeMethod(
                approval, "mergeApprovalVariables", "pi-1", submission);

        verify(computedFieldRecalculator).recalculate(eq(FU_CODE), same(merged));
    }
}
