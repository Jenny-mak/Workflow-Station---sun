package com.developer.service.impl;

import com.developer.component.EmailConnectionComponent;
import com.developer.component.EmailMonitorRuleComponent;
import com.developer.component.EmailTemplateComponent;
import com.developer.dto.AiGeneratedData;
import com.developer.dto.EmailConnectionRequest;
import com.developer.dto.EmailMonitorRuleRequest;
import com.developer.dto.EmailTemplateRequest;
import com.developer.entity.EmailConnection;
import com.developer.entity.EmailMonitorRule;
import com.developer.entity.EmailTemplate;
import com.developer.entity.FormDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.enums.ConnectionType;
import com.developer.enums.EmailConnectionDirection;
import com.developer.exception.AiGenerationException;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.FormDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 邮件三阶段提案落库：按名 upsert、从不删除、连接凭证零触碰。 */
@ExtendWith(MockitoExtension.class)
class AiEmailProposalWriterTest {

    @Mock private EmailTemplateComponent emailTemplateComponent;
    @Mock private EmailConnectionComponent emailConnectionComponent;
    @Mock private EmailMonitorRuleComponent emailMonitorRuleComponent;
    @Mock private EmailTemplateRepository emailTemplateRepository;
    @Mock private EmailConnectionRepository emailConnectionRepository;
    @Mock private EmailMonitorRuleRepository emailMonitorRuleRepository;
    @Mock private FormDefinitionRepository formDefinitionRepository;

    private AiEmailProposalWriter writer;
    private FunctionUnit functionUnit;

    @BeforeEach
    void setUp() {
        writer = new AiEmailProposalWriter(emailTemplateComponent, emailConnectionComponent,
                emailMonitorRuleComponent, emailTemplateRepository, emailConnectionRepository,
                emailMonitorRuleRepository, formDefinitionRepository);
        functionUnit = new FunctionUnit();
        functionUnit.setId(7L);
        lenient().when(emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(anyLong())).thenReturn(List.of());
        lenient().when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(anyLong())).thenReturn(List.of());
        lenient().when(emailMonitorRuleRepository
                .findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(anyLong()))
                .thenReturn(List.of());
        lenient().when(formDefinitionRepository.findByFunctionUnitId(anyLong())).thenReturn(List.of());
    }

    @Test
    void hasEmailSlicesDetectsAnyOfTheThree() {
        assertFalse(AiEmailProposalWriter.hasEmailSlices(AiGeneratedData.builder().build()));
        assertFalse(AiEmailProposalWriter.hasEmailSlices(
                AiGeneratedData.builder().emailTemplates(List.of()).build()));
        assertTrue(AiEmailProposalWriter.hasEmailSlices(
                AiGeneratedData.builder().emailConnections(List.of(Map.of("name", "a@b.c"))).build()));
    }

    @Test
    void templateWithNewNameIsCreatedAndExistingNameIsUpdated() {
        EmailTemplate existing = EmailTemplate.builder().id(3L).name("Approved").subject("old").build();
        when(emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenReturn(List.of(existing));

        AiGeneratedData data = AiGeneratedData.builder().emailTemplates(List.of(
                Map.of("name", "Approved", "subject", "new subject", "bodyHtml", "<p>ok</p>"),
                Map.of("name", "Rejected", "subject", "sorry", "bodyHtml", "<p>no</p>", "enabled", false)))
                .build();

        writer.write(functionUnit, data);

        ArgumentCaptor<EmailTemplateRequest> updated = ArgumentCaptor.forClass(EmailTemplateRequest.class);
        verify(emailTemplateComponent).update(eq(7L), eq(3L), updated.capture());
        assertEquals("new subject", updated.getValue().getSubject());
        assertTrue(updated.getValue().getEnabled());

        ArgumentCaptor<EmailTemplateRequest> created = ArgumentCaptor.forClass(EmailTemplateRequest.class);
        verify(emailTemplateComponent).create(eq(7L), created.capture());
        assertEquals("Rejected", created.getValue().getName());
        assertFalse(created.getValue().getEnabled());
        verify(emailTemplateComponent, never()).delete(anyLong(), anyLong());
    }

    @Test
    void newConnectionIsCreatedWithoutAnyCredentials() {
        AiGeneratedData data = AiGeneratedData.builder().emailConnections(List.of(
                Map.of("name", "notify@x.com", "connectionType", "OUTLOOK", "direction", "OUTBOUND",
                        "fromName", "Workflow Bot")))
                .build();

        writer.write(functionUnit, data);

        ArgumentCaptor<EmailConnectionRequest> req = ArgumentCaptor.forClass(EmailConnectionRequest.class);
        verify(emailConnectionComponent).create(eq(7L), req.capture());
        assertEquals("notify@x.com", req.getValue().getName());
        assertEquals(ConnectionType.OUTLOOK, req.getValue().getConnectionType());
        assertEquals(EmailConnectionDirection.OUTBOUND, req.getValue().getDirection());
        assertEquals("Workflow Bot", req.getValue().getFromName());
        assertNull(req.getValue().getUsername());
        assertNull(req.getValue().getPassword());
    }

    @Test
    void existingConnectionKeepsItsCredentialAndBypassesComponentUpdate() {
        EmailConnection existing = EmailConnection.builder()
                .id(11L).name("notify@x.com").direction(EmailConnectionDirection.OUTBOUND)
                .connectionType(ConnectionType.GMAIL).username("bot").credentialEncrypted("ENC")
                .fromName("Old Name").enabled(true).build();
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenReturn(List.of(existing));

        AiGeneratedData data = AiGeneratedData.builder().emailConnections(List.of(
                Map.of("name", "notify@x.com", "direction", "OUTBOUND", "connectionType", "GMAIL",
                        "fromName", "New Name", "enabled", false)))
                .build();

        writer.write(functionUnit, data);

        verify(emailConnectionComponent, never()).update(anyLong(), anyLong(), any());
        verify(emailConnectionComponent, never()).create(anyLong(), any());
        verify(emailConnectionRepository).save(existing);
        assertEquals("New Name", existing.getFromName());
        assertFalse(existing.getEnabled());
        assertEquals("ENC", existing.getCredentialEncrypted());
        assertEquals("bot", existing.getUsername());
    }

    @Test
    void sameAddressWithDifferentDirectionIsANewConnection() {
        EmailConnection outbound = EmailConnection.builder()
                .id(11L).name("shared@x.com").direction(EmailConnectionDirection.OUTBOUND).build();
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenReturn(List.of(outbound));

        AiGeneratedData data = AiGeneratedData.builder().emailConnections(List.of(
                Map.of("name", "shared@x.com", "direction", "INBOUND", "mailboxAddress", "shared@x.com")))
                .build();

        writer.write(functionUnit, data);

        verify(emailConnectionComponent).create(eq(7L), any());
        verify(emailConnectionRepository, never()).save(any());
    }

    @Test
    void monitorResolvesConnectionByNamePreferringInboundAndFormByName() {
        EmailConnection outbound = EmailConnection.builder()
                .id(1L).name("inbox@x.com").direction(EmailConnectionDirection.OUTBOUND).connectionUid("uid-out").build();
        EmailConnection inbound = EmailConnection.builder()
                .id(2L).name("inbox@x.com").direction(EmailConnectionDirection.INBOUND).connectionUid("uid-in").build();
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenReturn(List.of(outbound, inbound));
        FormDefinition form = new FormDefinition();
        form.setId(40L);
        form.setFormName("order_form");
        when(formDefinitionRepository.findByFunctionUnitId(7L)).thenReturn(List.of(form));

        Map<String, Object> rule = new HashMap<>();
        rule.put("name", "Invoice inbox");
        rule.put("connectionName", "inbox@x.com");
        rule.put("targetFormName", "order_form");
        rule.put("pollIntervalSeconds", 120);
        rule.put("extractionRules", Map.of("fields", List.of(Map.of("target", "order_no", "source", "SUBJECT", "type", "DIRECT"))));
        AiGeneratedData data = AiGeneratedData.builder().emailMonitorRules(List.of(rule)).build();

        writer.write(functionUnit, data);

        ArgumentCaptor<EmailMonitorRuleRequest> req = ArgumentCaptor.forClass(EmailMonitorRuleRequest.class);
        verify(emailMonitorRuleComponent).create(eq(7L), req.capture());
        assertEquals("uid-in", req.getValue().getConnectionUid());
        assertEquals(40L, req.getValue().getTargetFormId());
        assertEquals(120, req.getValue().getPollIntervalSeconds());
        assertNull(req.getValue().getStartEventId());
        assertNull(req.getValue().getTargetBindingId());
        assertNull(req.getValue().getSystemInitiatorUserId());
    }

    @Test
    void monitorWithExistingNameIsUpdated() {
        EmailConnection inbound = EmailConnection.builder()
                .id(2L).name("inbox@x.com").direction(EmailConnectionDirection.INBOUND).connectionUid("uid-in").build();
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenReturn(List.of(inbound));
        EmailMonitorRule existing = EmailMonitorRule.builder().id(5L).name("Invoice inbox").build();
        when(emailMonitorRuleRepository
                .findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(7L))
                .thenReturn(List.of(existing));

        AiGeneratedData data = AiGeneratedData.builder().emailMonitorRules(List.of(
                Map.of("name", "Invoice inbox", "connectionName", "inbox@x.com", "enabled", false)))
                .build();

        writer.write(functionUnit, data);

        verify(emailMonitorRuleComponent).update(eq(7L), eq(5L), any());
        verify(emailMonitorRuleComponent, never()).create(anyLong(), any());
    }

    @Test
    void monitorReferencingUnknownConnectionFailsBeforeWriting() {
        AiGeneratedData data = AiGeneratedData.builder().emailMonitorRules(List.of(
                Map.of("name", "Invoice inbox", "connectionName", "ghost@x.com")))
                .build();

        AiGenerationException ex = assertThrows(AiGenerationException.class, () -> writer.write(functionUnit, data));

        assertEquals("AI_STUDIO_MONITOR_CONNECTION_NOT_FOUND", ex.getErrorCode());
        verify(emailMonitorRuleComponent, never()).create(anyLong(), any());
    }
}
