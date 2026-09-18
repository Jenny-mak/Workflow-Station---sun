package com.developer.property;

import com.developer.component.impl.FunctionUnitComponentImpl;
import com.developer.dto.ValidationResult;
import com.developer.entity.DecisionDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.entity.ProcessDefinition;
import com.developer.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeProperty;
import com.developer.service.UserDisplayNameService;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * BPMN-DMN 交叉引用验证属性测试
 * Feature: dmn-decision-table-integration
 *
 * Property 11: BPMN-DMN 交叉引用验证
 *
 * Validates: Requirements 10.2, 10.3, 10.4
 */
public class BpmnDmnCrossReferencePropertyTest {

    private FunctionUnitRepository functionUnitRepository;
    private FunctionUnitComponentImpl functionUnitComponent;
    private AtomicLong idGenerator;

    @BeforeProperty
    void setUp() {
        functionUnitRepository = mock(FunctionUnitRepository.class);
        FunctionUnitDevGroupAssignmentRepository devGroupAssignmentRepository = mock(FunctionUnitDevGroupAssignmentRepository.class);
        when(devGroupAssignmentRepository.findByFunctionUnitId(anyLong())).thenReturn(List.of());
        functionUnitComponent = new FunctionUnitComponentImpl(
                functionUnitRepository,
                mock(ProcessDefinitionRepository.class),
                mock(TableDefinitionRepository.class),
                mock(FormDefinitionRepository.class),
                mock(ActionDefinitionRepository.class),
                mock(DecisionDefinitionRepository.class),
                mock(FormTableBindingRepository.class),
                mock(FormStageBindingRepository.class),
                mock(TableRelationRepository.class),
                mock(SubTableViewConfigRepository.class),
                mock(VersionRepository.class),
                mock(IconRepository.class),
                new ObjectMapper(),
                mock(UserDisplayNameService.class),
                mock(com.developer.security.FunctionUnitWorkspaceAccessService.class),
                devGroupAssignmentRepository,
                mock(com.developer.component.VersionComponent.class),
                mock(com.developer.util.DeveloperWorkstationSequenceSynchronizer.class),
                mock(com.developer.service.MainTableViewService.class),
                mock(com.developer.repository.ForeignKeyRepository.class),
                mock(com.developer.component.impl.FunctionUnitExporter.class),
                mock(com.developer.component.TableDesignComponent.class),
                mock(com.developer.repository.EmailConnectionRepository.class),
                mock(com.developer.repository.EmailMonitorRuleRepository.class),
                mock(com.developer.repository.EmailTemplateRepository.class),
                new com.developer.service.impl.FunctionUnitDocumentService(
                org.mockito.Mockito.mock(com.developer.repository.AiDocumentRepository.class),
                org.mockito.Mockito.mock(com.developer.repository.AiStudioThreadStateRepository.class))
        );
        idGenerator = new AtomicLong(1L);
    }

    // ========== Property 11a: INVALID_DECISION_REFERENCE for broken references ==========

    /**
     * Property 11a: For any FunctionUnit whose BPMN XML contains DMN service tasks
     * referencing decision keys NOT present in the DecisionDefinition list,
     * validate() should return INVALID_DECISION_REFERENCE errors for each missing key.
     *
     * **Validates: Requirements 10.2**
     */
    @Property(tries = 100)
    void invalidDecisionReferenceWhenKeyNotInDefinitions(
            @ForAll("referencedKeyLists") List<String> referencedKeys,
            @ForAll("definedKeyLists") List<String> definedKeys) {

        // Ensure at least one referenced key is NOT in definedKeys
        Set<String> definedSet = new HashSet<>(definedKeys);
        Set<String> referencedSet = new HashSet<>(referencedKeys);

        // Add a key that is guaranteed to be missing from definitions
        String missingKey = "missing_key_" + UUID.randomUUID().toString().substring(0, 8);
        referencedSet.add(missingKey);

        // Build BPMN XML with all referenced keys
        String bpmnXml = buildBpmnXmlWithDmnTasks(referencedSet);

        // Build DecisionDefinitions from definedKeys (must be non-empty for validation to run)
        List<DecisionDefinition> decisions = new ArrayList<>();
        // Always include at least one definition so the validation logic runs
        decisions.add(buildDecision("anchor_key_" + UUID.randomUUID().toString().substring(0, 8)));
        for (String key : definedSet) {
            decisions.add(buildDecision(key));
        }

        FunctionUnit fu = buildFunctionUnit(bpmnXml, decisions);
        when(functionUnitRepository.findById(fu.getId())).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(fu.getId());

        // The missingKey should always produce an INVALID_DECISION_REFERENCE error
        assertThat(result.getErrors().stream()
                .filter(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode()))
                .anyMatch(e -> e.getMessage().contains(missingKey)))
                .as("Should have INVALID_DECISION_REFERENCE error for missing key '%s'", missingKey)
                .isTrue();

        // Every referenced key not in the defined set should produce an error
        Set<String> allDefinedKeys = decisions.stream()
                .map(DecisionDefinition::getDecisionKey)
                .collect(Collectors.toSet());
        Set<String> expectedMissing = referencedSet.stream()
                .filter(k -> !allDefinedKeys.contains(k))
                .collect(Collectors.toSet());

        Set<String> actualErrorKeys = result.getErrors().stream()
                .filter(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode()))
                .map(ValidationResult.ValidationError::getElementId)
                .collect(Collectors.toSet());

        assertThat(actualErrorKeys)
                .as("All referenced keys not in definitions should produce INVALID_DECISION_REFERENCE")
                .containsAll(expectedMissing);
    }

    // ========== Property 11b: UNREFERENCED_DECISION warning ==========

    /**
     * Property 11b: For any FunctionUnit with DecisionDefinitions that are NOT
     * referenced by any BPMN DMN service task, validate() should return
     * UNREFERENCED_DECISION warnings for each unreferenced definition.
     *
     * **Validates: Requirements 10.3**
     */
    @Property(tries = 100)
    void unreferencedDecisionWarningWhenDefinitionNotInBpmn(
            @ForAll("referencedKeyLists") List<String> referencedKeys,
            @ForAll("definedKeyLists") List<String> definedKeys) {

        Set<String> referencedSet = new HashSet<>(referencedKeys);

        // Add a defined key that is guaranteed to be unreferenced
        String unreferencedKey = "unreferenced_key_" + UUID.randomUUID().toString().substring(0, 8);
        Set<String> definedSet = new HashSet<>(definedKeys);
        definedSet.add(unreferencedKey);

        // Build BPMN XML with referenced keys only
        String bpmnXml = referencedSet.isEmpty()
                ? buildBpmnXmlWithoutDmn()
                : buildBpmnXmlWithDmnTasks(referencedSet);

        // Build DecisionDefinitions from definedSet
        List<DecisionDefinition> decisions = definedSet.stream()
                .map(this::buildDecision)
                .collect(Collectors.toList());

        FunctionUnit fu = buildFunctionUnit(bpmnXml, decisions);
        when(functionUnitRepository.findById(fu.getId())).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(fu.getId());

        // The unreferencedKey should produce an UNREFERENCED_DECISION warning
        assertThat(result.getWarnings().stream()
                .filter(w -> "UNREFERENCED_DECISION".equals(w.getCode()))
                .anyMatch(w -> w.getMessage().contains(unreferencedKey)))
                .as("Should have UNREFERENCED_DECISION warning for unreferenced key '%s'", unreferencedKey)
                .isTrue();

        // Every defined key not in the referenced set should produce a warning
        Set<String> expectedUnreferenced = definedSet.stream()
                .filter(k -> !referencedSet.contains(k))
                .collect(Collectors.toSet());

        Set<String> actualWarningKeys = result.getWarnings().stream()
                .filter(w -> "UNREFERENCED_DECISION".equals(w.getCode()))
                .map(ValidationResult.ValidationWarning::getElementId)
                .collect(Collectors.toSet());

        assertThat(actualWarningKeys)
                .as("All defined keys not referenced by BPMN should produce UNREFERENCED_DECISION")
                .containsAll(expectedUnreferenced);
    }

    // ========== Property 11c: No decision errors when no DecisionDefinitions ==========

    /**
     * Property 11c: For any FunctionUnit with NO DecisionDefinitions,
     * validate() should NOT produce any INVALID_DECISION_REFERENCE errors
     * or UNREFERENCED_DECISION warnings, regardless of BPMN content.
     *
     * **Validates: Requirements 10.4**
     */
    @Property(tries = 100)
    void noDecisionErrorsWhenNoDecisionDefinitions(
            @ForAll("referencedKeyLists") List<String> referencedKeys) {

        Set<String> referencedSet = new HashSet<>(referencedKeys);

        // Build BPMN XML that references decision keys
        String bpmnXml = referencedSet.isEmpty()
                ? buildBpmnXmlWithoutDmn()
                : buildBpmnXmlWithDmnTasks(referencedSet);

        // Empty DecisionDefinition list
        FunctionUnit fu = buildFunctionUnit(bpmnXml, new ArrayList<>());
        when(functionUnitRepository.findById(fu.getId())).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(fu.getId());

        assertThat(result.getErrors().stream()
                .noneMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())))
                .as("No INVALID_DECISION_REFERENCE errors when no DecisionDefinitions exist")
                .isTrue();

        assertThat(result.getWarnings().stream()
                .noneMatch(w -> "UNREFERENCED_DECISION".equals(w.getCode())))
                .as("No UNREFERENCED_DECISION warnings when no DecisionDefinitions exist")
                .isTrue();
    }

    // ========== Property 11d: All matched — no decision errors or warnings ==========

    /**
     * Property 11d: When all BPMN-referenced keys exactly match all defined keys,
     * there should be no INVALID_DECISION_REFERENCE errors and no UNREFERENCED_DECISION warnings.
     *
     * **Validates: Requirements 10.2, 10.3**
     */
    @Property(tries = 100)
    void noDecisionIssuesWhenAllKeysMatch(
            @ForAll("definedKeyLists") List<String> keys) {

        Set<String> keySet = new HashSet<>(keys);
        if (keySet.isEmpty()) {
            return; // Skip trivial case — covered by 11c
        }

        String bpmnXml = buildBpmnXmlWithDmnTasks(keySet);
        List<DecisionDefinition> decisions = keySet.stream()
                .map(this::buildDecision)
                .collect(Collectors.toList());

        FunctionUnit fu = buildFunctionUnit(bpmnXml, decisions);
        when(functionUnitRepository.findById(fu.getId())).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(fu.getId());

        assertThat(result.getErrors().stream()
                .noneMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())))
                .as("No INVALID_DECISION_REFERENCE errors when all keys match")
                .isTrue();

        assertThat(result.getWarnings().stream()
                .noneMatch(w -> "UNREFERENCED_DECISION".equals(w.getCode())))
                .as("No UNREFERENCED_DECISION warnings when all keys match")
                .isTrue();
    }

    // ========== Generators ==========

    @Provide
    Arbitrary<List<String>> referencedKeyLists() {
        return validDecisionKeys().list().ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<String>> definedKeyLists() {
        return validDecisionKeys().list().ofMinSize(0).ofMaxSize(5);
    }

    private Arbitrary<String> validDecisionKeys() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(15)
                .map(s -> "dk_" + s);
    }

    // ========== Helper Methods ==========

    private FunctionUnit buildFunctionUnit(String bpmnXml, List<DecisionDefinition> decisions) {
        Long fuId = idGenerator.getAndIncrement();
        FunctionUnit fu = FunctionUnit.builder()
                .id(fuId)
                .name("TestFU_" + fuId)
                .code("fu-test-" + fuId)
                .decisionDefinitions(decisions != null ? decisions : new ArrayList<>())
                .tableDefinitions(new ArrayList<>())
                .formDefinitions(new ArrayList<>())
                .actionDefinitions(new ArrayList<>())
                .build();

        if (bpmnXml != null) {
            ProcessDefinition pd = ProcessDefinition.builder()
                    .id(idGenerator.getAndIncrement())
                    .functionUnit(fu)
                    .bpmnXml(bpmnXml)
                    .build();
            fu.setProcessDefinition(pd);
        }

        return fu;
    }

    private DecisionDefinition buildDecision(String key) {
        return DecisionDefinition.builder()
                .id(idGenerator.getAndIncrement())
                .decisionKey(key)
                .decisionName("Decision " + key)
                .hitPolicy("FIRST")
                .build();
    }

    /**
     * Build a BPMN XML string containing DMN service tasks for each key.
     * Uses the extension elements format (flowable:field) which is the primary format.
     */
    private String buildBpmnXmlWithDmnTasks(Set<String> decisionKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n");
        sb.append("             xmlns:flowable=\"http://flowable.org/bpmn\"\n");
        sb.append("             targetNamespace=\"http://test\">\n");
        sb.append("  <process id=\"testProcess\" name=\"Test\">\n");

        int taskIndex = 0;
        for (String key : decisionKeys) {
            sb.append("    <serviceTask id=\"dmnTask").append(taskIndex)
              .append("\" name=\"Evaluate ").append(escapeXml(key))
              .append("\" flowable:type=\"dmn\">\n");
            sb.append("      <extensionElements>\n");
            sb.append("        <flowable:field name=\"decisionTableReferenceKey\">\n");
            sb.append("          <flowable:string><![CDATA[").append(key).append("]]></flowable:string>\n");
            sb.append("        </flowable:field>\n");
            sb.append("      </extensionElements>\n");
            sb.append("    </serviceTask>\n");
            taskIndex++;
        }

        sb.append("  </process>\n");
        sb.append("</definitions>");
        return sb.toString();
    }

    /**
     * Build a BPMN XML string with no DMN service tasks.
     */
    private String buildBpmnXmlWithoutDmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             targetNamespace="http://test">
                  <process id="testProcess" name="Test">
                    <startEvent id="start" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """;
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
    }
}
