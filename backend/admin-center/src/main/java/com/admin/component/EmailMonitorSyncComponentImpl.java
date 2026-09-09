package com.admin.component;

import com.admin.entity.EmailMonitorRule;
import com.admin.entity.FunctionUnit;
import com.admin.repository.EmailMonitorRuleRepository;
import com.admin.repository.FunctionUnitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailMonitorSyncComponentImpl implements EmailMonitorSyncComponent {

    private final EmailMonitorRuleRepository emailMonitorRuleRepository;
    private final FunctionUnitRepository functionUnitRepository;

    @Override
    @Transactional
    public void syncMonitorRules(String functionUnitId, List<Map<String, Object>> monitorRules) {
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new IllegalArgumentException("Function unit not found: " + functionUnitId));

        Map<String, EmailMonitorRule> existingById = emailMonitorRuleRepository.findByFunctionUnitId(functionUnitId)
                .stream()
                .collect(Collectors.toMap(EmailMonitorRule::getId, rule -> rule, (left, right) -> left));

        if (monitorRules == null || monitorRules.isEmpty()) {
            emailMonitorRuleRepository.deleteByFunctionUnitId(functionUnitId);
            deleteSupersededSiblingRules(functionUnit, Set.of());
            log.info("No email monitor rules to sync for function unit {}", functionUnitId);
            return;
        }

        Set<String> syncedIds = new HashSet<>();
        int synced = 0;
        for (Map<String, Object> rule : monitorRules) {
            if (!hasStartEvent(rule)) {
                continue;
            }
            EmailMonitorRule entity = toEntity(functionUnit, rule);
            EmailMonitorRule previous = resolvePreviousRule(existingById, entity.getId());
            if (previous != null) {
                entity.setLastSyncCursor(previous.getLastSyncCursor());
                entity.setLastSyncedAt(previous.getLastSyncedAt());
            }
            emailMonitorRuleRepository.save(entity);
            syncedIds.add(entity.getId());
            synced++;
        }

        for (EmailMonitorRule stale : existingById.values()) {
            if (!syncedIds.contains(stale.getId())) {
                emailMonitorRuleRepository.delete(stale);
            }
        }
        deleteSupersededSiblingRules(functionUnit, syncedIds);
        log.info("Synced {} email monitor runtime bindings for function unit {}", synced, functionUnitId);
    }

    /**
     * Re-import/deploy creates a new catalog UUID for the same FU code. Old rows on
     * previous catalog ids stay enabled and would poll the same mailbox in parallel.
     */
    private void deleteSupersededSiblingRules(FunctionUnit current, Set<String> keepIds) {
        String code = current.getCode();
        if (code == null || code.isBlank()) {
            return;
        }
        List<FunctionUnit> versions = functionUnitRepository.findByCodeOrderByVersionDesc(code);
        for (FunctionUnit version : versions) {
            if (version.getId().equals(current.getId())) {
                continue;
            }
            for (EmailMonitorRule leftover : emailMonitorRuleRepository.findByFunctionUnitId(version.getId())) {
                if (!keepIds.contains(leftover.getId())) {
                    emailMonitorRuleRepository.delete(leftover);
                    log.info("Removed superseded email monitor {} from previous catalog {}",
                            leftover.getId(), version.getId());
                }
            }
        }
    }

    private EmailMonitorRule resolvePreviousRule(Map<String, EmailMonitorRule> existingById, String ruleUid) {
        EmailMonitorRule previous = existingById.get(ruleUid);
        if (previous != null) {
            return previous;
        }
        return emailMonitorRuleRepository.findById(ruleUid).orElse(null);
    }

    private static boolean hasStartEvent(Map<String, Object> rule) {
        Object startEventId = rule.get("startEventId");
        return startEventId instanceof String text && !text.isBlank();
    }

    private EmailMonitorRule toEntity(FunctionUnit functionUnit, Map<String, Object> rule) {
        String ruleUid = rule.get("ruleUid") != null
                ? String.valueOf(rule.get("ruleUid")) : UUID.randomUUID().toString();
        return EmailMonitorRule.builder()
                .id(ruleUid)
                .functionUnit(functionUnit)
                .name((String) rule.get("name"))
                .enabled(rule.get("enabled") == null || Boolean.TRUE.equals(rule.get("enabled")))
                .connectionUid((String) rule.get("connectionUid"))
                .processDefinitionKey((String) rule.get("processDefinitionKey"))
                .startEventId((String) rule.get("startEventId"))
                .folderLabel(rule.get("folderLabel") != null ? (String) rule.get("folderLabel") : "INBOX")
                .filterFrom((String) rule.get("filterFrom"))
                .filterSubject((String) rule.get("filterSubject"))
                .actionType(rule.get("actionType") != null ? (String) rule.get("actionType") : "START_PROCESS")
                .targetFormId(rule.get("targetFormId") != null ? String.valueOf(rule.get("targetFormId")) : null)
                .targetBindingId((String) rule.get("targetBindingId"))
                .systemInitiatorUserId((String) rule.get("systemInitiatorUserId"))
                .extractionRules(asMap(rule.get("extractionRules")))
                .correlation(asMap(rule.get("correlation")))
                .pollIntervalSeconds(rule.get("pollIntervalSeconds") != null
                        ? ((Number) rule.get("pollIntervalSeconds")).intValue() : 60)
                .reviewOnMissing(rule.get("reviewOnMissing") == null || Boolean.TRUE.equals(rule.get("reviewOnMissing")))
                .syncedAt(Instant.now())
                .build();
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return null;
    }
}
