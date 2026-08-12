package com.portal.component;

import com.portal.dto.TaskInfo;
import com.portal.entity.DelegationRule;
import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single place for standing-rule match logic (query / canProcess / complete).
 * Do not duplicate type/window/process/priority checks elsewhere.
 */
@Component
public class DelegationRuleMatcher {

    /** Priority values treated as urgent for {@link DelegationType#URGENT}. */
    private static final Set<String> URGENT_PRIORITIES = Set.of("URGENT", "CRITICAL");

    public boolean matches(TaskInfo task, DelegationRule rule) {
        return matches(task, rule, LocalDateTime.now());
    }

    public boolean matches(TaskInfo task, DelegationRule rule, LocalDateTime now) {
        if (task == null || rule == null || now == null) {
            return false;
        }
        if (rule.getStatus() != DelegationStatus.ACTIVE) {
            return false;
        }
        if (rule.getStartTime() != null && now.isBefore(rule.getStartTime())) {
            return false;
        }
        if (rule.getEndTime() != null && now.isAfter(rule.getEndTime())) {
            return false;
        }

        DelegationType type = rule.getDelegationType();
        if (type == null) {
            return false;
        }

        return switch (type) {
            case ALL, TEMPORARY -> matchesPriorityFilter(task, rule);
            case PARTIAL -> matchesProcessTypes(task, rule) && matchesPriorityFilter(task, rule);
            case URGENT -> isUrgentPriority(task.getPriority()) && matchesPriorityFilter(task, rule);
        };
    }

    /**
     * Whether any active rule for the given delegator allows the delegate to act on the task.
     */
    public boolean anyMatch(TaskInfo task, String delegatorId, List<DelegationRule> rulesForDelegate) {
        if (task == null || delegatorId == null || rulesForDelegate == null || rulesForDelegate.isEmpty()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        for (DelegationRule rule : rulesForDelegate) {
            if (!delegatorId.equals(rule.getDelegatorId())) {
                continue;
            }
            if (matches(task, rule, now)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesProcessTypes(TaskInfo task, DelegationRule rule) {
        List<String> processTypes = rule.getProcessTypes();
        if (processTypes == null || processTypes.isEmpty()) {
            return false;
        }
        String key = task.getProcessDefinitionKey();
        if (key == null || key.isBlank()) {
            return false;
        }
        for (String allowed : processTypes) {
            if (allowed != null && allowed.equalsIgnoreCase(key.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPriorityFilter(TaskInfo task, DelegationRule rule) {
        List<String> filter = rule.getPriorityFilter();
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        String priority = normalizePriority(task.getPriority());
        if (priority == null) {
            return false;
        }
        for (String allowed : filter) {
            if (allowed != null && priority.equals(normalizePriority(allowed))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUrgentPriority(String priority) {
        String p = normalizePriority(priority);
        return p != null && URGENT_PRIORITIES.contains(p);
    }

    private static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return null;
        }
        return priority.trim().toUpperCase(Locale.ROOT);
    }
}
