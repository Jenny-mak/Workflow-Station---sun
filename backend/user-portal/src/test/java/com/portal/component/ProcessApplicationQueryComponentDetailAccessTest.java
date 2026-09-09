package com.portal.component;

import com.portal.component.MainTableViewAccessResolver.AccessRule;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.entity.ProcessInstance;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.UserDisplayNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.portal.exception.PortalException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessApplicationQueryComponentDetailAccessTest {

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
    @Mock private FunctionUnitAccessComponent functionUnitAccessComponent;

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
    }

    @Test
    void canAccessProcessDetail_allowsParticipant() {
        ProcessInstanceInfo detail = ProcessInstanceInfo.builder()
                .id("pi-1")
                .startUserId("user-dev")
                .build();

        assertThat(component.canAccessProcessDetail("user-dev", detail)).isTrue();
    }

    @Test
    void canAccessProcessDetail_allowsOpenViewReaderWhenNotRestrictedToInvolved() {
        ProcessInstanceInfo detail = ProcessInstanceInfo.builder()
                .id("pi-email")
                .startUserId("system")
                .functionUnitCode("test-20260803-xc0jmo")
                .build();
        when(jdbcTemplate.queryForList(anyString(), eq("test-20260803-xc0jmo")))
                .thenReturn(List.of(Map.of("id", 50325L, "restrict_to_involved_users", false)));
        when(jdbcTemplate.queryForList(anyString(), eq(50325L)))
                .thenReturn(List.of(Map.of("target_type", "BUSINESS_UNIT", "target_id", "bu-1")));
        when(mainTableViewAccessResolver.parseAccessRules(anyList()))
                .thenReturn(List.of(new AccessRule("BUSINESS_UNIT", "bu-1")));
        when(mainTableViewAccessResolver.canUserSeeView(eq("user-dev"), anyList())).thenReturn(true);

        assertThat(component.canAccessProcessDetail("user-dev", detail)).isTrue();
    }

    @Test
    void canAccessProcessDetail_deniesWhenRestrictedViewAndUserNotInvolved() {
        ProcessInstanceInfo detail = ProcessInstanceInfo.builder()
                .id("pi-email")
                .startUserId("system")
                .functionUnitCode("test-20260803-xc0jmo")
                .build();
        ProcessInstance entity = new ProcessInstance();
        entity.setId("pi-email");
        entity.setStartUserId("system");
        when(jdbcTemplate.queryForList(anyString(), eq("test-20260803-xc0jmo")))
                .thenReturn(List.of(Map.of("id", 50325L, "restrict_to_involved_users", true)));
        when(jdbcTemplate.queryForList(anyString(), eq(50325L)))
                .thenReturn(List.of(Map.of("target_type", "BUSINESS_UNIT", "target_id", "bu-1")));
        when(mainTableViewAccessResolver.parseAccessRules(anyList()))
                .thenReturn(List.of(new AccessRule("BUSINESS_UNIT", "bu-1")));
        when(mainTableViewAccessResolver.canUserSeeView(eq("user-dev"), anyList())).thenReturn(true);
        when(processInstanceRepository.findById("pi-email")).thenReturn(Optional.of(entity));
        when(mainTableViewInvolvementChecker.isUserInvolved("user-dev", entity)).thenReturn(false);

        assertThat(component.canAccessProcessDetail("user-dev", detail)).isFalse();
    }

    @Test
    void canAccessProcessDetail_throwsWhenAccessRulesLoadFails() {
        ProcessInstanceInfo detail = ProcessInstanceInfo.builder()
                .id("pi-email")
                .startUserId("system")
                .functionUnitCode("test-20260803-xc0jmo")
                .build();
        when(jdbcTemplate.queryForList(anyString(), eq("test-20260803-xc0jmo")))
                .thenReturn(List.of(Map.of("id", 50325L, "restrict_to_involved_users", false)));
        when(jdbcTemplate.queryForList(anyString(), eq(50325L)))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> component.canAccessProcessDetail("user-dev", detail))
                .isInstanceOf(PortalException.class)
                .hasMessageContaining("Failed to load process detail access rules");
    }
}
