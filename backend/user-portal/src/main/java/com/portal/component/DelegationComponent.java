package com.portal.component;

import com.portal.dto.DelegationRuleRequest;
import com.portal.dto.PageResponse;
import com.portal.entity.DelegationAudit;
import com.portal.entity.DelegationRule;
import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
import com.portal.exception.PortalException;
import com.portal.repository.DelegationAuditRepository;
import com.portal.repository.DelegationRuleRepository;
import com.portal.util.DelegationAuditListSpec;
import com.portal.util.DelegationRuleListSpec;
import com.portal.util.PortalColumnFilterSupport;
import com.platform.common.i18n.I18nService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    private final EntityManager entityManager;

    /**
     * 创建委托规则
     */
    @Transactional
    public DelegationRule createDelegationRule(String delegatorId, DelegationRuleRequest request) {
        // 验证不能委托给自己
        if (delegatorId.equals(request.getDelegateId())) {
            throw new PortalException("400", i18nService.getMessage("portal.cannot_delegate_self"));
        }

        // 检查是否存在循环委托
        if (hasCircularDelegation(delegatorId, request.getDelegateId())) {
            throw new PortalException("400", i18nService.getMessage("portal.circular_delegation"));
        }

        validateTypeFields(request);

        DelegationRule rule = DelegationRule.builder()
                .delegatorId(delegatorId)
                .delegateId(request.getDelegateId())
                .delegationType(request.getDelegationType())
                .processTypes(request.getProcessTypes())
                .priorityFilter(request.getPriorityFilter())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .reason(request.getReason())
                .status(DelegationStatus.ACTIVE)
                .build();

        rule = delegationRuleRepository.save(rule);

        // 记录审计日志
        recordAudit(delegatorId, request.getDelegateId(), null, "CREATE_DELEGATION", "SUCCESS", request.getReason());

        log.info("用户 {} 创建了委托规则给 {}", delegatorId, request.getDelegateId());
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

        validateTypeFields(request);

        rule.setDelegateId(request.getDelegateId());
        rule.setDelegationType(request.getDelegationType());
        rule.setProcessTypes(request.getProcessTypes());
        rule.setPriorityFilter(request.getPriorityFilter());
        rule.setStartTime(request.getStartTime());
        rule.setEndTime(request.getEndTime());
        rule.setReason(request.getReason());

        rule = delegationRuleRepository.save(rule);

        // 记录审计日志
        recordAudit(delegatorId, request.getDelegateId(), null, "UPDATE_DELEGATION", "SUCCESS", request.getReason());

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
        recordAudit(delegatorId, rule.getDelegateId(), null, "DELETE_DELEGATION", "SUCCESS", null);

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

        recordAudit(delegatorId, rule.getDelegateId(), null, "SUSPEND_DELEGATION", "SUCCESS", null);

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

        recordAudit(delegatorId, rule.getDelegateId(), null, "RESUME_DELEGATION", "SUCCESS", null);

        log.info("用户 {} 恢复了委托规则 {}", delegatorId, ruleId);
        return rule;
    }

    /**
     * 获取用户的委托规则列表（全量，兼容旧客户端）
     */
    public List<DelegationRule> getDelegationRules(String delegatorId) {
        return delegationRuleRepository.findByDelegatorId(delegatorId);
    }

    /**
     * 分页获取用户的委托规则（0-based page）。
     */
    public Page<DelegationRule> getDelegationRules(String delegatorId, Pageable pageable) {
        return getDelegationRules(delegatorId, pageable, null, null, null, null).page();
    }

    /**
     * Paged delegation rules with optional column filters / sort / groupBy.
     */
    public DelegationListResult getDelegationRules(
            String delegatorId,
            Pageable pageable,
            Map<String, Map<String, Object>> filters,
            String sortField,
            String sortDirection,
            String groupBy) {
        String safeGroupBy = DelegationRuleListSpec.sanitizeGroupBy(groupBy);
        var columnFilters = DelegationRuleListSpec.parseFilters(filters);
        Specification<DelegationRule> spec = DelegationRuleListSpec.build(delegatorId, columnFilters);
        Pageable sorted = DelegationRuleListSpec.withSort(pageable, sortField, sortDirection, safeGroupBy);
        Page<DelegationRule> page = delegationRuleRepository.findAll(spec, sorted);
        Map<String, Long> groupCounts = safeGroupBy != null
                ? PortalColumnFilterSupport.computeGroupCounts(
                        entityManager, DelegationRule.class, spec, safeGroupBy)
                : null;
        return new DelegationListResult(page, groupCounts);
    }

    public record DelegationListResult(Page<DelegationRule> page, Map<String, Long> groupCounts) {
        public PageResponse<DelegationRule> toPageResponse() {
            PageResponse<DelegationRule> response = PageResponse.of(page);
            if (groupCounts != null) {
                response.setGroupCounts(groupCounts);
            }
            return response;
        }
    }

    /**
     * 获取用户的有效委托规则
     */
    public List<DelegationRule> getActiveDelegationRules(String delegatorId) {
        return delegationRuleRepository.findActiveDelegationRules(delegatorId, LocalDateTime.now());
    }

    /**
     * 获取委托审计记录
     */
    public Page<DelegationAudit> getDelegationAuditRecords(String userId, Pageable pageable) {
        return getDelegationAuditRecords(userId, pageable, null, null, null, null).page();
    }

    /**
     * Delegation audit with optional column filters / sort / groupBy.
     */
    public DelegationAuditListResult getDelegationAuditRecords(
            String userId,
            Pageable pageable,
            Map<String, Map<String, Object>> filters,
            String sortField,
            String sortDirection,
            String groupBy) {
        String safeGroupBy = DelegationAuditListSpec.sanitizeGroupBy(groupBy);
        var columnFilters = DelegationAuditListSpec.parseFilters(filters);
        Specification<DelegationAudit> spec = DelegationAuditListSpec.build(userId, columnFilters);
        Pageable sorted = DelegationAuditListSpec.withSort(pageable, sortField, sortDirection, safeGroupBy);
        Page<DelegationAudit> page = delegationAuditRepository.findAll(spec, sorted);
        Map<String, Long> groupCounts = safeGroupBy != null
                ? PortalColumnFilterSupport.computeGroupCounts(
                        entityManager, DelegationAudit.class, spec, safeGroupBy)
                : null;
        return new DelegationAuditListResult(page, groupCounts);
    }

    public record DelegationAuditListResult(Page<DelegationAudit> page, Map<String, Long> groupCounts) {
        public PageResponse<DelegationAudit> toPageResponse() {
            PageResponse<DelegationAudit> response = PageResponse.of(page);
            if (groupCounts != null) {
                response.setGroupCounts(groupCounts);
            }
            return response;
        }
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

    /**
     * Type-specific field gates (PARTIAL process_types; TEMPORARY start/end; time range).
     */
    private void validateTypeFields(DelegationRuleRequest request) {
        DelegationType type = request.getDelegationType();
        if (type == null) {
            throw new PortalException("400", i18nService.getMessage("portal.delegation_type_required"));
        }
        if (type == DelegationType.PARTIAL) {
            List<String> processTypes = request.getProcessTypes();
            boolean hasProcess = processTypes != null && processTypes.stream()
                    .anyMatch(p -> p != null && !p.isBlank());
            if (!hasProcess) {
                throw new PortalException("400",
                        i18nService.getMessage("portal.delegation_partial_process_types_required"));
            }
        }
        if (type == DelegationType.TEMPORARY) {
            if (request.getStartTime() == null || request.getEndTime() == null) {
                throw new PortalException("400",
                        i18nService.getMessage("portal.delegation_temporary_time_required"));
            }
        }
        LocalDateTime start = request.getStartTime();
        LocalDateTime end = request.getEndTime();
        if (start != null && end != null && !end.isAfter(start)) {
            throw new PortalException("400",
                    i18nService.getMessage("portal.delegation_invalid_time_range"));
        }
        if ((start == null) != (end == null)) {
            throw new PortalException("400",
                    i18nService.getMessage("portal.delegation_invalid_time_range"));
        }
    }

    /**
     * 检查是否存在循环委托
     */
    private boolean hasCircularDelegation(String delegatorId, String delegateId) {
        // 检查被委托人是否已经委托给了委托人
        List<DelegationRule> delegateRules = delegationRuleRepository
                .findActiveDelegationRules(delegateId, LocalDateTime.now());
        
        for (DelegationRule rule : delegateRules) {
            if (rule.getDelegateId().equals(delegatorId)) {
                return true;
            }
            // 递归检查（限制深度为2级）
            List<DelegationRule> subRules = delegationRuleRepository
                    .findActiveDelegationRules(rule.getDelegateId(), LocalDateTime.now());
            for (DelegationRule subRule : subRules) {
                if (subRule.getDelegateId().equals(delegatorId)) {
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
