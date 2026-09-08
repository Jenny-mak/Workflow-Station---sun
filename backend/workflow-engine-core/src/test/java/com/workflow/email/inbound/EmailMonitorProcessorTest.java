package com.workflow.email.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.client.AdminCenterClient;
import com.workflow.dto.response.ProcessInstanceResult;
import com.workflow.email.extract.EmailMessage;
import com.workflow.email.inbound.entity.ProcessedEmailMessage;
import com.workflow.email.inbound.entity.SysEmailMonitorRule;
import com.workflow.email.inbound.repository.ProcessedEmailMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailMonitorProcessorTest {

    private ProcessedEmailMessageRepository processedRepository;
    private EmailMonitorPortalSyncComponent portalSyncComponent;
    private AdminCenterClient adminCenterClient;
    private EmailMonitorProcessor processor;

    @BeforeEach
    void setUp() {
        processedRepository = mock(ProcessedEmailMessageRepository.class);
        portalSyncComponent = mock(EmailMonitorPortalSyncComponent.class);
        adminCenterClient = mock(AdminCenterClient.class);
        when(adminCenterClient.resolveFunctionUnitCodeById(eq("fu-1")))
                .thenReturn(Optional.of("FU-MCY"));
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        processor = new EmailMonitorProcessor(
                processedRepository, portalSyncComponent, adminCenterClient, new ObjectMapper(), txManager);
    }

    private SysEmailMonitorRule rule(Map<String, Object> extractionRules) {
        SysEmailMonitorRule rule = new SysEmailMonitorRule();
        rule.setId("rule-1");
        rule.setActionType("START_PROCESS");
        rule.setProcessDefinitionKey("case_process");
        rule.setSystemInitiatorUserId("system");
        rule.setFunctionUnitId("fu-1");
        rule.setReviewOnMissing(true);
        rule.setExtractionRules(extractionRules);
        return rule;
    }

    private Map<String, Object> labelRule(String target, String label, boolean required) {
        return Map.of("fields", List.of(Map.of(
                "target", target, "source", "TEXT", "type", "LABEL",
                "label", label, "required", required)));
    }

    @Test
    void missingRequiredRoutesToReviewAndDoesNotStartProcess() {
        SysEmailMonitorRule rule = rule(labelRule("case_number", "Case No: ", true));
        EmailMessage email = new EmailMessage("m1", "s", "a@b.com", "nothing here", null, Map.of());

        String status = processor.process(rule, email);

        assertThat(status).isEqualTo(ProcessedEmailMessage.STATUS_REVIEW);
        verify(portalSyncComponent, never()).startPortalProcess(any(), any(), any(), any(), any());
        verify(processedRepository).save(any());
    }

    @Test
    void startsProcessViaPortalWithExtractedVariables() {
        SysEmailMonitorRule rule = rule(labelRule("case_number", "Case No: ", true));
        EmailMessage email = new EmailMessage("m2", "s", "a@b.com", "Case No: ABC-7", null, Map.of());

        when(portalSyncComponent.startPortalProcess(any(), any(), any(), any(), any()))
                .thenReturn(ProcessInstanceResult.builder()
                        .processInstanceId("pi-9").success(true).build());

        String status = processor.process(rule, email);

        assertThat(status).isEqualTo(ProcessedEmailMessage.STATUS_STARTED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(portalSyncComponent).startPortalProcess(
                eq("case_process"), eq("FU-MCY"), eq("system"), eq("email:m2"), vars.capture());
        assertThat(vars.getValue()).containsEntry("case_number", "ABC-7");
        assertThat(vars.getValue()).containsEntry("initiator", "system");
        assertThat(vars.getValue()).containsEntry("functionUnitCode", "FU-MCY");
        @SuppressWarnings("unchecked")
        Map<String, Object> inbound = (Map<String, Object>) vars.getValue().get("__inboundEmail__");
        assertThat(inbound).containsEntry("messageId", "m2");
    }

    @Test
    void inboundEmailSnapshotIncludesHeadersWhenPresent() {
        SysEmailMonitorRule rule = rule(labelRule("case_number", "Case No: ", true));
        Map<String, String> headers = Map.of(
                "to", "user@example.com",
                "cc", "cc@example.com",
                "reply-to", "reply@example.com",
                "date", "2026-09-08T10:00:00Z");
        EmailMessage email = new EmailMessage(
                "m5", "Subject line", "from@example.com", "Case No: X", null, headers);

        when(portalSyncComponent.startPortalProcess(any(), any(), any(), any(), any()))
                .thenReturn(ProcessInstanceResult.builder()
                        .processInstanceId("pi-10").success(true).build());

        processor.process(rule, email);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(portalSyncComponent).startPortalProcess(any(), any(), any(), any(), vars.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> inbound = (Map<String, Object>) vars.getValue().get("__inboundEmail__");
        assertThat(inbound)
                .containsEntry("to", "user@example.com")
                .containsEntry("cc", "cc@example.com")
                .containsEntry("reply-to", "reply@example.com")
                .containsEntry("date", "2026-09-08T10:00:00Z");
    }

    @Test
    void unresolvedFunctionUnitCodeFailsBeforePortalStart() {
        SysEmailMonitorRule rule = rule(labelRule("case_number", "Case No: ", true));
        rule.setFunctionUnitId("fu-missing");
        when(adminCenterClient.resolveFunctionUnitCodeById("fu-missing")).thenReturn(Optional.empty());
        EmailMessage email = new EmailMessage("m4", "s", "a@b.com", "Case No: ABC-7", null, Map.of());

        String status = processor.process(rule, email);

        assertThat(status).isEqualTo(ProcessedEmailMessage.STATUS_FAILED);
        verify(portalSyncComponent, never()).startPortalProcess(any(), any(), any(), any(), any());
        verify(processedRepository).save(any());
    }

    @Test
    void alreadyProcessedMessageIsSkipped() {
        SysEmailMonitorRule rule = rule(labelRule("case_number", "Case No: ", true));
        EmailMessage email = new EmailMessage("m3", "s", "a@b.com", "Case No: X", null, Map.of());
        when(processedRepository.existsByRuleUidAndMessageId("rule-1", "m3")).thenReturn(true);

        String status = processor.process(rule, email);

        assertThat(status).isNull();
        verify(portalSyncComponent, never()).startPortalProcess(any(), any(), any(), any(), any());
        verify(processedRepository, never()).save(any());
    }
}
