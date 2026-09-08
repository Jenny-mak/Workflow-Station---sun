package com.developer.component.impl;

import com.developer.entity.EmailConnection;
import com.developer.entity.EmailMonitorRule;
import com.developer.entity.EmailTemplate;
import com.developer.entity.FunctionUnit;
import com.developer.repository.ActionDefinitionRepository;
import com.developer.repository.DecisionDefinitionRepository;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FormStageBindingRepository;
import com.developer.repository.FormTableBindingRepository;
import com.developer.repository.FunctionUnitRepository;
import com.developer.repository.LinkFormComponentRepository;
import com.developer.repository.SubTableViewConfigRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.util.BpmnIdRewriter;
import com.developer.validation.DmnXmlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Email connections / templates / monitors must round-trip with id remaps for Send Email BPMN.
 */
@ExtendWith(MockitoExtension.class)
class EmailPortabilityTest {

    @Mock
    private EmailConnectionRepository emailConnectionRepository;
    @Mock
    private EmailTemplateRepository emailTemplateRepository;
    @Mock
    private EmailMonitorRuleRepository emailMonitorRuleRepository;

    private FunctionUnitImportWriter importWriter;

    @BeforeEach
    void setUp() {
        importWriter = new FunctionUnitImportWriter(
                mock(TableDefinitionRepository.class),
                mock(FormDefinitionRepository.class),
                mock(ActionDefinitionRepository.class),
                mock(DecisionDefinitionRepository.class),
                emailConnectionRepository,
                emailMonitorRuleRepository,
                emailTemplateRepository,
                mock(FormTableBindingRepository.class),
                mock(LinkFormComponentRepository.class),
                mock(TableRelationRepository.class),
                mock(SubTableViewConfigRepository.class),
                mock(DmnXmlParser.class),
                new ObjectMapper());
    }

    @Test
    void importEmailTemplate_persistsSubjectAndBody() {
        FunctionUnit functionUnit = FunctionUnit.builder().id(7L).code("fu_demo").build();
        Map<String, Object> data = new HashMap<>();
        data.put("templateId", 12);
        data.put("name", "Approval notice");
        data.put("subject", "Hello ${applicant}");
        data.put("bodyHtml", "<p>Body</p>");
        data.put("enabled", true);

        when(emailTemplateRepository.save(any(EmailTemplate.class)))
                .thenAnswer(inv -> {
                    EmailTemplate t = inv.getArgument(0);
                    t.setId(120L);
                    return t;
                });

        EmailTemplate saved = importWriter.importEmailTemplate(functionUnit, data);

        assertThat(saved.getId()).isEqualTo(120L);
        assertThat(saved.getName()).isEqualTo("Approval notice");
        assertThat(saved.getSubject()).isEqualTo("Hello ${applicant}");
        assertThat(saved.getBodyHtml()).isEqualTo("<p>Body</p>");
        assertThat(saved.getFunctionUnit()).isSameAs(functionUnit);
    }

    @Test
    void importEmailConnection_returnsEntityAndRestoresOauth() {
        FunctionUnit functionUnit = FunctionUnit.builder().id(7L).code("fu_demo").build();
        Map<String, Object> data = new HashMap<>();
        data.put("connectionId", 5);
        data.put("connectionUid", "uid-abc");
        data.put("name", "SMTP");
        data.put("connectionType", "SMTP");
        data.put("host", "smtp.example.com");
        data.put("port", 587);
        data.put("fromEmail", "a@example.com");
        data.put("oauthProvider", "GMAIL");
        data.put("oauthRefreshTokenEncrypted", "enc-refresh");

        when(emailConnectionRepository.save(any(EmailConnection.class)))
                .thenAnswer(inv -> {
                    EmailConnection c = inv.getArgument(0);
                    c.setId(50L);
                    return c;
                });

        EmailConnection saved = importWriter.importEmailConnection(functionUnit, data);

        assertThat(saved.getId()).isEqualTo(50L);
        assertThat(saved.getConnectionUid()).isEqualTo("uid-abc");
        assertThat(saved.getOauthRefreshTokenEncrypted()).isEqualTo("enc-refresh");
        assertThat(saved.getOauthProvider().name()).isEqualTo("GMAIL");
    }

    @Test
    void importEmailConnection_readsCredentialEncrypted() {
        FunctionUnit functionUnit = FunctionUnit.builder().id(7L).code("fu_demo").build();
        Map<String, Object> data = new HashMap<>();
        data.put("connectionUid", "uid-abc");
        data.put("name", "SMTP");
        data.put("fromEmail", "a@example.com");
        data.put("credentialEncrypted", "enc-new");

        when(emailConnectionRepository.save(any(EmailConnection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EmailConnection saved = importWriter.importEmailConnection(functionUnit, data);

        assertThat(saved.getCredentialEncrypted()).isEqualTo("enc-new");
    }

    @Test
    void importEmailConnection_acceptsLegacyPasswordEncryptedKey() {
        FunctionUnit functionUnit = FunctionUnit.builder().id(7L).code("fu_demo").build();
        Map<String, Object> data = new HashMap<>();
        data.put("connectionUid", "uid-abc");
        data.put("name", "SMTP");
        data.put("fromEmail", "a@example.com");
        data.put("passwordEncrypted", "enc-legacy");

        when(emailConnectionRepository.save(any(EmailConnection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EmailConnection saved = importWriter.importEmailConnection(functionUnit, data);

        assertThat(saved.getCredentialEncrypted()).isEqualTo("enc-legacy");
    }

    @Test
    void importEmailConnection_prefersLegacyWhenNewKeyBlank() {
        FunctionUnit functionUnit = FunctionUnit.builder().id(7L).code("fu_demo").build();
        Map<String, Object> data = new HashMap<>();
        data.put("connectionUid", "uid-abc");
        data.put("name", "SMTP");
        data.put("fromEmail", "a@example.com");
        data.put("credentialEncrypted", null);
        data.put("passwordEncrypted", "enc-legacy");

        when(emailConnectionRepository.save(any(EmailConnection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EmailConnection saved = importWriter.importEmailConnection(functionUnit, data);

        assertThat(saved.getCredentialEncrypted()).isEqualTo("enc-legacy");
    }

    @Test
    void importEmailMonitorRule_usesTargetFunctionUnitCodeAsProcessKey() {
        FunctionUnit functionUnit = FunctionUnit.builder().id(7L).code("fu_new_code").build();
        Map<String, Object> ruleData = new HashMap<>();
        ruleData.put("ruleUid", "rule-1");
        ruleData.put("name", "Inbound");
        ruleData.put("connectionUid", "uid-abc");
        ruleData.put("processDefinitionKey", "fu_old_code");
        ruleData.put("startEventId", "StartEvent_1");

        when(emailMonitorRuleRepository.save(any(EmailMonitorRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        importWriter.importEmailMonitorRule(functionUnit, ruleData, Map.of(), Map.of());

        ArgumentCaptor<EmailMonitorRule> captor = ArgumentCaptor.forClass(EmailMonitorRule.class);
        verify(emailMonitorRuleRepository).save(captor.capture());
        assertThat(captor.getValue().getProcessDefinitionKey()).isEqualTo("fu_new_code");
    }

    @Test
    void importEmailMonitorRule_unmappedFormId_throwsBusinessException() {
        FunctionUnit functionUnit = FunctionUnit.builder().id(7L).code("fu_new_code").build();
        Map<String, Object> ruleData = new HashMap<>();
        ruleData.put("ruleUid", "rule-1");
        ruleData.put("name", "Inbound");
        ruleData.put("targetFormId", 99);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> importWriter.importEmailMonitorRule(functionUnit, ruleData, Map.of(), Map.of()))
                .isInstanceOf(com.developer.exception.DeveloperBusinessException.class)
                .hasMessageContaining("targetFormId");
    }

    @Test
    void importEmailMonitorRule_unmappedBindingId_throwsBusinessException() {
        FunctionUnit functionUnit = FunctionUnit.builder().id(7L).code("fu_new_code").build();
        Map<String, Object> ruleData = new HashMap<>();
        ruleData.put("ruleUid", "rule-1");
        ruleData.put("name", "Inbound");
        ruleData.put("targetBindingId", "55");

        assertThatThrownBy(
                        () -> importWriter.importEmailMonitorRule(functionUnit, ruleData, Map.of(), Map.of()))
                .isInstanceOf(com.developer.exception.DeveloperBusinessException.class)
                .hasMessageContaining("targetBindingId");
    }

    @Test
    void buildVersionSnapshotPayload_alwaysIncludesEmptyEmailTemplatesKey() {
        FunctionUnitRepository functionUnitRepository = mock(FunctionUnitRepository.class);
        TableDefinitionRepository tableDefinitionRepository = mock(TableDefinitionRepository.class);
        FormDefinitionRepository formDefinitionRepository = mock(FormDefinitionRepository.class);
        ActionDefinitionRepository actionDefinitionRepository = mock(ActionDefinitionRepository.class);
        DecisionDefinitionRepository decisionDefinitionRepository = mock(DecisionDefinitionRepository.class);
        FormStageBindingRepository formStageBindingRepository = mock(FormStageBindingRepository.class);
        TableRelationRepository tableRelationRepository = mock(TableRelationRepository.class);
        FunctionUnitWorkspaceAccessService accessService = mock(FunctionUnitWorkspaceAccessService.class);

        FunctionUnit functionUnit = FunctionUnit.builder().id(42L).name("Demo").code("fu_demo").build();
        when(functionUnitRepository.findById(42L)).thenReturn(Optional.of(functionUnit));
        when(tableDefinitionRepository.findByFunctionUnitIdWithFields(42L)).thenReturn(List.of());
        when(formDefinitionRepository.findByFunctionUnitIdWithBindings(42L)).thenReturn(List.of());
        when(actionDefinitionRepository.findByFunctionUnitId(42L)).thenReturn(List.of());
        when(decisionDefinitionRepository.findByFunctionUnitId(42L)).thenReturn(List.of());
        when(tableRelationRepository.findByFunctionUnitId(42L)).thenReturn(List.of());

        FunctionUnitExporter exporter = ExportImportTestComponents.exporter(
                functionUnitRepository,
                tableDefinitionRepository,
                formDefinitionRepository,
                actionDefinitionRepository,
                decisionDefinitionRepository,
                formStageBindingRepository,
                tableRelationRepository,
                accessService,
                new ObjectMapper());

        Map<String, Object> payload = exporter.buildVersionSnapshotPayload(42L);

        assertThat(payload).containsKey("emailTemplates");
        assertThat(payload.get("emailTemplates")).isInstanceOf(List.class);
        assertThat((List<?>) payload.get("emailTemplates")).isEmpty();
    }

    @Test
    void bpmnRewriter_remapsConnectionIdAndEmailTemplateId() {
        String xml = """
                <bpmn:sendTask id="SendTask_1">
                  <custom:properties>
                    <custom:property name="connectionId" value="5" />
                    <custom:property name="emailTemplateId" value="12" />
                    <custom:property name="emailTo" value="user@example.com" />
                  </custom:properties>
                </bpmn:sendTask>
                """;

        String rewritten = BpmnIdRewriter.rewrite(
                xml,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(5L, 50L),
                Map.of(12L, 120L));

        assertThat(rewritten).contains("name=\"connectionId\" value=\"50\"");
        assertThat(rewritten).contains("name=\"emailTemplateId\" value=\"120\"");
        assertThat(rewritten).contains("name=\"emailTo\" value=\"user@example.com\"");
    }

    @Test
    void bpmnRewriter_remapsConnectionUidStoredInConnectionId() {
        String xml = """
                <bpmn:sendTask id="SendTask_1">
                  <custom:properties>
                    <custom:property name="connectionId" value="uid-source" />
                  </custom:properties>
                </bpmn:sendTask>
                """;

        String rewritten = BpmnIdRewriter.rewrite(
                xml,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("uid-source", "uid-cloned"));

        assertThat(rewritten).contains("name=\"connectionId\" value=\"uid-cloned\"");
        assertThat(rewritten).doesNotContain("uid-source");
    }
}
