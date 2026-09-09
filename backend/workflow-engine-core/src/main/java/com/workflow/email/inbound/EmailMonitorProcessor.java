package com.workflow.email.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.client.AdminCenterClient;
import com.workflow.dto.response.ProcessInstanceResult;
import com.workflow.email.extract.EmailExtractionSpec;
import com.workflow.email.extract.EmailFieldExtractor;
import com.workflow.email.extract.EmailMessage;
import com.workflow.email.extract.ExtractionResult;
import com.workflow.email.inbound.entity.ProcessedEmailMessage;
import com.workflow.email.inbound.entity.SysEmailMonitorRule;
import com.workflow.email.inbound.repository.ProcessedEmailMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Processes a single inbound email against a monitor rule: runs the no-code extraction,
 * applies the missing-required review gate, and either starts a process (writing main fields +
 * sub-table rows as variables) or records the email for manual review. Idempotent per
 * {@code (ruleUid, messageId)}.
 *
 * <p>Portal start runs outside a DB transaction so engine JDBC connections are not held
 * across the cross-service Flowable round-trip; only {@code processedRepository.save} is transactional.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailMonitorProcessor {

    private static final String ACTION_START_PROCESS = "START_PROCESS";

    private final ProcessedEmailMessageRepository processedRepository;
    private final EmailMonitorPortalSyncComponent portalSyncComponent;
    private final AdminCenterClient adminCenterClient;
    private final EmailInboundAttachmentBinder attachmentBinder;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    private volatile TransactionTemplate txTemplate;

    private TransactionTemplate tx() {
        TransactionTemplate template = txTemplate;
        if (template == null) {
            template = new TransactionTemplate(transactionManager);
            txTemplate = template;
        }
        return template;
    }

    /** Returns the recorded status, or {@code null} when the email was skipped as already processed. */
    public String process(SysEmailMonitorRule rule, EmailMessage email) {
        if (!StringUtils.hasText(email.messageId())) {
            log.warn("Inbound email without messageId for rule {}; skipping", rule.getId());
            return null;
        }
        if (processedRepository.existsByRuleUidAndMessageId(rule.getId(), email.messageId())) {
            return null;
        }
        try {
            return processNew(rule, email);
        } catch (DataIntegrityViolationException race) {
            log.info("Email {} already claimed by another instance for rule {}", email.messageId(), rule.getId());
            return null;
        }
    }

    private String processNew(SysEmailMonitorRule rule, EmailMessage email) {
        EmailExtractionSpec spec = parseSpec(rule);
        if (spec == null) {
            return record(rule, email, ProcessedEmailMessage.STATUS_FAILED, null, "No extraction rules configured");
        }

        ExtractionResult extraction = EmailFieldExtractor.extract(email, spec);
        EmailInboundAttachmentBinder.BindResult attachments = attachmentBinder.bind(email, spec);
        extraction.getFields().putAll(attachments.fields());
        for (String missing : attachments.missingRequired()) {
            if (!extraction.getMissingRequired().contains(missing)) {
                extraction.getMissingRequired().add(missing);
            }
        }

        boolean review = extraction.hasMissingRequired()
                && (rule.getReviewOnMissing() == null || Boolean.TRUE.equals(rule.getReviewOnMissing()));
        if (review) {
            return record(rule, email, ProcessedEmailMessage.STATUS_REVIEW, null,
                    "Missing required fields: " + extraction.getMissingRequired());
        }

        if (!ACTION_START_PROCESS.equalsIgnoreCase(rule.getActionType())
                || !StringUtils.hasText(rule.getProcessDefinitionKey())) {
            return record(rule, email, ProcessedEmailMessage.STATUS_FAILED, null,
                    "Unsupported action or missing process key");
        }

        Optional<String> functionUnitCode = resolveFunctionUnitCode(rule);
        if (functionUnitCode.isEmpty()) {
            return record(rule, email, ProcessedEmailMessage.STATUS_FAILED, null,
                    "functionUnitCode could not be resolved for rule " + rule.getId());
        }

        Map<String, Object> startVariables = buildStartVariables(
                rule, email, extraction, functionUnitCode.get(), attachments);
        ProcessInstanceResult result = portalSyncComponent.startPortalProcess(
                rule.getProcessDefinitionKey(),
                functionUnitCode.get(),
                rule.getSystemInitiatorUserId(),
                "email:" + email.messageId(),
                startVariables);
        if (result == null || !result.isSuccess()) {
            String msg = result != null ? result.getMessage() : "portal startProcess returned null";
            return record(rule, email, ProcessedEmailMessage.STATUS_FAILED, null, msg);
        }
        return record(rule, email, ProcessedEmailMessage.STATUS_STARTED, result.getProcessInstanceId(), null);
    }

    /**
     * Catalog id on the rule must resolve to a DW function unit code when present; otherwise
     * fall back to the BPMN process definition key (same as internal Portal start).
     */
    private Optional<String> resolveFunctionUnitCode(SysEmailMonitorRule rule) {
        if (!StringUtils.hasText(rule.getFunctionUnitId())) {
            if (StringUtils.hasText(rule.getProcessDefinitionKey())) {
                return Optional.of(rule.getProcessDefinitionKey().trim());
            }
            return Optional.empty();
        }
        Optional<String> code = adminCenterClient.resolveFunctionUnitCodeById(rule.getFunctionUnitId());
        if (code.isEmpty()) {
            log.warn("Email monitor rule {} could not resolve functionUnitCode for functionUnitId={}",
                    rule.getId(), rule.getFunctionUnitId());
        }
        return code;
    }

    private Map<String, Object> buildStartVariables(
            SysEmailMonitorRule rule,
            EmailMessage email,
            ExtractionResult extraction,
            String functionUnitCode,
            EmailInboundAttachmentBinder.BindResult attachments) {
        Map<String, Object> variables = new HashMap<>(extraction.getFields());
        if (StringUtils.hasText(rule.getSystemInitiatorUserId())) {
            variables.put("initiator", rule.getSystemInitiatorUserId());
        }
        if (StringUtils.hasText(rule.getFunctionUnitId())) {
            variables.put("functionUnitId", rule.getFunctionUnitId());
        }
        variables.put("functionUnitCode", functionUnitCode);
        if (StringUtils.hasText(rule.getProcessDefinitionKey())) {
            variables.put("processDefinitionKey", rule.getProcessDefinitionKey());
        }
        variables.put("__inboundEmail__", inboundEmailSnapshot(email, attachments));
        if (!extraction.getSubTables().isEmpty()) {
            variables.put("__subTables__", extraction.getSubTables());
        }
        return variables;
    }

    private Map<String, Object> inboundEmailSnapshot(
            EmailMessage email, EmailInboundAttachmentBinder.BindResult attachments) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("messageId", email.messageId());
        snapshot.put("subject", email.subject());
        snapshot.put("from", email.from());
        putHeaderIfPresent(snapshot, email, "to");
        putHeaderIfPresent(snapshot, email, "cc");
        putHeaderIfPresent(snapshot, email, "reply-to");
        putHeaderIfPresent(snapshot, email, "date");
        snapshot.put("text", email.text());
        if (StringUtils.hasText(email.html())) {
            snapshot.put("html", email.html());
        }
        List<String> names = !attachments.attachmentNames().isEmpty()
                ? attachments.attachmentNames()
                : email.attachmentNames();
        if (!names.isEmpty()) {
            snapshot.put("attachmentNames", names);
        }
        if (!attachments.attachmentErrors().isEmpty()) {
            snapshot.put("attachmentErrors", attachments.attachmentErrors());
        }
        return snapshot;
    }

    private static void putHeaderIfPresent(
            Map<String, Object> snapshot, EmailMessage email, String headerKey) {
        if (email.headers() == null) {
            return;
        }
        String value = email.headers().get(headerKey);
        if (StringUtils.hasText(value)) {
            snapshot.put(headerKey, value);
        }
    }

    private EmailExtractionSpec parseSpec(SysEmailMonitorRule rule) {
        Map<String, Object> rules = rule.getExtractionRules();
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(rules, EmailExtractionSpec.class);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid extractionRules for rule {}: {}", rule.getId(), e.getMessage());
            return null;
        }
    }

    private String record(SysEmailMonitorRule rule, EmailMessage email,
                          String status, String processInstanceId, String error) {
        return tx().execute(txStatus -> {
            ProcessedEmailMessage row = new ProcessedEmailMessage();
            row.setRuleUid(rule.getId());
            row.setMessageId(email.messageId());
            row.setProcessInstanceId(processInstanceId);
            row.setStatus(status);
            row.setErrorMessage(truncate(error));
            row.setProcessedAt(Instant.now());
            processedRepository.save(row);
            return status;
        });
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
}
