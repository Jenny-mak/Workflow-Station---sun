package com.portal.component;

import com.platform.security.entity.User;
import com.platform.security.repository.UserRepository;
import com.portal.dto.TaskInfo;
import com.portal.entity.DelegationAudit;
import com.portal.entity.DelegationRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fills transient display-name fields on delegation list rows. Filters still
 * bind to stored user ids ({@code Kind.USER}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelegationUserDisplayEnricher {

    private final UserRepository userRepository;

    public void enrichRules(List<DelegationRule> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (DelegationRule row : rows) {
            if (row.isUserTarget()) {
                collectUserId(ids, row.getDelegateId());
            }
        }
        Map<String, String> names = lookup(ids);
        for (DelegationRule row : rows) {
            if (!row.isUserTarget()) {
                continue;
            }
            row.setDelegateDisplayName(names.get(trimToNull(row.getDelegateId())));
        }
    }

    public void enrichAudit(List<DelegationAudit> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (DelegationAudit row : rows) {
            collectUserId(ids, row.getDelegatorId());
            collectUserId(ids, row.getDelegateId());
        }
        Map<String, String> names = lookup(ids);
        for (DelegationAudit row : rows) {
            row.setDelegatorDisplayName(names.get(trimToNull(row.getDelegatorId())));
            row.setDelegateDisplayName(names.get(trimToNull(row.getDelegateId())));
        }
    }

    public void enrichDelegatedTasks(List<TaskInfo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (TaskInfo row : rows) {
            collectUserId(ids, row.getDelegatorId());
        }
        Map<String, String> names = lookup(ids);
        for (TaskInfo row : rows) {
            String name = names.get(trimToNull(row.getDelegatorId()));
            if (name != null) {
                row.setDelegatorName(name);
            }
        }
    }

    private Map<String, String> lookup(Set<String> userIds) {
        Map<String, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        try {
            userRepository.findAllById(userIds).forEach(user -> {
                if (user != null && user.getId() != null) {
                    names.put(user.getId(), displayNameForUser(user));
                }
            });
        } catch (RuntimeException e) {
            // FALLBACK(ux): list cells keep stored ids when sys_users lookup fails
            log.warn("delegation user display lookup failed: {}", e.getMessage());
        }
        return names;
    }

    private static void collectUserId(Set<String> ids, String raw) {
        String id = trimToNull(raw);
        if (id == null || id.indexOf('/') >= 0) {
            return;
        }
        ids.add(id);
    }

    static String displayNameForUser(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName().trim();
        }
        if (user.getFullName() != null && !user.getFullName().isBlank()) {
            return user.getFullName().trim();
        }
        return user.getUsername();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
