package com.developer.component.impl;

import com.developer.component.EmailMonitorRuleComponent;
import com.developer.dto.EmailMonitorRuleRequest;
import com.developer.dto.EmailMonitorRuleResponse;
import com.developer.dto.EmailMonitorStartEventBindRequest;
import com.developer.entity.EmailConnection;
import com.developer.entity.EmailMonitorRule;
import com.developer.entity.FunctionUnit;
import com.developer.enums.EmailConnectionDirection;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.FunctionUnitRepository;
import com.platform.common.i18n.I18nService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class EmailMonitorRuleComponentImpl implements EmailMonitorRuleComponent {

    private final EmailMonitorRuleRepository emailMonitorRuleRepository;
    private final EmailConnectionRepository emailConnectionRepository;
    private final FunctionUnitRepository functionUnitRepository;
    private final I18nService i18nService;

    @Override
    @Transactional(readOnly = true)
    public List<EmailMonitorRuleResponse> listTemplates(Long functionUnitId) {
        ensureFunctionUnitExists(functionUnitId);
        return emailMonitorRuleRepository
                .findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(functionUnitId)
                .stream()
                .map(EmailMonitorRuleResponse::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmailMonitorRuleResponse> listByFunctionUnitId(Long functionUnitId) {
        ensureFunctionUnitExists(functionUnitId);
        return emailMonitorRuleRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId).stream()
                .map(EmailMonitorRuleResponse::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EmailMonitorRuleResponse getById(Long functionUnitId, Long ruleId) {
        return EmailMonitorRuleResponse.fromEntity(getEntity(functionUnitId, ruleId));
    }

    @Override
    @Transactional(readOnly = true)
    public EmailMonitorRuleResponse getByStartEventId(Long functionUnitId, String startEventId) {
        ensureFunctionUnitExists(functionUnitId);
        if (!StringUtils.hasText(startEventId)) {
            throw new DeveloperBusinessException("VALIDATION_START_EVENT_REQUIRED", "Start event id is required");
        }
        EmailMonitorRule rule = emailMonitorRuleRepository
                .findByFunctionUnitIdAndStartEventId(functionUnitId, startEventId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("EmailMonitorRule", startEventId));
        return EmailMonitorRuleResponse.fromEntity(rule);
    }

    @Override
    @Transactional
    public EmailMonitorRuleResponse create(Long functionUnitId, EmailMonitorRuleRequest request) {
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", functionUnitId));

        rejectTemplatePollution(request);
        if (emailMonitorRuleRepository.existsByFunctionUnitIdAndName(functionUnitId, request.getName())) {
            throw new DeveloperBusinessException("CONFLICT_RULE_NAME",
                    i18nService.getMessage("email.monitor.name_conflict", request.getName()));
        }
        validateConnection(functionUnitId, request.getConnectionUid());

        EmailMonitorRule rule = EmailMonitorRule.builder()
                .ruleUid(UUID.randomUUID().toString())
                .functionUnit(functionUnit)
                .build();
        applyTemplate(rule, request);
        return EmailMonitorRuleResponse.fromEntity(emailMonitorRuleRepository.save(rule));
    }

    @Override
    @Transactional
    public EmailMonitorRuleResponse update(Long functionUnitId, Long ruleId, EmailMonitorRuleRequest request) {
        EmailMonitorRule rule = getTemplateEntity(functionUnitId, ruleId);

        if (emailMonitorRuleRepository.existsByFunctionUnitIdAndNameAndIdNot(
                functionUnitId, request.getName(), ruleId)) {
            throw new DeveloperBusinessException("CONFLICT_RULE_NAME",
                    i18nService.getMessage("email.monitor.name_conflict", request.getName()));
        }
        rejectTemplatePollution(request);
        validateConnection(functionUnitId, request.getConnectionUid());

        applyTemplate(rule, request);
        EmailMonitorRule saved = emailMonitorRuleRepository.save(rule);
        refreshBindingsFromTemplate(saved);
        return EmailMonitorRuleResponse.fromEntity(saved);
    }

    @Override
    @Transactional
    public void delete(Long functionUnitId, Long ruleId) {
        EmailMonitorRule rule = getTemplateEntity(functionUnitId, ruleId);
        if (!emailMonitorRuleRepository.findBySourceRuleId(ruleId).isEmpty()) {
            throw new DeveloperBusinessException("CONFLICT_MONITOR_IN_USE",
                    i18nService.getMessage("email.monitor.template_in_use"));
        }
        emailMonitorRuleRepository.delete(rule);
    }

    @Override
    @Transactional
    public EmailMonitorRuleResponse bindStartEvent(Long functionUnitId, EmailMonitorStartEventBindRequest request) {
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", functionUnitId));
        EmailMonitorRule template = getTemplateEntity(functionUnitId, request.getTemplateRuleId());
        validateConnection(functionUnitId, template.getConnectionUid());

        String startEventId = request.getStartEventId().trim();
        String bindingName = bindingName(template.getName(), startEventId);

        EmailMonitorRule binding = emailMonitorRuleRepository
                .findByFunctionUnitIdAndStartEventId(functionUnitId, startEventId)
                .orElse(null);

        if (binding == null) {
            if (emailMonitorRuleRepository.existsByFunctionUnitIdAndName(functionUnitId, bindingName)) {
                throw new DeveloperBusinessException("CONFLICT_RULE_NAME",
                        i18nService.getMessage("email.monitor.name_conflict", bindingName));
            }
            binding = EmailMonitorRule.builder()
                    .ruleUid(UUID.randomUUID().toString())
                    .functionUnit(functionUnit)
                    .name(bindingName)
                    .build();
        } else if (binding.getSourceRuleId() == null) {
            // Legacy monolithic rule: convert in place to binding referencing the selected template.
            binding.setSourceRuleId(template.getId());
        } else if (!template.getId().equals(binding.getSourceRuleId())) {
            copyTemplateFields(binding, template);
            binding.setSourceRuleId(template.getId());
        }

        binding.setSourceRuleId(template.getId());
        copyTemplateFields(binding, template);
        binding.setStartEventId(startEventId);
        binding.setProcessDefinitionKey(request.getProcessDefinitionKey().trim());
        binding.setFilterFrom(trimToNull(request.getFilterFrom()));
        binding.setFilterSubject(trimToNull(request.getFilterSubject()));
        binding.setEnabled(request.getEnabled() == null || Boolean.TRUE.equals(request.getEnabled()));

        validateStartEventBinding(functionUnitId, startEventId, binding.getId());
        return EmailMonitorRuleResponse.fromEntity(emailMonitorRuleRepository.save(binding));
    }

    @Override
    @Transactional
    public void unbindStartEvent(Long functionUnitId, String startEventId) {
        if (!StringUtils.hasText(startEventId)) {
            return;
        }
        emailMonitorRuleRepository.findByFunctionUnitIdAndStartEventId(functionUnitId, startEventId.trim())
                .ifPresent(emailMonitorRuleRepository::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertRuntimeBindingsForDeploy(Long functionUnitId) {
        ensureFunctionUnitExists(functionUnitId);
        List<EmailMonitorRule> templates = emailMonitorRuleRepository
                .findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(functionUnitId);
        List<EmailMonitorRule> bindings = emailMonitorRuleRepository
                .findByFunctionUnitIdAndStartEventIdIsNotNull(functionUnitId);
        for (EmailMonitorRule template : templates) {
            if (Boolean.FALSE.equals(template.getEnabled())) {
                continue;
            }
            assertEnabledTemplateReadyToDeploy(functionUnitId, template, bindings);
        }
    }

    private void assertEnabledTemplateReadyToDeploy(
            Long functionUnitId, EmailMonitorRule template, List<EmailMonitorRule> bindings) {
        String templateName = templateDisplayName(template);
        if (!hasReadyBinding(template.getId(), bindings)) {
            throw new DeveloperBusinessException("VALIDATION_MONITOR_BINDING_REQUIRED",
                    i18nService.getMessage("email.monitor.deploy_missing_start_event_binding", templateName));
        }
        assertMonitorConnection(functionUnitId, template.getConnectionUid(), true, templateName);
    }

    private static String templateDisplayName(EmailMonitorRule template) {
        if (StringUtils.hasText(template.getName())) {
            return template.getName().trim();
        }
        return template.getRuleUid();
    }

    private static boolean hasReadyBinding(Long templateId, List<EmailMonitorRule> bindings) {
        return bindings.stream().anyMatch(binding ->
                templateId.equals(binding.getSourceRuleId()) && isRuntimeBinding(binding));
    }

    private static boolean isRuntimeBinding(EmailMonitorRule rule) {
        return !Boolean.FALSE.equals(rule.getEnabled())
                && StringUtils.hasText(rule.getStartEventId())
                && StringUtils.hasText(rule.getProcessDefinitionKey());
    }

    private void rejectTemplatePollution(EmailMonitorRuleRequest request) {
        if (StringUtils.hasText(request.getStartEventId())) {
            throw new DeveloperBusinessException("VALIDATION_MONITOR_TEMPLATE_ONLY",
                    i18nService.getMessage("email.monitor.template_no_start_event"));
        }
        if (StringUtils.hasText(request.getFilterFrom()) || StringUtils.hasText(request.getFilterSubject())) {
            throw new DeveloperBusinessException("VALIDATION_MONITOR_TEMPLATE_ONLY",
                    i18nService.getMessage("email.monitor.template_no_filters"));
        }
        if (StringUtils.hasText(request.getProcessDefinitionKey())) {
            throw new DeveloperBusinessException("VALIDATION_MONITOR_TEMPLATE_ONLY",
                    i18nService.getMessage("email.monitor.template_no_process_key"));
        }
    }

    private void applyTemplate(EmailMonitorRule rule, EmailMonitorRuleRequest request) {
        rule.setName(request.getName().trim());
        rule.setEnabled(request.getEnabled() != null ? request.getEnabled() : Boolean.TRUE);
        rule.setConnectionUid(request.getConnectionUid());
        rule.setSourceRuleId(null);
        rule.setProcessDefinitionKey(null);
        rule.setStartEventId(null);
        rule.setFolderLabel(StringUtils.hasText(request.getFolderLabel()) ? request.getFolderLabel() : "INBOX");
        rule.setFilterFrom(null);
        rule.setFilterSubject(null);
        if (request.getActionType() != null) {
            rule.setActionType(request.getActionType());
        }
        rule.setTargetFormId(request.getTargetFormId());
        rule.setTargetBindingId(request.getTargetBindingId());
        rule.setSystemInitiatorUserId(request.getSystemInitiatorUserId());
        rule.setExtractionRules(request.getExtractionRules());
        rule.setCorrelation(request.getCorrelation());
        if (request.getPollIntervalSeconds() != null && request.getPollIntervalSeconds() > 0) {
            rule.setPollIntervalSeconds(request.getPollIntervalSeconds());
        }
        if (request.getReviewOnMissing() != null) {
            rule.setReviewOnMissing(request.getReviewOnMissing());
        }
    }

    private void copyTemplateFields(EmailMonitorRule binding, EmailMonitorRule template) {
        binding.setConnectionUid(template.getConnectionUid());
        binding.setFolderLabel(template.getFolderLabel());
        binding.setActionType(template.getActionType());
        binding.setTargetFormId(template.getTargetFormId());
        binding.setTargetBindingId(template.getTargetBindingId());
        binding.setSystemInitiatorUserId(template.getSystemInitiatorUserId());
        binding.setExtractionRules(copyMap(template.getExtractionRules()));
        binding.setCorrelation(copyMap(template.getCorrelation()));
        binding.setPollIntervalSeconds(template.getPollIntervalSeconds());
        binding.setReviewOnMissing(template.getReviewOnMissing());
    }

    private void refreshBindingsFromTemplate(EmailMonitorRule template) {
        for (EmailMonitorRule binding : emailMonitorRuleRepository.findBySourceRuleId(template.getId())) {
            copyTemplateFields(binding, template);
            emailMonitorRuleRepository.save(binding);
        }
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? null : new java.util.HashMap<>(source);
    }

    private static String bindingName(String templateName, String startEventId) {
        return templateName + " → " + startEventId;
    }

    private void validateStartEventBinding(Long functionUnitId, String startEventId, Long excludeRuleId) {
        if (!StringUtils.hasText(startEventId)) {
            return;
        }
        String trimmed = startEventId.trim();
        boolean taken = excludeRuleId == null
                ? emailMonitorRuleRepository.findByFunctionUnitIdAndStartEventId(functionUnitId, trimmed).isPresent()
                : emailMonitorRuleRepository.existsByFunctionUnitIdAndStartEventIdAndIdNot(
                        functionUnitId, trimmed, excludeRuleId);
        if (taken) {
            throw new DeveloperBusinessException("CONFLICT_START_EVENT",
                    i18nService.getMessage("email.monitor.start_event_conflict", trimmed));
        }
    }

    private void validateConnection(Long functionUnitId, String connectionUid) {
        assertMonitorConnection(functionUnitId, connectionUid, false, null);
    }

    private void assertMonitorConnection(
            Long functionUnitId, String connectionUid, boolean allowLegacyBoth, String templateName) {
        EmailConnection connection = requireOwnedConnection(functionUnitId, connectionUid, templateName);
        if (Boolean.FALSE.equals(connection.getEnabled())) {
            throw connectionError("VALIDATION_CONNECTION_DISABLED",
                    "email.monitor.connection_disabled", templateName);
        }
        if (!isMonitorDirection(connection.getDirection(), allowLegacyBoth)) {
            throw connectionError("VALIDATION_CONNECTION_NOT_INBOUND",
                    allowLegacyBoth
                            ? "email.monitor.connection_not_monitor_capable"
                            : "email.monitor.connection_not_inbound",
                    templateName);
        }
        String username = StringUtils.hasText(connection.getMailboxAddress())
                ? connection.getMailboxAddress() : connection.getUsername();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(connection.getCredentialEncrypted())) {
            throw connectionError("VALIDATION_CONNECTION_CREDENTIALS",
                    "email.monitor.connection_missing_credentials", templateName);
        }
    }

    private EmailConnection requireOwnedConnection(
            Long functionUnitId, String connectionUid, String templateName) {
        if (!StringUtils.hasText(connectionUid)) {
            throw connectionError("VALIDATION_CONNECTION_REQUIRED",
                    "email.monitor.connection_required", templateName);
        }
        EmailConnection connection = emailConnectionRepository.findByConnectionUid(connectionUid).orElse(null);
        if (connection == null || !functionUnitId.equals(connection.getFunctionUnit().getId())) {
            throw new DeveloperBusinessException("VALIDATION_CONNECTION_INVALID",
                    deployAwareMessage("email.monitor.connection_invalid", templateName, connectionUid));
        }
        return connection;
    }

    private static boolean isMonitorDirection(EmailConnectionDirection direction, boolean allowLegacyBoth) {
        if (direction == EmailConnectionDirection.INBOUND) {
            return true;
        }
        return allowLegacyBoth && direction == EmailConnectionDirection.BOTH;
    }

    private DeveloperBusinessException connectionError(String code, String key, String templateName) {
        return new DeveloperBusinessException(code, deployAwareMessage(key, templateName));
    }

    private String deployAwareMessage(String key, String templateName, Object... args) {
        String detail = args.length == 0
                ? i18nService.getMessage(key)
                : i18nService.getMessage(key, args);
        if (!StringUtils.hasText(templateName)) {
            return detail;
        }
        return i18nService.getMessage("email.monitor.deploy_connection_unready", templateName, detail);
    }

    private EmailMonitorRule getTemplateEntity(Long functionUnitId, Long ruleId) {
        EmailMonitorRule rule = getEntity(functionUnitId, ruleId);
        if (rule.getSourceRuleId() != null || StringUtils.hasText(rule.getStartEventId())) {
            throw new DeveloperBusinessException("VALIDATION_NOT_MONITOR_TEMPLATE",
                    i18nService.getMessage("email.monitor.not_a_template"));
        }
        return rule;
    }

    private EmailMonitorRule getEntity(Long functionUnitId, Long ruleId) {
        EmailMonitorRule rule = emailMonitorRuleRepository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("EmailMonitorRule", ruleId));
        if (!rule.getFunctionUnit().getId().equals(functionUnitId)) {
            throw new ResourceNotFoundException("EmailMonitorRule", ruleId);
        }
        return rule;
    }

    private void ensureFunctionUnitExists(Long functionUnitId) {
        if (!functionUnitRepository.existsById(functionUnitId)) {
            throw new ResourceNotFoundException("FunctionUnit", functionUnitId);
        }
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
