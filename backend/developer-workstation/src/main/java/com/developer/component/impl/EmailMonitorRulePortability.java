package com.developer.component.impl;

import com.developer.entity.EmailMonitorRule;
import com.developer.entity.FunctionUnit;
import com.developer.enums.EmailMonitorActionType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.EmailMonitorRuleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Function Unit inbound email-monitor round-trip: ZIP import, version restore, and clone.
 * Templates ({@code sourceRuleId} and {@code startEventId} both empty) must be restored
 * before Start Event bindings so {@code sourceRuleId} can be remapped.
 */
@Component
@RequiredArgsConstructor
public class EmailMonitorRulePortability {

    private final EmailMonitorRuleRepository emailMonitorRuleRepository;
    private final ObjectMapper objectMapper;

    record MonitorImportMaps(
            Map<Long, Long> formIdMapping,
            Map<Long, Long> bindingIdMapping,
            Map<String, String> connectionUidMapping,
            Map<Long, Long> monitorRuleIdMapping) {

        static MonitorImportMaps of(
                Map<Long, Long> formIdMapping,
                Map<Long, Long> bindingIdMapping,
                Map<String, String> connectionUidMapping) {
            return new MonitorImportMaps(
                    formIdMapping, bindingIdMapping, connectionUidMapping, new HashMap<>());
        }
    }

    void importAll(FunctionUnit functionUnit,
                   List<Map<String, Object>> monitors,
                   MonitorImportMaps maps) {
        if (monitors == null || monitors.isEmpty()) {
            return;
        }
        Map<Long, Long> ruleIdMapping = maps.monitorRuleIdMapping();
        List<Map<String, Object>> templates = new ArrayList<>();
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (Map<String, Object> monitor : monitors) {
            if (isTemplate(monitor)) {
                templates.add(monitor);
            } else {
                bindings.add(monitor);
            }
        }
        for (Map<String, Object> templateData : templates) {
            EmailMonitorRule saved = importRule(functionUnit, templateData, maps);
            recordRuleId(templateData.get("ruleId"), saved.getId(), ruleIdMapping);
        }
        for (Map<String, Object> bindingData : bindings) {
            importRule(functionUnit, bindingData, maps);
        }
    }

    EmailMonitorRule importRule(FunctionUnit functionUnit,
                                Map<String, Object> ruleData,
                                MonitorImportMaps maps) {
        EmailMonitorRule rule = EmailMonitorRule.builder()
                .ruleUid(UUID.randomUUID().toString())
                .functionUnit(functionUnit)
                .name((String) ruleData.get("name"))
                .enabled(ruleData.get("enabled") instanceof Boolean enabledVal ? enabledVal : true)
                .connectionUid(mappedConnectionUid(ruleData, maps))
                .sourceRuleId(mappedSourceRuleId(ruleData, maps))
                .processDefinitionKey(functionUnit.getCode())
                .startEventId((String) ruleData.get("startEventId"))
                .folderLabel(ruleData.get("folderLabel") instanceof String folder ? folder : "INBOX")
                .filterFrom((String) ruleData.get("filterFrom"))
                .filterSubject((String) ruleData.get("filterSubject"))
                .actionType(ruleData.get("actionType") instanceof String actionTypeStr
                        ? EmailMonitorActionType.valueOf(actionTypeStr)
                        : EmailMonitorActionType.START_PROCESS)
                .targetFormId(mappedFormId(ruleData, maps, (String) ruleData.get("name")))
                .targetBindingId(mappedBindingId(ruleData.get("targetBindingId"), maps.bindingIdMapping()))
                .systemInitiatorUserId((String) ruleData.get("systemInitiatorUserId"))
                .extractionRules(parseJsonMap(ruleData.get("extractionRules")))
                .correlation(parseJsonMap(ruleData.get("correlation")))
                .pollIntervalSeconds(ruleData.get("pollIntervalSeconds") instanceof Number poll
                        ? poll.intValue() : 60)
                .reviewOnMissing(ruleData.get("reviewOnMissing") instanceof Boolean review ? review : true)
                .build();
        return emailMonitorRuleRepository.save(rule);
    }

    void cloneAll(Long sourceFunctionUnitId,
                  FunctionUnit target,
                  Map<Long, Long> formIdMapping,
                  Map<Long, Long> bindingIdMapping,
                  Map<String, String> connectionUidMapping) {
        List<EmailMonitorRule> sources = emailMonitorRuleRepository
                .findByFunctionUnitIdOrderByNameAsc(sourceFunctionUnitId);
        Map<Long, Long> ruleIdMapping = new HashMap<>();
        for (EmailMonitorRule source : sources) {
            if (isTemplate(source)) {
                EmailMonitorRule cloned = cloneOne(
                        source, target, formIdMapping, bindingIdMapping, connectionUidMapping, null);
                ruleIdMapping.put(source.getId(), cloned.getId());
            }
        }
        for (EmailMonitorRule source : sources) {
            if (!isTemplate(source)) {
                cloneOne(source, target, formIdMapping, bindingIdMapping, connectionUidMapping,
                        mappedCloneSourceRuleId(source, ruleIdMapping));
            }
        }
    }

    private EmailMonitorRule cloneOne(EmailMonitorRule source,
                                      FunctionUnit target,
                                      Map<Long, Long> formIdMapping,
                                      Map<Long, Long> bindingIdMapping,
                                      Map<String, String> connectionUidMapping,
                                      Long mappedSourceRuleId) {
        EmailMonitorRule cloned = EmailMonitorRule.builder()
                .ruleUid(UUID.randomUUID().toString())
                .functionUnit(target)
                .name(source.getName())
                .enabled(source.getEnabled())
                .connectionUid(mappedCloneConnectionUid(source, connectionUidMapping))
                .sourceRuleId(mappedSourceRuleId)
                .processDefinitionKey(target.getCode())
                .startEventId(source.getStartEventId())
                .folderLabel(source.getFolderLabel())
                .filterFrom(source.getFilterFrom())
                .filterSubject(source.getFilterSubject())
                .actionType(source.getActionType())
                .targetFormId(mappedCloneFormId(source, formIdMapping))
                .targetBindingId(mappedCloneBindingId(source, bindingIdMapping))
                .systemInitiatorUserId(source.getSystemInitiatorUserId())
                .extractionRules(deepCopyMap(source.getExtractionRules()))
                .correlation(deepCopyMap(source.getCorrelation()))
                .pollIntervalSeconds(source.getPollIntervalSeconds())
                .reviewOnMissing(source.getReviewOnMissing())
                .build();
        return emailMonitorRuleRepository.save(cloned);
    }

    static boolean isTemplate(Map<String, Object> ruleData) {
        return ruleData.get("sourceRuleId") == null && !hasText(ruleData.get("startEventId"));
    }

    static boolean isTemplate(EmailMonitorRule rule) {
        return rule.getSourceRuleId() == null && !hasText(rule.getStartEventId());
    }

    private static void recordRuleId(Object sourceIdObj, Long newId, Map<Long, Long> mapping) {
        if (sourceIdObj instanceof Number sourceId && newId != null) {
            mapping.put(sourceId.longValue(), newId);
        }
    }

    private static String mappedConnectionUid(Map<String, Object> ruleData, MonitorImportMaps maps) {
        String sourceUid = (String) ruleData.get("connectionUid");
        if (sourceUid == null || sourceUid.isBlank()) {
            return sourceUid;
        }
        String mapped = maps.connectionUidMapping().get(sourceUid);
        if (mapped == null) {
            throw new DeveloperBusinessException("IMPORT_EMAIL_MONITOR_UNMAPPED",
                    "Email monitor references connectionUid that was not imported: " + sourceUid);
        }
        return mapped;
    }

    private static Long mappedSourceRuleId(Map<String, Object> ruleData, MonitorImportMaps maps) {
        if (!(ruleData.get("sourceRuleId") instanceof Number sourceId)) {
            return null;
        }
        Long mapped = maps.monitorRuleIdMapping().get(sourceId.longValue());
        if (mapped != null) {
            return mapped;
        }
        if (maps.monitorRuleIdMapping().isEmpty()) {
            // FALLBACK(migration): packages exported before monitor templates were included
            // only contain Start Event bindings; sourceRuleId cannot be remapped.
            // Delete after environments re-export Function Units with templates.
            return null;
        }
        throw new DeveloperBusinessException("IMPORT_EMAIL_MONITOR_UNMAPPED",
                "Email monitor references sourceRuleId that was not imported: " + sourceId);
    }

    private static Long mappedFormId(Map<String, Object> ruleData,
                                     MonitorImportMaps maps,
                                     String monitorName) {
        if (!(ruleData.get("targetFormId") instanceof Number sourceFormId)) {
            return null;
        }
        Long mapped = maps.formIdMapping().get(sourceFormId.longValue());
        if (mapped == null) {
            throw new DeveloperBusinessException("IMPORT_EMAIL_MONITOR_UNMAPPED",
                    "Email monitor '" + monitorName
                            + "' references targetFormId that was not imported: " + sourceFormId);
        }
        return mapped;
    }

    private static String mappedBindingId(Object rawBindingId, Map<Long, Long> bindingIdMapping) {
        if (rawBindingId == null) {
            return null;
        }
        String raw = String.valueOf(rawBindingId);
        try {
            long oldId = Long.parseLong(raw);
            Long mapped = bindingIdMapping.get(oldId);
            if (mapped == null) {
                throw new DeveloperBusinessException("IMPORT_EMAIL_MONITOR_UNMAPPED",
                        "Email monitor references targetBindingId that was not imported: " + raw);
            }
            return String.valueOf(mapped);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String mappedCloneConnectionUid(EmailMonitorRule source,
                                                   Map<String, String> connectionUidMapping) {
        String sourceUid = source.getConnectionUid();
        if (sourceUid == null) {
            return null;
        }
        String mapped = connectionUidMapping.get(sourceUid);
        if (mapped == null) {
            throw new DeveloperBusinessException("CLONE_EMAIL_MONITOR_UNMAPPED",
                    "Email monitor '" + source.getName()
                            + "' references connectionUid that was not cloned: " + sourceUid);
        }
        return mapped;
    }

    private static Long mappedCloneSourceRuleId(EmailMonitorRule source, Map<Long, Long> ruleIdMapping) {
        Long sourceRuleId = source.getSourceRuleId();
        if (sourceRuleId == null) {
            return null;
        }
        Long mapped = ruleIdMapping.get(sourceRuleId);
        if (mapped == null) {
            throw new DeveloperBusinessException("CLONE_EMAIL_MONITOR_UNMAPPED",
                    "Email monitor '" + source.getName()
                            + "' references sourceRuleId that was not cloned: " + sourceRuleId);
        }
        return mapped;
    }

    private static Long mappedCloneFormId(EmailMonitorRule source, Map<Long, Long> formIdMapping) {
        Long sourceFormId = source.getTargetFormId();
        if (sourceFormId == null) {
            return null;
        }
        Long mapped = formIdMapping.get(sourceFormId);
        if (mapped == null) {
            throw new DeveloperBusinessException("CLONE_EMAIL_MONITOR_UNMAPPED",
                    "Email monitor '" + source.getName()
                            + "' references targetFormId that was not cloned: " + sourceFormId);
        }
        return mapped;
    }

    private static String mappedCloneBindingId(EmailMonitorRule source, Map<Long, Long> bindingIdMapping) {
        String targetBindingId = source.getTargetBindingId();
        if (targetBindingId == null) {
            return null;
        }
        try {
            long oldBindingId = Long.parseLong(targetBindingId);
            Long mappedBindingId = bindingIdMapping.get(oldBindingId);
            if (mappedBindingId == null) {
                throw new DeveloperBusinessException("CLONE_EMAIL_MONITOR_UNMAPPED",
                        "Email monitor '" + source.getName()
                                + "' references targetBindingId that was not cloned: " + targetBindingId);
            }
            return String.valueOf(mappedBindingId);
        } catch (NumberFormatException e) {
            return targetBindingId;
        }
    }

    private static boolean hasText(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        if (obj instanceof String s && !s.isBlank()) {
            try {
                return objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new DeveloperBusinessException("SYS_JSON_ERROR",
                        "Failed to parse JSON configuration during import: " + e.getMessage());
            }
        }
        return null;
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    objectMapper.writeValueAsString(source),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new DeveloperBusinessException("SYS_JSON_ERROR",
                    "Failed to deep copy JSON configuration during clone: " + e.getMessage());
        }
    }
}
