package com.portal.component;

import com.platform.security.entity.User;
import com.platform.security.repository.UserRepository;
import com.portal.dto.TaskInfo;
import com.portal.entity.DelegationAudit;
import com.portal.entity.DelegationRule;
import com.portal.enums.DelegateTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DelegationUserDisplayEnricherTest {

    @Mock
    private UserRepository userRepository;

    private DelegationUserDisplayEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new DelegationUserDisplayEnricher(userRepository);
    }

    @Test
    void rulesResolveDisplayNameWithoutReplacingStoredId() {
        when(userRepository.findAllById(Set.of("user-e2e-lina"))).thenReturn(List.of(
                User.builder()
                        .id("user-e2e-lina")
                        .username("e2e_lina")
                        .displayName("Lina Chen")
                        .passwordHash("x")
                        .build()));

        DelegationRule row = DelegationRule.builder()
                .delegateId("user-e2e-lina")
                .delegateTargetType(DelegateTargetType.USER)
                .build();
        enricher.enrichRules(List.of(row));

        assertThat(row.getDelegateId()).isEqualTo("user-e2e-lina");
        assertThat(row.getDelegateDisplayName()).isEqualTo("Lina Chen");
    }

    @Test
    void buRoleRulesSkipUserLookup() {
        DelegationRule row = DelegationRule.builder()
                .delegateId(null)
                .delegateTargetType(DelegateTargetType.BU_ROLE)
                .delegateBuCode("hase-hmdc")
                .delegateRoleCode("HMDC_Index_Role")
                .build();
        enricher.enrichRules(List.of(row));

        assertThat(row.getDelegateDisplayName()).isNull();
    }

    @Test
    void auditSkipsBuRoleTokensAndKeepsUserIds() {
        when(userRepository.findAllById(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Set<String> ids = (Set<String>) invocation.getArgument(0);
            assertThat(ids).containsExactly("user-e2e-zhangwei");
            return List.of(User.builder()
                    .id("user-e2e-zhangwei")
                    .username("e2e_zhangwei")
                    .fullName("Zhang Wei")
                    .passwordHash("x")
                    .build());
        });

        DelegationAudit row = DelegationAudit.builder()
                .delegatorId("user-e2e-zhangwei")
                .delegateId("hase-hmdc/HMDC_Index_Role")
                .operationType("CREATE_DELEGATION")
                .build();
        enricher.enrichAudit(List.of(row));

        assertThat(row.getDelegatorDisplayName()).isEqualTo("Zhang Wei");
        assertThat(row.getDelegateDisplayName()).isNull();
        assertThat(row.getDelegateId()).isEqualTo("hase-hmdc/HMDC_Index_Role");
    }

    @Test
    void missingUserLeavesDisplayNameEmpty() {
        when(userRepository.findAllById(Set.of("user-gone"))).thenReturn(List.of());

        DelegationRule row = DelegationRule.builder()
                .delegateId("user-gone")
                .delegateTargetType(DelegateTargetType.USER)
                .build();
        enricher.enrichRules(List.of(row));

        assertThat(row.getDelegateDisplayName()).isNull();
        assertThat(row.getDelegateId()).isEqualTo("user-gone");
    }

    @Test
    void delegatedTasksFillDelegatorName() {
        when(userRepository.findAllById(Set.of("user-e2e-lina"))).thenReturn(List.of(
                User.builder()
                        .id("user-e2e-lina")
                        .username("e2e_lina")
                        .displayName("李娜")
                        .passwordHash("x")
                        .build()));

        TaskInfo row = TaskInfo.builder()
                .delegatorId("user-e2e-lina")
                .build();
        enricher.enrichDelegatedTasks(List.of(row));

        assertThat(row.getDelegatorName()).isEqualTo("李娜");
        assertThat(row.getDelegatorId()).isEqualTo("user-e2e-lina");
    }
}
