package com.developer.property;

import com.developer.component.impl.FunctionUnitComponentImpl;
import com.developer.dto.ValidationResult;
import com.developer.entity.ActionDefinition;
import com.developer.entity.DecisionDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.enums.ActionType;
import com.developer.repository.*;
import com.developer.security.FunctionUnitWorkspaceAccessService;
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
 * DECISION_TABLE 动作配置验证属性测试
 * Feature: dmn-decision-table-integration
 *
 * Property 12: DECISION_TABLE 动作配置验证
 *
 * Validates: Requirements 11.2, 11.3
 */
public class DecisionTableActionPropertyTest {

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
                mock(FunctionUnitWorkspaceAccessService.class),
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

    // ========== Property 12a: Missing required fields produce validation errors ==========

    /**
     * Property 12a: For any DECISION_TABLE ActionDefinition whose config_json is missing
     * one or more of {decisionKey, inputMappings, outputMappings}, validate() should
     * return the corresponding MISSING_* error codes.
     *
     * **Validates: Requirements 11.2**
     */
    @Property(tries = 100)
    void missingRequiredFieldsProduceValidationErrors(
            @ForAll("missingFieldCombinations") Set<String> missingFields) {

        // Build config_json with some fields missing
        Map<String, Object> config = new HashMap<>();
        // Always add a non-required field so config is non-empty
        config.put("someOtherField", "value");

        String decisionKey = "dk_" + UUID.randomUUID().toString().substring(0, 8);

        if (!missingFields.contains("decisionKey")) {
            config.put("decisionKey", decisionKey);
        }
        if (!missingFields.contains("inputMappings")) {
            config.put("inputMappings", Map.of("amount", "form.amount"));
        }
        if (!missingFields.contains("outputMappings")) {
            config.put("outputMappings", Map.of("riskLevel", "form.riskLevel"));
        }

        // Create a decision definition matching the key (if present) so we isolate missing-field errors
        List<DecisionDefinition> decisions = new ArrayList<>();
        if (!missingFields.contains("decisionKey")) {
            decisions.add(buildDecision(decisionKey));
        }

        ActionDefinition action = buildDecisionTableAction("TestAction", config);
        FunctionUnit fu = buildFunctionUnit(List.of(action), decisions);
        when(functionUnitRepository.findById(fu.getId())).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(fu.getId());

        // Verify each missing field produces the correct error
        if (missingFields.contains("decisionKey")) {
            assertThat(result.getErrors().stream()
                    .anyMatch(e -> "MISSING_DECISION_KEY".equals(e.getCode())))
                    .as("Should have MISSING_DECISION_KEY error when decisionKey is missing")
                    .isTrue();
        }
        if (missingFields.contains("inputMappings")) {
            assertThat(result.getErrors().stream()
                    .anyMatch(e -> "MISSING_INPUT_MAPPINGS".equals(e.getCode())))
                    .as("Should have MISSING_INPUT_MAPPINGS error when inputMappings is missing")
                    .isTrue();
        }
        if (missingFields.contains("outputMappings")) {
            assertThat(result.getErrors().stream()
                    .anyMatch(e -> "MISSING_OUTPUT_MAPPINGS".equals(e.getCode())))
                    .as("Should have MISSING_OUTPUT_MAPPINGS error when outputMappings is missing")
                    .isTrue();
        }

        // Result should be invalid when any field is missing
        assertThat(result.isValid())
                .as("Validation should fail when required fields are missing")
                .isFalse();
    }

    // ========== Property 12b: Invalid decision key reference produces error ==========

    /**
     * Property 12b: For any DECISION_TABLE ActionDefinition whose config_json contains
     * a decisionKey that does NOT reference an existing DecisionDefinition in the same
     * FunctionUnit, validate() should return INVALID_DECISION_REFERENCE error.
     *
     * **Validates: Requirements 11.3**
     */
    @Property(tries = 100)
    void invalidDecisionKeyReferenceProducesError(
            @ForAll("validDecisionKeys") String referencedKey,
            @ForAll("validDecisionKeyLists") List<String> definedKeys) {

        // Ensure the referenced key is NOT in the defined keys
        Set<String> definedSet = new HashSet<>(definedKeys);
        String missingKey = referencedKey + "_missing_" + UUID.randomUUID().toString().substring(0, 6);

        Map<String, Object> config = new HashMap<>();
        config.put("decisionKey", missingKey);
        config.put("inputMappings", Map.of("a", "b"));
        config.put("outputMappings", Map.of("c", "d"));

        List<DecisionDefinition> decisions = definedSet.stream()
                .map(this::buildDecision)
                .collect(Collectors.toList());

        ActionDefinition action = buildDecisionTableAction("BadRefAction", config);
        FunctionUnit fu = buildFunctionUnit(List.of(action), decisions);
        when(functionUnitRepository.findById(fu.getId())).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(fu.getId());

        assertThat(result.isValid())
                .as("Validation should fail when decisionKey references non-existent definition")
                .isFalse();

        assertThat(result.getErrors().stream()
                .filter(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode()))
                .anyMatch(e -> e.getMessage().contains(missingKey)))
                .as("Should have INVALID_DECISION_REFERENCE error for missing key '%s'", missingKey)
                .isTrue();
    }

    // ========== Property 12c: Valid config with existing key produces no decision action errors ==========

    /**
     * Property 12c: For any DECISION_TABLE ActionDefinition whose config_json contains
     * all required fields (decisionKey, inputMappings, outputMappings) and the decisionKey
     * references an existing DecisionDefinition, validate() should NOT produce any
     * MISSING_DECISION_KEY, MISSING_INPUT_MAPPINGS, MISSING_OUTPUT_MAPPINGS,
     * MISSING_DECISION_CONFIG, or INVALID_DECISION_REFERENCE errors for that action.
     *
     * **Validates: Requirements 11.2, 11.3**
     */
    @Property(tries = 100)
    void validConfigWithExistingKeyProducesNoErrors(
            @ForAll("validDecisionKeys") String decisionKey) {

        Map<String, Object> config = new HashMap<>();
        config.put("decisionKey", decisionKey);
        config.put("inputMappings", Map.of("amount", "form.amount"));
        config.put("outputMappings", Map.of("riskLevel", "form.riskLevel"));

        List<DecisionDefinition> decisions = List.of(buildDecision(decisionKey));
        ActionDefinition action = buildDecisionTableAction("ValidAction", config);
        FunctionUnit fu = buildFunctionUnit(List.of(action), decisions);
        when(functionUnitRepository.findById(fu.getId())).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(fu.getId());

        Set<String> decisionActionErrorCodes = Set.of(
                "MISSING_DECISION_KEY", "MISSING_INPUT_MAPPINGS", "MISSING_OUTPUT_MAPPINGS",
                "MISSING_DECISION_CONFIG", "INVALID_DECISION_REFERENCE");

        assertThat(result.getErrors().stream()
                .noneMatch(e -> decisionActionErrorCodes.contains(e.getCode())))
                .as("No decision action validation errors when config is valid and key exists")
                .isTrue();
    }

    // ========== Property 12d: Non-DECISION_TABLE actions are not validated for decision config ==========

    /**
     * Property 12d: For any ActionDefinition whose actionType is NOT DECISION_TABLE,
     * validate() should NOT produce MISSING_DECISION_KEY, MISSING_INPUT_MAPPINGS,
     * MISSING_OUTPUT_MAPPINGS, MISSING_DECISION_CONFIG, or INVALID_DECISION_REFERENCE errors,
     * regardless of config_json content.
     *
     * **Validates: Requirements 11.2**
     */
    @Property(tries = 100)
    void nonDecisionTableActionsNotValidatedForDecisionConfig(
            @ForAll("nonDecisionTableActionTypes") ActionType actionType) {

        // Use an empty config that would fail DECISION_TABLE validation
        ActionDefinition action = ActionDefinition.builder()
                .id(idGenerator.getAndIncrement())
                .actionName("NonDTAction")
                .actionType(actionType)
                .configJson(new HashMap<>())
                .build();

        FunctionUnit fu = buildFunctionUnit(List.of(action), new ArrayList<>());
        when(functionUnitRepository.findById(fu.getId())).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(fu.getId());

        Set<String> decisionActionErrorCodes = Set.of(
                "MISSING_DECISION_KEY", "MISSING_INPUT_MAPPINGS", "MISSING_OUTPUT_MAPPINGS",
                "MISSING_DECISION_CONFIG", "INVALID_DECISION_REFERENCE");

        assertThat(result.getErrors().stream()
                .noneMatch(e -> decisionActionErrorCodes.contains(e.getCode())))
                .as("Non-DECISION_TABLE actions should not produce decision config errors")
                .isTrue();
    }

    // ========== Generators ==========

    @Provide
    Arbitrary<Set<String>> missingFieldCombinations() {
        Set<String> allFields = Set.of("decisionKey", "inputMappings", "outputMappings");
        // Generate non-empty subsets of the required fields
        return Arbitraries.of(allFields)
                .set()
                .ofMinSize(1)
                .ofMaxSize(3);
    }

    @Provide
    Arbitrary<String> validDecisionKeys() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(15)
                .map(s -> "dk_" + s);
    }

    @Provide
    Arbitrary<List<String>> validDecisionKeyLists() {
        return validDecisionKeys().list().ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<ActionType> nonDecisionTableActionTypes() {
        return Arbitraries.of(
                Arrays.stream(ActionType.values())
                        .filter(t -> t != ActionType.DECISION_TABLE)
                        .toArray(ActionType[]::new));
    }

    // ========== Helper Methods ==========

    private FunctionUnit buildFunctionUnit(List<ActionDefinition> actions, List<DecisionDefinition> decisions) {
        Long fuId = idGenerator.getAndIncrement();
        return FunctionUnit.builder()
                .id(fuId)
                .name("TestFU_" + fuId)
                .code("fu-test-" + fuId)
                .actionDefinitions(actions != null ? actions : new ArrayList<>())
                .decisionDefinitions(decisions != null ? decisions : new ArrayList<>())
                .tableDefinitions(new ArrayList<>())
                .formDefinitions(new ArrayList<>())
                .build();
    }

    private DecisionDefinition buildDecision(String key) {
        return DecisionDefinition.builder()
                .id(idGenerator.getAndIncrement())
                .decisionKey(key)
                .decisionName("Decision " + key)
                .hitPolicy("FIRST")
                .build();
    }

    private ActionDefinition buildDecisionTableAction(String name, Map<String, Object> configJson) {
        return ActionDefinition.builder()
                .id(idGenerator.getAndIncrement())
                .actionName(name)
                .actionType(ActionType.DECISION_TABLE)
                .configJson(configJson)
                .build();
    }
}
