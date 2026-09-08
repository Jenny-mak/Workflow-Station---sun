package com.developer.component.impl;

import com.developer.dto.EmailMonitorRuleRequest;
import com.developer.dto.EmailMonitorRuleResponse;
import com.developer.dto.EmailMonitorStartEventBindRequest;
import com.developer.entity.EmailConnection;
import com.developer.entity.EmailMonitorRule;
import com.developer.entity.FunctionUnit;
import com.developer.enums.EmailConnectionDirection;
import com.developer.enums.EmailMonitorActionType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.FunctionUnitRepository;
import com.platform.common.i18n.I18nService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailMonitorRuleComponentImplTest {

    private static final Long FUNCTION_UNIT_ID = 48L;
    private static final Long TEMPLATE_RULE_ID = 10L;
    private static final String CONNECTION_UID = "conn-inbound-1";
    private static final String START_EVENT_ID = "StartEvent_1";

    @Mock
    private EmailMonitorRuleRepository emailMonitorRuleRepository;

    @Mock
    private EmailConnectionRepository emailConnectionRepository;

    @Mock
    private FunctionUnitRepository functionUnitRepository;

    @Mock
    private I18nService i18nService;

    @InjectMocks
    private EmailMonitorRuleComponentImpl component;

    @Test
    void create_withStartEventId_rejectsTemplatePollution() {
        FunctionUnit functionUnit = sampleFunctionUnit();
        EmailMonitorRuleRequest request = sampleTemplateRequest();
        request.setStartEventId(START_EVENT_ID);

        when(functionUnitRepository.findById(FUNCTION_UNIT_ID)).thenReturn(Optional.of(functionUnit));
        when(i18nService.getMessage("email.monitor.template_no_start_event"))
                .thenReturn("Templates cannot reference a Start Event");

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.create(FUNCTION_UNIT_ID, request));

        assertEquals("VALIDATION_MONITOR_TEMPLATE_ONLY", ex.getErrorCode());
        verify(emailMonitorRuleRepository, never()).save(any());
    }

    @Test
    void delete_templateWithBindings_throwsConflictMonitorInUse() {
        EmailMonitorRule template = sampleTemplate(TEMPLATE_RULE_ID);
        EmailMonitorRule binding = EmailMonitorRule.builder()
                .id(20L)
                .sourceRuleId(TEMPLATE_RULE_ID)
                .startEventId(START_EVENT_ID)
                .functionUnit(template.getFunctionUnit())
                .build();

        when(emailMonitorRuleRepository.findById(TEMPLATE_RULE_ID)).thenReturn(Optional.of(template));
        when(emailMonitorRuleRepository.findBySourceRuleId(TEMPLATE_RULE_ID)).thenReturn(List.of(binding));
        when(i18nService.getMessage("email.monitor.template_in_use"))
                .thenReturn("Template is referenced by Start Event bindings");

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.delete(FUNCTION_UNIT_ID, TEMPLATE_RULE_ID));

        assertEquals("CONFLICT_MONITOR_IN_USE", ex.getErrorCode());
        verify(emailMonitorRuleRepository, never()).delete(any());
    }

    @Test
    void bindStartEvent_createsBindingFromTemplate() {
        FunctionUnit functionUnit = sampleFunctionUnit();
        EmailMonitorRule template = sampleTemplate(TEMPLATE_RULE_ID);
        EmailMonitorStartEventBindRequest request = new EmailMonitorStartEventBindRequest();
        request.setTemplateRuleId(TEMPLATE_RULE_ID);
        request.setStartEventId(START_EVENT_ID);
        request.setProcessDefinitionKey("debit-card-process");
        request.setFilterFrom("alerts@example.com");
        request.setFilterSubject("urgent");
        request.setEnabled(true);

        when(functionUnitRepository.findById(FUNCTION_UNIT_ID)).thenReturn(Optional.of(functionUnit));
        when(emailMonitorRuleRepository.findById(TEMPLATE_RULE_ID)).thenReturn(Optional.of(template));
        when(emailConnectionRepository.findByConnectionUid(CONNECTION_UID))
                .thenReturn(Optional.of(inboundConnection(functionUnit)));
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndStartEventId(FUNCTION_UNIT_ID, START_EVENT_ID))
                .thenReturn(Optional.empty());
        when(emailMonitorRuleRepository.existsByFunctionUnitIdAndName(
                eq(FUNCTION_UNIT_ID), eq("Inbox Monitor → " + START_EVENT_ID)))
                .thenReturn(false);
        when(emailMonitorRuleRepository.save(any(EmailMonitorRule.class))).thenAnswer(invocation -> {
            EmailMonitorRule saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        EmailMonitorRuleResponse response = component.bindStartEvent(FUNCTION_UNIT_ID, request);

        ArgumentCaptor<EmailMonitorRule> captor = ArgumentCaptor.forClass(EmailMonitorRule.class);
        verify(emailMonitorRuleRepository).save(captor.capture());
        EmailMonitorRule saved = captor.getValue();

        assertEquals(TEMPLATE_RULE_ID, saved.getSourceRuleId());
        assertEquals(START_EVENT_ID, saved.getStartEventId());
        assertEquals("debit-card-process", saved.getProcessDefinitionKey());
        assertEquals(CONNECTION_UID, saved.getConnectionUid());
        assertEquals("alerts@example.com", saved.getFilterFrom());
        assertEquals("urgent", saved.getFilterSubject());
        assertEquals(true, saved.getEnabled());
        assertEquals(TEMPLATE_RULE_ID, response.getSourceRuleId());
        assertEquals(START_EVENT_ID, response.getStartEventId());
    }

    @Test
    void bindStartEvent_duplicateBindingName_throwsConflictRuleName() {
        FunctionUnit functionUnit = sampleFunctionUnit();
        EmailMonitorRule template = sampleTemplate(TEMPLATE_RULE_ID);
        String expectedName = "Inbox Monitor → " + START_EVENT_ID;
        EmailMonitorStartEventBindRequest request = new EmailMonitorStartEventBindRequest();
        request.setTemplateRuleId(TEMPLATE_RULE_ID);
        request.setStartEventId(START_EVENT_ID);
        request.setProcessDefinitionKey("debit-card-process");

        when(functionUnitRepository.findById(FUNCTION_UNIT_ID)).thenReturn(Optional.of(functionUnit));
        when(emailMonitorRuleRepository.findById(TEMPLATE_RULE_ID)).thenReturn(Optional.of(template));
        when(emailConnectionRepository.findByConnectionUid(CONNECTION_UID))
                .thenReturn(Optional.of(inboundConnection(functionUnit)));
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndStartEventId(FUNCTION_UNIT_ID, START_EVENT_ID))
                .thenReturn(Optional.empty());
        when(emailMonitorRuleRepository.existsByFunctionUnitIdAndName(FUNCTION_UNIT_ID, expectedName))
                .thenReturn(true);
        when(i18nService.getMessage("email.monitor.name_conflict", expectedName))
                .thenReturn("Monitor rule name already exists: " + expectedName);

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.bindStartEvent(FUNCTION_UNIT_ID, request));

        assertEquals("CONFLICT_RULE_NAME", ex.getErrorCode());
        verify(emailMonitorRuleRepository, never()).save(any());
    }

    @Test
    void listTemplates_returnsOnlyTemplateRows() {
        EmailMonitorRule template = sampleTemplate(TEMPLATE_RULE_ID);

        when(functionUnitRepository.existsById(FUNCTION_UNIT_ID)).thenReturn(true);
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(
                FUNCTION_UNIT_ID))
                .thenReturn(List.of(template));

        List<EmailMonitorRuleResponse> templates = component.listTemplates(FUNCTION_UNIT_ID);

        assertEquals(1, templates.size());
        assertEquals(TEMPLATE_RULE_ID, templates.get(0).getId());
        assertNull(templates.get(0).getSourceRuleId());
        assertNull(templates.get(0).getStartEventId());
    }

    @Test
    void create_disabledConnection_rejects() {
        FunctionUnit functionUnit = sampleFunctionUnit();
        EmailConnection connection = inboundConnection(functionUnit);
        connection.setEnabled(false);
        EmailMonitorRuleRequest request = sampleTemplateRequest();

        when(functionUnitRepository.findById(FUNCTION_UNIT_ID)).thenReturn(Optional.of(functionUnit));
        when(emailMonitorRuleRepository.existsByFunctionUnitIdAndName(FUNCTION_UNIT_ID, request.getName()))
                .thenReturn(false);
        when(emailConnectionRepository.findByConnectionUid(CONNECTION_UID))
                .thenReturn(Optional.of(connection));
        when(i18nService.getMessage("email.monitor.connection_disabled"))
                .thenReturn("Enable the mailbox connection");

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.create(FUNCTION_UNIT_ID, request));

        assertEquals("VALIDATION_CONNECTION_DISABLED", ex.getErrorCode());
        verify(emailMonitorRuleRepository, never()).save(any());
    }

    @Test
    void create_bothDirection_rejects() {
        FunctionUnit functionUnit = sampleFunctionUnit();
        EmailConnection connection = inboundConnection(functionUnit);
        connection.setDirection(EmailConnectionDirection.BOTH);
        EmailMonitorRuleRequest request = sampleTemplateRequest();

        when(functionUnitRepository.findById(FUNCTION_UNIT_ID)).thenReturn(Optional.of(functionUnit));
        when(emailMonitorRuleRepository.existsByFunctionUnitIdAndName(FUNCTION_UNIT_ID, request.getName()))
                .thenReturn(false);
        when(emailConnectionRepository.findByConnectionUid(CONNECTION_UID))
                .thenReturn(Optional.of(connection));
        when(i18nService.getMessage("email.monitor.connection_not_inbound"))
                .thenReturn("Email Monitor requires a connection with direction INBOUND");

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.create(FUNCTION_UNIT_ID, request));

        assertEquals("VALIDATION_CONNECTION_NOT_INBOUND", ex.getErrorCode());
        verify(emailMonitorRuleRepository, never()).save(any());
    }

    @Test
    void create_oauthOnly_rejects() {
        FunctionUnit functionUnit = sampleFunctionUnit();
        EmailConnection connection = inboundConnection(functionUnit);
        connection.setCredentialEncrypted(null);
        connection.setOauthRefreshTokenEncrypted("oauth-enc");
        EmailMonitorRuleRequest request = sampleTemplateRequest();

        when(functionUnitRepository.findById(FUNCTION_UNIT_ID)).thenReturn(Optional.of(functionUnit));
        when(emailMonitorRuleRepository.existsByFunctionUnitIdAndName(FUNCTION_UNIT_ID, request.getName()))
                .thenReturn(false);
        when(emailConnectionRepository.findByConnectionUid(CONNECTION_UID))
                .thenReturn(Optional.of(connection));
        when(i18nService.getMessage("email.monitor.connection_missing_credentials"))
                .thenReturn("Mailbox username/address and password are required for Email Monitor");

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.create(FUNCTION_UNIT_ID, request));

        assertEquals("VALIDATION_CONNECTION_CREDENTIALS", ex.getErrorCode());
        verify(emailMonitorRuleRepository, never()).save(any());
    }

    @Test
    void assertRuntimeBindingsForDeploy_enabledTemplateWithoutBinding_throws() {
        when(functionUnitRepository.existsById(FUNCTION_UNIT_ID)).thenReturn(true);
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(
                FUNCTION_UNIT_ID))
                .thenReturn(List.of(sampleTemplate(TEMPLATE_RULE_ID)));
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndStartEventIdIsNotNull(FUNCTION_UNIT_ID))
                .thenReturn(List.of());
        when(i18nService.getMessage("email.monitor.deploy_missing_start_event_binding", "Inbox Monitor"))
                .thenReturn("Bind Start Event before Deploy");

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.assertRuntimeBindingsForDeploy(FUNCTION_UNIT_ID));

        assertEquals("VALIDATION_MONITOR_BINDING_REQUIRED", ex.getErrorCode());
    }

    @Test
    void assertRuntimeBindingsForDeploy_enabledTemplateWithReadyBinding_passes() {
        EmailMonitorRule binding = readyBinding(TEMPLATE_RULE_ID);
        FunctionUnit functionUnit = sampleFunctionUnit();

        when(functionUnitRepository.existsById(FUNCTION_UNIT_ID)).thenReturn(true);
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(
                FUNCTION_UNIT_ID))
                .thenReturn(List.of(sampleTemplate(TEMPLATE_RULE_ID)));
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndStartEventIdIsNotNull(FUNCTION_UNIT_ID))
                .thenReturn(List.of(binding));
        when(emailConnectionRepository.findByConnectionUid(CONNECTION_UID))
                .thenReturn(Optional.of(inboundConnection(functionUnit)));

        component.assertRuntimeBindingsForDeploy(FUNCTION_UNIT_ID);
    }

    @Test
    void assertRuntimeBindingsForDeploy_secondEnabledTemplateUnbound_throws() {
        EmailMonitorRule binding = readyBinding(TEMPLATE_RULE_ID);

        when(functionUnitRepository.existsById(FUNCTION_UNIT_ID)).thenReturn(true);
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(
                FUNCTION_UNIT_ID))
                .thenReturn(List.of(sampleTemplate(TEMPLATE_RULE_ID), sampleTemplate(11L)));
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndStartEventIdIsNotNull(FUNCTION_UNIT_ID))
                .thenReturn(List.of(binding));
        when(emailConnectionRepository.findByConnectionUid(CONNECTION_UID))
                .thenReturn(Optional.of(inboundConnection(sampleFunctionUnit())));
        when(i18nService.getMessage("email.monitor.deploy_missing_start_event_binding", "Inbox Monitor"))
                .thenReturn("Bind Start Event before Deploy");

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.assertRuntimeBindingsForDeploy(FUNCTION_UNIT_ID));

        assertEquals("VALIDATION_MONITOR_BINDING_REQUIRED", ex.getErrorCode());
    }

    @Test
    void assertRuntimeBindingsForDeploy_disabledConnection_throws() {
        FunctionUnit functionUnit = sampleFunctionUnit();
        EmailConnection connection = inboundConnection(functionUnit);
        connection.setEnabled(false);
        stubDeployWithConnection(connection);
        when(i18nService.getMessage("email.monitor.connection_disabled"))
                .thenReturn("Enable the mailbox connection");
        when(i18nService.getMessage(
                "email.monitor.deploy_connection_unready", "Inbox Monitor", "Enable the mailbox connection"))
                .thenReturn("template Inbox Monitor: Enable the mailbox connection");

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.assertRuntimeBindingsForDeploy(FUNCTION_UNIT_ID));

        assertEquals("VALIDATION_CONNECTION_DISABLED", ex.getErrorCode());
    }

    @Test
    void assertRuntimeBindingsForDeploy_legacyBothWithPassword_passes() {
        FunctionUnit functionUnit = sampleFunctionUnit();
        EmailConnection connection = inboundConnection(functionUnit);
        connection.setDirection(EmailConnectionDirection.BOTH);
        stubDeployWithConnection(connection);

        component.assertRuntimeBindingsForDeploy(FUNCTION_UNIT_ID);
    }

    @Test
    void assertRuntimeBindingsForDeploy_oauthOnly_throws() {
        FunctionUnit functionUnit = sampleFunctionUnit();
        EmailConnection connection = inboundConnection(functionUnit);
        connection.setCredentialEncrypted(null);
        connection.setOauthRefreshTokenEncrypted("oauth-enc");
        stubDeployWithConnection(connection);
        when(i18nService.getMessage("email.monitor.connection_missing_credentials"))
                .thenReturn("password required");
        when(i18nService.getMessage(
                "email.monitor.deploy_connection_unready", "Inbox Monitor", "password required"))
                .thenReturn("template Inbox Monitor: password required");

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.assertRuntimeBindingsForDeploy(FUNCTION_UNIT_ID));

        assertEquals("VALIDATION_CONNECTION_CREDENTIALS", ex.getErrorCode());
    }

    @Test
    void assertRuntimeBindingsForDeploy_outboundDirection_throws() {
        FunctionUnit functionUnit = sampleFunctionUnit();
        EmailConnection connection = inboundConnection(functionUnit);
        connection.setDirection(EmailConnectionDirection.OUTBOUND);
        stubDeployWithConnection(connection);
        when(i18nService.getMessage("email.monitor.connection_not_monitor_capable"))
                .thenReturn("must be INBOUND or legacy BOTH");
        when(i18nService.getMessage(
                "email.monitor.deploy_connection_unready", "Inbox Monitor", "must be INBOUND or legacy BOTH"))
                .thenReturn("template Inbox Monitor: must be INBOUND or legacy BOTH");

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class,
                () -> component.assertRuntimeBindingsForDeploy(FUNCTION_UNIT_ID));

        assertEquals("VALIDATION_CONNECTION_NOT_INBOUND", ex.getErrorCode());
    }

    private EmailMonitorRule readyBinding(Long templateId) {
        return EmailMonitorRule.builder()
                .id(20L)
                .sourceRuleId(templateId)
                .startEventId(START_EVENT_ID)
                .processDefinitionKey("debit-card-process")
                .enabled(true)
                .functionUnit(sampleFunctionUnit())
                .build();
    }

    private void stubDeployWithConnection(EmailConnection connection) {
        when(functionUnitRepository.existsById(FUNCTION_UNIT_ID)).thenReturn(true);
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(
                FUNCTION_UNIT_ID))
                .thenReturn(List.of(sampleTemplate(TEMPLATE_RULE_ID)));
        when(emailMonitorRuleRepository.findByFunctionUnitIdAndStartEventIdIsNotNull(FUNCTION_UNIT_ID))
                .thenReturn(List.of(readyBinding(TEMPLATE_RULE_ID)));
        when(emailConnectionRepository.findByConnectionUid(CONNECTION_UID))
                .thenReturn(Optional.of(connection));
    }

    private FunctionUnit sampleFunctionUnit() {
        FunctionUnit fu = new FunctionUnit();
        fu.setId(FUNCTION_UNIT_ID);
        fu.setName("Test FU");
        return fu;
    }

    private EmailMonitorRule sampleTemplate(Long ruleId) {
        return EmailMonitorRule.builder()
                .id(ruleId)
                .ruleUid("rule-uid-" + ruleId)
                .name("Inbox Monitor")
                .enabled(true)
                .connectionUid(CONNECTION_UID)
                .folderLabel("INBOX")
                .actionType(EmailMonitorActionType.START_PROCESS)
                .pollIntervalSeconds(60)
                .reviewOnMissing(true)
                .functionUnit(sampleFunctionUnit())
                .build();
    }

    private EmailMonitorRuleRequest sampleTemplateRequest() {
        EmailMonitorRuleRequest request = new EmailMonitorRuleRequest();
        request.setName("Inbox Monitor");
        request.setConnectionUid(CONNECTION_UID);
        request.setEnabled(true);
        return request;
    }

    private EmailConnection inboundConnection(FunctionUnit functionUnit) {
        return EmailConnection.builder()
                .connectionUid(CONNECTION_UID)
                .name("Inbound")
                .enabled(true)
                .direction(EmailConnectionDirection.INBOUND)
                .mailboxAddress("monitor@example.test")
                .credentialEncrypted("enc")
                .functionUnit(functionUnit)
                .build();
    }
}
