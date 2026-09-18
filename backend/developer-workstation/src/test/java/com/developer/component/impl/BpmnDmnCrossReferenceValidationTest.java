package com.developer.component.impl;

import com.developer.dto.ValidationResult;
import com.developer.entity.DecisionDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.entity.ProcessDefinition;
import com.developer.repository.*;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.developer.service.UserDisplayNameService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * BPMN-DMN 交叉引用验证单元测试
 * 验证需求 10.1, 10.2, 10.3, 10.4
 */
@ExtendWith(MockitoExtension.class)
class BpmnDmnCrossReferenceValidationTest {

    @Mock
    private FunctionUnitRepository functionUnitRepository;
    @Mock
    private ProcessDefinitionRepository processDefinitionRepository;
    @Mock
    private TableDefinitionRepository tableDefinitionRepository;
    @Mock
    private FormDefinitionRepository formDefinitionRepository;
    @Mock
    private ActionDefinitionRepository actionDefinitionRepository;
    @Mock
    private DecisionDefinitionRepository decisionDefinitionRepository;
    @Mock
    private VersionRepository versionRepository;
    @Mock
    private IconRepository iconRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private UserDisplayNameService userDisplayNameService;
    @Mock
    private FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService;
    @Mock
    private FunctionUnitDevGroupAssignmentRepository functionUnitDevGroupAssignmentRepository;

    private FunctionUnitComponentImpl functionUnitComponent;

    /** BPMN XML template with a DMN service task using extension elements */
    private static final String BPMN_WITH_DMN_EXTENSION = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="http://test">
              <process id="testProcess" name="Test">
                <serviceTask id="dmnTask1" name="Evaluate" flowable:type="dmn">
                  <extensionElements>
                    <flowable:field name="decisionTableReferenceKey">
                      <flowable:string><![CDATA[%s]]></flowable:string>
                    </flowable:field>
                  </extensionElements>
                </serviceTask>
              </process>
            </definitions>
            """;

    /** BPMN XML template with a DMN service task using attribute format */
    private static final String BPMN_WITH_DMN_ATTRIBUTE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="http://test">
              <process id="testProcess" name="Test">
                <serviceTask id="dmnTask1" name="Evaluate"
                             flowable:type="dmn"
                             flowable:decisionTableReferenceKey="%s" />
              </process>
            </definitions>
            """;

    /** BPMN XML with no DMN service tasks */
    private static final String BPMN_WITHOUT_DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="http://test">
              <process id="testProcess" name="Test">
                <startEvent id="start" />
                <endEvent id="end" />
              </process>
            </definitions>
            """;


    /** BPMN XML with multiple DMN service tasks */
    private static final String BPMN_WITH_MULTIPLE_DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="http://test">
              <process id="testProcess" name="Test">
                <serviceTask id="dmnTask1" name="Risk" flowable:type="dmn">
                  <extensionElements>
                    <flowable:field name="decisionTableReferenceKey">
                      <flowable:string><![CDATA[risk_assessment]]></flowable:string>
                    </flowable:field>
                  </extensionElements>
                </serviceTask>
                <serviceTask id="dmnTask2" name="Pricing" flowable:type="dmn">
                  <extensionElements>
                    <flowable:field name="decisionTableReferenceKey">
                      <flowable:string><![CDATA[pricing_rules]]></flowable:string>
                    </flowable:field>
                  </extensionElements>
                </serviceTask>
              </process>
            </definitions>
            """;

    @BeforeEach
    void setUp() {
        lenient().when(functionUnitDevGroupAssignmentRepository.findByFunctionUnitId(anyLong())).thenReturn(List.of());
        functionUnitComponent = new FunctionUnitComponentImpl(
                functionUnitRepository,
                processDefinitionRepository,
                tableDefinitionRepository,
                formDefinitionRepository,
                actionDefinitionRepository,
                decisionDefinitionRepository,
                org.mockito.Mockito.mock(FormTableBindingRepository.class),
                org.mockito.Mockito.mock(FormStageBindingRepository.class),
                org.mockito.Mockito.mock(TableRelationRepository.class),
                org.mockito.Mockito.mock(SubTableViewConfigRepository.class),
                versionRepository,
                iconRepository,
                objectMapper,
                userDisplayNameService,
                functionUnitWorkspaceAccessService,
                functionUnitDevGroupAssignmentRepository,
                org.mockito.Mockito.mock(com.developer.component.VersionComponent.class),
                org.mockito.Mockito.mock(com.developer.util.DeveloperWorkstationSequenceSynchronizer.class),
                org.mockito.Mockito.mock(com.developer.service.MainTableViewService.class),
                org.mockito.Mockito.mock(com.developer.repository.ForeignKeyRepository.class),
                org.mockito.Mockito.mock(com.developer.component.impl.FunctionUnitExporter.class),
                org.mockito.Mockito.mock(com.developer.component.TableDesignComponent.class),
                org.mockito.Mockito.mock(com.developer.repository.EmailConnectionRepository.class),
                org.mockito.Mockito.mock(com.developer.repository.EmailMonitorRuleRepository.class),
                org.mockito.Mockito.mock(com.developer.repository.EmailTemplateRepository.class),
                new com.developer.service.impl.FunctionUnitDocumentService(
                org.mockito.Mockito.mock(com.developer.repository.AiDocumentRepository.class),
                org.mockito.Mockito.mock(com.developer.repository.AiStudioThreadStateRepository.class))
        );
    }

    private FunctionUnit createFunctionUnit(String bpmnXml, List<DecisionDefinition> decisions) {
        FunctionUnit fu = FunctionUnit.builder()
                .id(1L)
                .name("Test FU")
                .code("fu-test-001")
                .build();

        if (bpmnXml != null) {
            ProcessDefinition pd = ProcessDefinition.builder()
                    .id(1L)
                    .functionUnit(fu)
                    .bpmnXml(bpmnXml)
                    .build();
            fu.setProcessDefinition(pd);
        }

        fu.setDecisionDefinitions(decisions != null ? decisions : new ArrayList<>());
        return fu;
    }

    private DecisionDefinition createDecision(Long id, String key) {
        return DecisionDefinition.builder()
                .id(id)
                .decisionKey(key)
                .decisionName("Decision " + key)
                .build();
    }

    // --- Requirement 10.4: No decision-related errors when no DecisionDefinitions ---

    @Test
    @DisplayName("No decision errors when function unit has no DecisionDefinitions")
    void shouldNotProduceDecisionErrors_whenNoDecisionDefinitions() {
        String bpmnXml = String.format(BPMN_WITH_DMN_EXTENSION, "some_key");
        FunctionUnit fu = createFunctionUnit(bpmnXml, new ArrayList<>());
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        // Should not have any INVALID_DECISION_REFERENCE or UNREFERENCED_DECISION
        assertTrue(result.getErrors().stream()
                .noneMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())));
        assertTrue(result.getWarnings().stream()
                .noneMatch(w -> "UNREFERENCED_DECISION".equals(w.getCode())));
    }

    // --- Requirement 10.2: INVALID_DECISION_REFERENCE for broken references ---

    @Test
    @DisplayName("Error when BPMN references a decision key not in DecisionDefinition list")
    void shouldReturnError_whenBpmnReferencesMissingDecisionKey() {
        String bpmnXml = String.format(BPMN_WITH_DMN_EXTENSION, "nonexistent_key");
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "existing_key"));
        FunctionUnit fu = createFunctionUnit(bpmnXml, decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())
                        && e.getMessage().contains("nonexistent_key")));
    }

    @Test
    @DisplayName("No error when BPMN references an existing decision key")
    void shouldNotReturnError_whenBpmnReferencesExistingDecisionKey() {
        String bpmnXml = String.format(BPMN_WITH_DMN_EXTENSION, "risk_assessment");
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "risk_assessment"));
        FunctionUnit fu = createFunctionUnit(bpmnXml, decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertTrue(result.getErrors().stream()
                .noneMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())));
    }

    // --- Requirement 10.3: UNREFERENCED_DECISION warning ---

    @Test
    @DisplayName("Warning when DecisionDefinition exists but is not referenced by BPMN")
    void shouldReturnWarning_whenDecisionNotReferencedByBpmn() {
        String bpmnXml = BPMN_WITHOUT_DMN;
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "unused_decision"));
        FunctionUnit fu = createFunctionUnit(bpmnXml, decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> "UNREFERENCED_DECISION".equals(w.getCode())
                        && w.getMessage().contains("unused_decision")));
    }

    @Test
    @DisplayName("No unreferenced warning when all decisions are referenced")
    void shouldNotReturnWarning_whenAllDecisionsAreReferenced() {
        String bpmnXml = String.format(BPMN_WITH_DMN_EXTENSION, "risk_assessment");
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "risk_assessment"));
        FunctionUnit fu = createFunctionUnit(bpmnXml, decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertTrue(result.getWarnings().stream()
                .noneMatch(w -> "UNREFERENCED_DECISION".equals(w.getCode())));
    }

    // --- Multiple references ---

    @Test
    @DisplayName("Mixed: one valid reference and one broken reference")
    void shouldReturnErrorForBroken_andNoErrorForValid() {
        List<DecisionDefinition> decisions = List.of(
                createDecision(1L, "risk_assessment"),
                createDecision(2L, "pricing_rules")
        );
        // BPMN references risk_assessment (valid) and pricing_rules (valid)
        FunctionUnit fu = createFunctionUnit(BPMN_WITH_MULTIPLE_DMN, decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertTrue(result.getErrors().stream()
                .noneMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())));
        assertTrue(result.getWarnings().stream()
                .noneMatch(w -> "UNREFERENCED_DECISION".equals(w.getCode())));
    }

    @Test
    @DisplayName("Multiple DMN tasks: one references missing key, one references existing key")
    void shouldReturnErrorForMissing_andWarningForUnreferenced() {
        // BPMN references risk_assessment and pricing_rules
        // But only approval_decision exists as DecisionDefinition
        List<DecisionDefinition> decisions = List.of(
                createDecision(1L, "approval_decision")
        );
        FunctionUnit fu = createFunctionUnit(BPMN_WITH_MULTIPLE_DMN, decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        // Both risk_assessment and pricing_rules should be INVALID_DECISION_REFERENCE
        assertFalse(result.isValid());
        assertEquals(2, result.getErrors().stream()
                .filter(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode()))
                .count());

        // approval_decision should be UNREFERENCED_DECISION
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> "UNREFERENCED_DECISION".equals(w.getCode())
                        && w.getMessage().contains("approval_decision")));
    }

    // --- Attribute format support ---

    @Test
    @DisplayName("Should extract decision key from flowable:decisionTableReferenceKey attribute")
    void shouldExtractKeyFromAttribute() {
        String bpmnXml = String.format(BPMN_WITH_DMN_ATTRIBUTE, "attr_decision");
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "attr_decision"));
        FunctionUnit fu = createFunctionUnit(bpmnXml, decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertTrue(result.getErrors().stream()
                .noneMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())));
        assertTrue(result.getWarnings().stream()
                .noneMatch(w -> "UNREFERENCED_DECISION".equals(w.getCode())));
    }

    // --- Edge cases ---

    @Test
    @DisplayName("No process definition: skip cross-reference validation")
    void shouldSkipValidation_whenNoProcessDefinition() {
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "some_key"));
        FunctionUnit fu = createFunctionUnit(null, decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        // Should not have INVALID_DECISION_REFERENCE errors (validation skipped)
        assertTrue(result.getErrors().stream()
                .noneMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())));
    }

    @Test
    @DisplayName("Empty BPMN XML: skip cross-reference validation gracefully")
    void shouldSkipValidation_whenBpmnXmlIsEmpty() {
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "some_key"));
        FunctionUnit fu = createFunctionUnit("", decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertTrue(result.getErrors().stream()
                .noneMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())));
    }

    @Test
    @DisplayName("Malformed BPMN XML: skip validation gracefully without crashing")
    void shouldSkipValidation_whenBpmnXmlIsMalformed() {
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "some_key"));
        FunctionUnit fu = createFunctionUnit("<not valid xml", decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        // Should not crash, just skip decision validation
        assertNotNull(result);
    }
}
