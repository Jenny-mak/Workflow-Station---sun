package com.portal.properties;

import com.portal.component.DelegationComponent;
import com.portal.dto.DelegationRuleRequest;
import com.portal.entity.DelegationRule;
import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
import com.portal.exception.PortalException;
import com.portal.repository.DelegationAuditRepository;
import com.portal.repository.DelegationRuleRepository;
import com.platform.common.i18n.I18nService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 委托规则属性测试
 * 验证委托规则的正确性
 */
class DelegationRuleProperties {

    @Mock
    private DelegationRuleRepository delegationRuleRepository;

    @Mock
    private DelegationAuditRepository delegationAuditRepository;

    @Mock
    private I18nService i18nService;

    private DelegationComponent delegationComponent;
    private Random random;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(i18nService.getMessage(any(String.class))).thenReturn("test message");
        delegationComponent = new DelegationComponent(delegationRuleRepository, delegationAuditRepository, i18nService, mock(jakarta.persistence.EntityManager.class));
        random = new Random();

        // 默认返回空列表
        when(delegationRuleRepository.findActiveDelegationRules(any(), any()))
                .thenReturn(Collections.emptyList());
    }

    /**
     * 属性1: 不能委托给自己
     */
    @RepeatedTest(20)
    void cannotDelegateToSelf() {
        String userId = "user_" + random.nextInt(1000);

        DelegationRuleRequest request = DelegationRuleRequest.builder()
                .delegateId(userId)
                .delegationType(DelegationType.ALL)
                .build();

        assertThrows(PortalException.class, () -> 
            delegationComponent.createDelegationRule(userId, request));
    }

    @Test
    void partialDelegationRequiresProcessTypes() {
        DelegationRuleRequest request = DelegationRuleRequest.builder()
                .delegateId("delegate_1")
                .delegationType(DelegationType.PARTIAL)
                .build();
        assertThrows(PortalException.class,
                () -> delegationComponent.createDelegationRule("delegator_1", request));
    }

    @Test
    void temporaryDelegationRequiresTimeWindow() {
        DelegationRuleRequest request = DelegationRuleRequest.builder()
                .delegateId("delegate_1")
                .delegationType(DelegationType.TEMPORARY)
                .startTime(LocalDateTime.now())
                .build();
        assertThrows(PortalException.class,
                () -> delegationComponent.createDelegationRule("delegator_1", request));
    }

    /**
     * 属性2: 创建委托规则后状态为ACTIVE
     */
    @RepeatedTest(20)
    void createdDelegationRuleShouldBeActive() {
        String delegatorId = "delegator_" + random.nextInt(1000);
        String delegateId = "delegate_" + random.nextInt(1000);

        DelegationRuleRequest request = DelegationRuleRequest.builder()
                .delegateId(delegateId)
                .delegationType(DelegationType.ALL)
                .reason("出差委托")
                .build();

        when(delegationRuleRepository.save(any(DelegationRule.class)))
                .thenAnswer(invocation -> {
                    DelegationRule rule = invocation.getArgument(0);
                    rule.setId(1L);
                    return rule;
                });

        DelegationRule rule = delegationComponent.createDelegationRule(delegatorId, request);

        assertEquals(DelegationStatus.ACTIVE, rule.getStatus());
        assertEquals(delegatorId, rule.getDelegatorId());
        assertEquals(delegateId, rule.getDelegateId());
    }

    /**
     * 属性3: 暂停委托规则后状态变为SUSPENDED
     */
    @RepeatedTest(20)
    void suspendedDelegationRuleShouldHaveSuspendedStatus() {
        String delegatorId = "delegator_" + random.nextInt(1000);
        Long ruleId = (long) random.nextInt(1000);

        DelegationRule existingRule = DelegationRule.builder()
                .id(ruleId)
                .delegatorId(delegatorId)
                .delegateId("delegate_1")
                .delegationType(DelegationType.ALL)
                .status(DelegationStatus.ACTIVE)
                .build();

        when(delegationRuleRepository.findById(ruleId))
                .thenReturn(Optional.of(existingRule));
        when(delegationRuleRepository.save(any(DelegationRule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DelegationRule rule = delegationComponent.suspendDelegationRule(ruleId, delegatorId);

        assertEquals(DelegationStatus.SUSPENDED, rule.getStatus());
    }

    /**
     * 属性4: 恢复委托规则后状态变为ACTIVE
     */
    @RepeatedTest(20)
    void resumedDelegationRuleShouldHaveActiveStatus() {
        String delegatorId = "delegator_" + random.nextInt(1000);
        Long ruleId = (long) random.nextInt(1000);

        DelegationRule existingRule = DelegationRule.builder()
                .id(ruleId)
                .delegatorId(delegatorId)
                .delegateId("delegate_1")
                .delegationType(DelegationType.ALL)
                .status(DelegationStatus.SUSPENDED)
                .build();

        when(delegationRuleRepository.findById(ruleId))
                .thenReturn(Optional.of(existingRule));
        when(delegationRuleRepository.save(any(DelegationRule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DelegationRule rule = delegationComponent.resumeDelegationRule(ruleId, delegatorId);

        assertEquals(DelegationStatus.ACTIVE, rule.getStatus());
    }

    /**
     * 属性5: 只有委托人可以修改委托规则
     */
    @RepeatedTest(20)
    void onlyDelegatorCanModifyRule() {
        String delegatorId = "delegator_" + random.nextInt(1000);
        String otherUser = "other_" + random.nextInt(1000);
        Long ruleId = (long) random.nextInt(1000);

        DelegationRule existingRule = DelegationRule.builder()
                .id(ruleId)
                .delegatorId(delegatorId)
                .delegateId("delegate_1")
                .delegationType(DelegationType.ALL)
                .status(DelegationStatus.ACTIVE)
                .build();

        when(delegationRuleRepository.findById(ruleId))
                .thenReturn(Optional.of(existingRule));

        DelegationRuleRequest request = DelegationRuleRequest.builder()
                .delegateId("new_delegate")
                .delegationType(DelegationType.PARTIAL)
                .build();

        // 其他用户不能修改
        assertThrows(PortalException.class, () -> 
            delegationComponent.updateDelegationRule(ruleId, otherUser, request));

        // 其他用户不能删除
        assertThrows(PortalException.class, () -> 
            delegationComponent.deleteDelegationRule(ruleId, otherUser));

        // 其他用户不能暂停
        assertThrows(PortalException.class, () -> 
            delegationComponent.suspendDelegationRule(ruleId, otherUser));
    }

    /**
     * 属性6: 循环委托应该被检测并拒绝
     */
    @RepeatedTest(20)
    void circularDelegationShouldBeRejected() {
        String userA = "user_a_" + random.nextInt(1000);
        String userB = "user_b_" + random.nextInt(1000);

        // 模拟B已经委托给A
        DelegationRule existingRule = DelegationRule.builder()
                .delegatorId(userB)
                .delegateId(userA)
                .delegationType(DelegationType.ALL)
                .status(DelegationStatus.ACTIVE)
                .build();

        when(delegationRuleRepository.findActiveDelegationRules(eq(userB), any()))
                .thenReturn(List.of(existingRule));

        // A尝试委托给B，应该被拒绝
        DelegationRuleRequest request = DelegationRuleRequest.builder()
                .delegateId(userB)
                .delegationType(DelegationType.ALL)
                .build();

        assertThrows(PortalException.class, () -> 
            delegationComponent.createDelegationRule(userA, request));
    }

    /**
     * 属性7: 委托规则的时间范围应该被正确保存
     */
    @RepeatedTest(20)
    void delegationTimeRangeShouldBeSaved() {
        String delegatorId = "delegator_" + random.nextInt(1000);
        String delegateId = "delegate_" + random.nextInt(1000);
        LocalDateTime startTime = LocalDateTime.now().plusDays(1);
        LocalDateTime endTime = LocalDateTime.now().plusDays(7);

        DelegationRuleRequest request = DelegationRuleRequest.builder()
                .delegateId(delegateId)
                .delegationType(DelegationType.TEMPORARY)
                .startTime(startTime)
                .endTime(endTime)
                .build();

        when(delegationRuleRepository.save(any(DelegationRule.class)))
                .thenAnswer(invocation -> {
                    DelegationRule rule = invocation.getArgument(0);
                    rule.setId(1L);
                    return rule;
                });

        DelegationRule rule = delegationComponent.createDelegationRule(delegatorId, request);

        assertEquals(startTime, rule.getStartTime());
        assertEquals(endTime, rule.getEndTime());
        assertEquals(DelegationType.TEMPORARY, rule.getDelegationType());
    }

    /**
     * 属性8: 委托规则的流程类型筛选应该被正确保存
     */
    @RepeatedTest(20)
    void delegationProcessTypeFilterShouldBeSaved() {
        String delegatorId = "delegator_" + random.nextInt(1000);
        String delegateId = "delegate_" + random.nextInt(1000);
        List<String> processTypes = List.of("leave_process", "expense_process");

        DelegationRuleRequest request = DelegationRuleRequest.builder()
                .delegateId(delegateId)
                .delegationType(DelegationType.PARTIAL)
                .processTypes(processTypes)
                .build();

        when(delegationRuleRepository.save(any(DelegationRule.class)))
                .thenAnswer(invocation -> {
                    DelegationRule rule = invocation.getArgument(0);
                    rule.setId(1L);
                    return rule;
                });

        DelegationRule rule = delegationComponent.createDelegationRule(delegatorId, request);

        assertEquals(processTypes, rule.getProcessTypes());
        assertEquals(DelegationType.PARTIAL, rule.getDelegationType());
    }

    /**
     * 属性9: 不存在的委托规则操作应该抛出异常
     */
    @Test
    void operationOnNonexistentRuleShouldThrowException() {
        Long ruleId = 999L;
        String userId = "user_1";

        when(delegationRuleRepository.findById(ruleId))
                .thenReturn(Optional.empty());

        assertThrows(PortalException.class, () -> 
            delegationComponent.suspendDelegationRule(ruleId, userId));

        assertThrows(PortalException.class, () -> 
            delegationComponent.resumeDelegationRule(ruleId, userId));

        assertThrows(PortalException.class, () -> 
            delegationComponent.deleteDelegationRule(ruleId, userId));
    }

    /**
     * 属性10: 创建委托规则应该记录审计日志
     */
    @RepeatedTest(20)
    void creatingDelegationRuleShouldRecordAudit() {
        String delegatorId = "delegator_" + random.nextInt(1000);
        String delegateId = "delegate_" + random.nextInt(1000);

        DelegationRuleRequest request = DelegationRuleRequest.builder()
                .delegateId(delegateId)
                .delegationType(DelegationType.ALL)
                .reason("出差委托")
                .build();

        when(delegationRuleRepository.save(any(DelegationRule.class)))
                .thenAnswer(invocation -> {
                    DelegationRule rule = invocation.getArgument(0);
                    rule.setId(1L);
                    return rule;
                });

        delegationComponent.createDelegationRule(delegatorId, request);

        verify(delegationAuditRepository, times(1)).save(any());
    }
}
