package com.portal.component;

import com.portal.dto.DelegationRuleRequest;
import com.portal.entity.DelegationAudit;
import com.portal.entity.DelegationRule;
import com.portal.enums.DelegateTargetType;
import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
import com.portal.exception.PortalException;
import com.portal.repository.DelegationAuditRepository;
import com.portal.repository.DelegationRuleRepository;
import com.platform.common.i18n.I18nService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 委托管理组件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelegationComponent {

    private final DelegationRuleRepository delegationRuleRepository;
    private final DelegationAuditRepository delegationAuditRepository;
    private final I18nService i18nService;

    /**
     * 创建委托规则
     */
    @Transactional
    public DelegationRule createDelegationRule(String delegatorId, DelegationRuleRequest request) {
        validateRuleRequest(delegatorId, request);
        if (request.effectiveTargetType() == DelegateTargetType.USER
                && hasCircularDelegation(delegatorId, request.getDelegateId().trim())) {
            throw new PortalException("400", i18nService.getMessage("portal.circular_delegation"));
        }

        DelegationRule rule = DelegationRule.builder()
                .delegatorId(delegatorId)
                .delegationType(request.getDelegationType())
                .processTypes(request.getProcessTypes())
                .priorityFilter(request.getPriorityFilter())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .reason(request.getReason())
                .status(DelegationStatus.ACTIVE)
                .build();
        applyTarget(rule, request);
        rule = delegationRuleRepository.save(rule);
        recordAudit(delegatorId, describeTarget(rule), null, "CREATE_DELEGATION", "SUCCESS", request.getReason());
        log.info("用户 {} 创建了委托规则给 {}", delegatorId, describeTarget(rule));
        return rule;
    }

    /**
     * 更新委托规则
     */
    @Transactional
    public DelegationRule updateDelegationRule(Long ruleId, String delegatorId, DelegationRuleRequest request) {
        DelegationRule rule = delegationRuleRepository.findById(ruleId)
                .orElseThrow(() -> new PortalException("404", i18nService.getMessage("portal.delegation_not_found")));

        // 验证是否是委托人
        if (!delegatorId.equals(rule.getDelegatorId())) {
            throw new PortalException("403", i18nService.getMessage("portal.only_delegator_modify"));
        }
        validateRuleRequest(delegatorId, request);
        if (request.effectiveTargetType() == DelegateTargetType.USER
                && hasCircularDelegation(delegatorId, request.getDelegateId().trim())) {
            throw new PortalException("400", i18nService.getMessage("portal.circular_delegation"));
        }

        applyTarget(rule, request);
        rule.setDelegationType(request.getDelegationType());
        rule.setProcessTypes(request.getProcessTypes());
        rule.setPriorityFilter(request.getPriorityFilter());
        rule.setStartTime(request.getStartTime());
        rule.setEndTime(request.getEndTime());
        rule.setReason(request.getReason());

        rule = delegationRuleRepository.save(rule);

        // 记录审计日志
        recordAudit(delegatorId, describeTarget(rule), null, "UPDATE_DELEGATION", "SUCCESS", request.getReason());

        log.info("用户 {} 更新了委托规则 {}", delegatorId, ruleId);
        return rule;
    }

    /**
     * 删除委托规则
     */
    @Transactional
    public void deleteDelegationRule(Long ruleId, String delegatorId) {
        DelegationRule rule = delegationRuleRepository.findById(ruleId)
                .orElseThrow(() -> new PortalException("404", i18nService.getMessage("portal.delegation_not_found")));

        // 验证是否是委托人
        if (!delegatorId.equals(rule.getDelegatorId())) {
            throw new PortalException("403", i18nService.getMessage("portal.only_delegator_delete"));
        }

        delegationRuleRepository.delete(rule);

        // 记录审计日志
        recordAudit(delegatorId, describeTarget(rule), null, "DELETE_DELEGATION", "SUCCESS", null);

        log.info("用户 {} 删除了委托规则 {}", delegatorId, ruleId);
    }

    /**
     * 暂停委托规则
     */
    @Transactional
    public DelegationRule suspendDelegationRule(Long ruleId, String delegatorId) {
        DelegationRule rule = delegationRuleRepository.findById(ruleId)
                .orElseThrow(() -> new PortalException("404", i18nService.getMessage("portal.delegation_not_found")));

        if (!delegatorId.equals(rule.getDelegatorId())) {
            throw new PortalException("403", i18nService.getMessage("portal.only_delegator_suspend"));
        }

        rule.setStatus(DelegationStatus.SUSPENDED);
        rule = delegationRuleRepository.save(rule);

        recordAudit(delegatorId, describeTarget(rule), null, "SUSPEND_DELEGATION", "SUCCESS", null);

        log.info("用户 {} 暂停了委托规则 {}", delegatorId, ruleId);
        return rule;
    }

    /**
     * 恢复委托规则
     */
    @Transactional
    public DelegationRule resumeDelegationRule(Long ruleId, String delegatorId) {
        DelegationRule rule = delegationRuleRepository.findById(ruleId)
                .orElseThrow(() -> new PortalException("404", i18nService.getMessage("portal.delegation_not_found")));

        if (!delegatorId.equals(rule.getDelegatorId())) {
            throw new PortalException("403", i18nService.getMessage("portal.only_delegator_resume"));
        }

        rule.setStatus(DelegationStatus.ACTIVE);
        rule = delegationRuleRepository.save(rule);

        recordAudit(delegatorId, describeTarget(rule), null, "RESUME_DELEGATION", "SUCCESS", null);

        log.info("用户 {} 恢复了委托规则 {}", delegatorId, ruleId);
        return rule;
    }

    /**
     * 获取用户的委托规则列表
     */
    public List<DelegationRule> getDelegationRules(String delegatorId) {
        return delegationRuleRepository.findByDelegatorId(delegatorId);
    }

    /**
     * 获取用户的有效委托规则
     */
    public List<DelegationRule> getActiveDelegationRules(String delegatorId) {
        return delegationRuleRepository.findActiveDelegationRules(delegatorId, LocalDateTime.now());
    }

    /**
     * 获取委托给用户的规则
     */
    public List<DelegationRule> getDelegationsForDelegate(String delegateId) {
        return delegationRuleRepository.findActiveDelegationsForDelegate(delegateId, LocalDateTime.now());
    }

    /**
     * 获取委托审计记录
     */
    public Page<DelegationAudit> getDelegationAuditRecords(String userId, Pageable pageable) {
        return delegationAuditRepository.findByDelegatorIdOrDelegateIdOrderByCreatedAtDesc(userId, userId, pageable);
    }

    /**
     * 获取代理任务的委托人列表
     * 返回当前用户作为代理人时，所有委托人的ID列表
     */
    public List<String> getDelegatorIds(String delegateId) {
        List<DelegationRule> rules = delegationRuleRepository
                .findActiveDelegationsForDelegate(delegateId, LocalDateTime.now());
        return rules.stream()
                .map(DelegationRule::getDelegatorId)
                .distinct()
                .toList();
    }

    /**
     * 记录代理任务处理审计
     */
    @Transactional
    public void recordDelegateTaskProcess(String delegatorId, String delegateId, 
                                          String taskId, String operationType, 
                                          String result, String detail) {
        recordAudit(delegatorId, delegateId, taskId, operationType, result, detail);
        log.info("代理人 {} 代理 {} 处理任务 {}, 操作: {}, 结果: {}", 
                delegateId, delegatorId, taskId, operationType, result);
    }

    /**
     * 获取指定委托人的有效委托规则
     */
    public DelegationRule getActiveDelegationRule(String delegatorId, String delegateId) {
        List<DelegationRule> rules = delegationRuleRepository
                .findActiveDelegationsForDelegate(delegateId, LocalDateTime.now());
        return rules.stream()
                .filter(r -> r.getDelegatorId().equals(delegatorId))
                .findFirst()
                .orElse(null);
    }

    private void validateRuleRequest(String delegatorId, DelegationRuleRequest request) {
        if (request == null || request.getDelegationType() == null) {
            throw new PortalException("400", i18nService.getMessage("portal.delegation_type_required"));
        }
        if (request.getDelegationType() == DelegationType.PARTIAL
                && (request.getProcessTypes() == null || request.getProcessTypes().stream()
                        .noneMatch(v -> v != null && !v.isBlank()))) {
            throw new PortalException("400", i18nService.getMessage("portal.delegation_partial_process_types_required"));
        }
        if (request.getDelegationType() == DelegationType.TEMPORARY
                && (request.getStartTime() == null || request.getEndTime() == null)) {
            throw new PortalException("400", i18nService.getMessage("portal.delegation_temporary_window_required"));
        }
        LocalDateTime now = LocalDateTime.now();
        if (request.getStartTime() != null && request.getStartTime().isBefore(now)) {
            throw new PortalException("400", i18nService.getMessage("portal.delegation_time_in_past"));
        }
        if (request.getEndTime() != null && request.getEndTime().isBefore(now)) {
            throw new PortalException("400", i18nService.getMessage("portal.delegation_time_in_past"));
        }
        if (request.isBuRoleTarget()) {
            if (blank(request.getDelegateBuCode()) || blank(request.getDelegateRoleCode())) {
                throw new PortalException("400", i18nService.getMessage("portal.delegation_bu_role_pair_required"));
            }
            return;
        }
        if (blank(request.getDelegateId())) {
            throw new PortalException("400", i18nService.getMessage("portal.delegation_delegate_required"));
        }
        if (delegatorId.equals(request.getDelegateId().trim())) {
            throw new PortalException("400", i18nService.getMessage("portal.cannot_delegate_self"));
        }
    }

    private static void applyTarget(DelegationRule rule, DelegationRuleRequest request) {
        rule.setDelegateTargetType(request.effectiveTargetType());
        if (request.isBuRoleTarget()) {
            rule.setDelegateId(null);
            rule.setDelegateBuCode(request.getDelegateBuCode().trim());
            rule.setDelegateRoleCode(request.getDelegateRoleCode().trim());
            return;
        }
        rule.setDelegateId(request.getDelegateId().trim());
        rule.setDelegateBuCode(null);
        rule.setDelegateRoleCode(null);
    }

    private static String describeTarget(DelegationRule rule) {
        if (rule.isBuRoleTarget()) {
            return rule.getDelegateBuCode() + "/" + rule.getDelegateRoleCode();
        }
        return rule.getDelegateId();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 检查是否存在循环委托
     */
    private boolean hasCircularDelegation(String delegatorId, String delegateId) {
        // 检查被委托人是否已经委托给了委托人
        List<DelegationRule> delegateRules = delegationRuleRepository
                .findActiveDelegationRules(delegateId, LocalDateTime.now());
        
        for (DelegationRule rule : delegateRules) {
            if (!rule.isUserTarget() || rule.getDelegateId() == null) {
                continue;
            }
            if (rule.getDelegateId().equals(delegatorId)) {
                return true;
            }
            List<DelegationRule> subRules = delegationRuleRepository
                    .findActiveDelegationRules(rule.getDelegateId(), LocalDateTime.now());
            for (DelegationRule subRule : subRules) {
                if (subRule.isUserTarget() && delegatorId.equals(subRule.getDelegateId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 记录审计日志
     */
    private void recordAudit(String delegatorId, String delegateId, String taskId, 
                            String operationType, String result, String detail) {
        DelegationAudit audit = DelegationAudit.builder()
                .delegatorId(delegatorId)
                .delegateId(delegateId)
                .taskId(taskId)
                .operationType(operationType)
                .operationResult(result)
                .operationDetail(detail)
                .build();
        delegationAuditRepository.save(audit);
    }
}
