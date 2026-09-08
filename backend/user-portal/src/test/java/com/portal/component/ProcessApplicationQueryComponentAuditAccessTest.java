package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.UserDisplayNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reviewers are neither initiator nor participant, so the audit grant is the only
 * thing standing between them and someone else's request. These tests pin down
 * that the grant is scoped to one function unit and cannot stand in for the
 * participant gate elsewhere.
 */
@ExtendWith(MockitoExtension.class)
class ProcessApplicationQueryComponentAuditAccessTest {

    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private WorkflowEngineClient workflowEngineClient;
    @Mock private EngineSubTableHydrator engineSubTableHydrator;
    @Mock private UserDisplayNameResolver userDisplayNameResolver;
    @Mock private MiOverlayComponent miOverlayComponent;
    @Mock private SubTableEnrichmentComponent subTableEnrichmentComponent;
    @Mock private RequestIdEnricher requestIdEnricher;
    @Mock private MainTableViewInvolvementChecker mainTableViewInvolvementChecker;
    @Mock private MainTableViewAccessResolver mainTableViewAccessResolver;
    @Mock private FunctionUnitAccessComponent functionUnitAccessComponent;
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
                functionUnitAccessComponent,
                jdbcTemplate,
                org.mockito.Mockito.mock(MiOuterStepResolver.class));
        lenient().when(workflowEngineClient.isAvailable()).thenReturn(false);
        // No published views, so nothing leaks in through the view path.
        lenient().when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());
    }

    private static ProcessInstanceInfo otherUsersRequest(String functionUnitCode) {
        return ProcessInstanceInfo.builder()
                .id("proc-1")
                .startUserId("someone-else")
                .functionUnitCode(functionUnitCode)
                .build();
    }

    @Test
    void auditGrantOnTheOwningUnitAllowsDetail() {
        ProcessInstanceInfo detail = otherUsersRequest("fu-expense");
        when(mainTableViewAccessResolver.isSystemAdministrator("reviewer")).thenReturn(false);
        when(functionUnitAccessComponent.canAuditFunctionUnit("reviewer", "fu-expense")).thenReturn(true);

        assertThat(component.canAuditProcessDetail("reviewer", detail)).isTrue();
    }

    /**
     * N2: the grant is per function unit. Holding one on another unit must not open
     * this one — otherwise a single grant would expose the whole platform.
     */
    @Test
    void auditGrantOnAnotherUnitDoesNotAllowDetail() {
        ProcessInstanceInfo detail = otherUsersRequest("fu-expense");
        when(mainTableViewAccessResolver.isSystemAdministrator("reviewer")).thenReturn(false);
        when(functionUnitAccessComponent.canAuditFunctionUnit("reviewer", "fu-expense")).thenReturn(false);

        assertThat(component.canAuditProcessDetail("reviewer", detail)).isFalse();
    }

    @Test
    void withoutAnyGrantAuditAccessIsRefused() {
        ProcessInstanceInfo detail = otherUsersRequest("fu-expense");
        when(mainTableViewAccessResolver.isSystemAdministrator("stranger")).thenReturn(false);
        when(functionUnitAccessComponent.canAuditFunctionUnit("stranger", "fu-expense")).thenReturn(false);

        assertThat(component.canAuditProcessDetail("stranger", detail)).isFalse();
    }

    /**
     * An instance with no function unit code cannot be matched against any grant,
     * so it must be refused rather than fall through to an unscoped allow.
     */
    @Test
    void instanceWithoutFunctionUnitCodeIsRefusedWithoutConsultingGrants() {
        ProcessInstanceInfo detail = otherUsersRequest(null);
        when(mainTableViewAccessResolver.isSystemAdministrator("reviewer")).thenReturn(false);

        assertThat(component.canAuditProcessDetail("reviewer", detail)).isFalse();
        verify(functionUnitAccessComponent, never()).canAuditFunctionUnit(anyString(), anyString());
    }

    @Test
    void participantsStillPassWithoutNeedingAnAuditGrant() {
        ProcessInstanceInfo detail = ProcessInstanceInfo.builder()
                .id("proc-1")
                .startUserId("initiator")
                .functionUnitCode("fu-expense")
                .build();

        assertThat(component.canAuditProcessDetail("initiator", detail)).isTrue();
        verify(functionUnitAccessComponent, never()).canAuditFunctionUnit(anyString(), anyString());
    }

    @Test
    void nullUserIsRefused() {
        assertThat(component.canAuditProcessDetail(null, otherUsersRequest("fu-expense"))).isFalse();
        assertThat(component.canAuditProcessDetail("reviewer", null)).isFalse();
    }
}
