package com.developer.component.impl;

import com.developer.dto.ValidationResult;
import com.developer.entity.ActionDefinition;
import com.developer.entity.DecisionDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.enums.ActionType;
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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * DECISION_TABLE 动作配置验证单元测试
 * 验证需求 11.2, 11.3, 11.4
 */
@ExtendWith(MockitoExtension.class)
class DecisionTableActionValidationTest {

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

    private FunctionUnit createFunctionUnit(List<ActionDefinition> actions, List<DecisionDefinition> decisions) {
        FunctionUnit fu = FunctionUnit.builder()
                .id(1L)
                .name("Test FU")
                .code("fu-test-001")
                .build();
        fu.setActionDefinitions(actions != null ? actions : new ArrayList<>());
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

    private ActionDefinition createDecisionTableAction(String name, Map<String, Object> configJson) {
        return ActionDefinition.builder()
                .id(1L)
                .actionName(name)
                .actionType(ActionType.DECISION_TABLE)
                .configJson(configJson)
                .build();
    }

    private Map<String, Object> validConfig(String decisionKey) {
        Map<String, Object> config = new HashMap<>();
        config.put("decisionKey", decisionKey);
        config.put("inputMappings", Map.of("amount", "form.amount"));
        config.put("outputMappings", Map.of("riskLevel", "form.riskLevel"));
        return config;
    }

    // --- Requirement 11.2: config_json must contain decisionKey, inputMappings, outputMappings ---

    @Test
    @DisplayName("Valid DECISION_TABLE action with all required fields and existing decision key")
    void shouldPassValidation_whenConfigIsCompleteAndDecisionKeyExists() {
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "risk_assessment"));
        ActionDefinition action = createDecisionTableAction("Evaluate Risk", validConfig("risk_assessment"));
        FunctionUnit fu = createFunctionUnit(List.of(action), decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertTrue(result.getErrors().stream()
                .noneMatch(e -> e.getCode().equals("MISSING_DECISION_KEY")
                        || e.getCode().equals("MISSING_INPUT_MAPPINGS")
                        || e.getCode().equals("MISSING_OUTPUT_MAPPINGS")
                        || e.getCode().equals("INVALID_DECISION_REFERENCE")
                        || e.getCode().equals("MISSING_DECISION_CONFIG")));
    }

    @Test
    @DisplayName("Error when config_json is missing decisionKey")
    void shouldReturnError_whenDecisionKeyMissing() {
        Map<String, Object> config = new HashMap<>();
        config.put("inputMappings", Map.of("a", "b"));
        config.put("outputMappings", Map.of("c", "d"));
        ActionDefinition action = createDecisionTableAction("Bad Action", config);
        FunctionUnit fu = createFunctionUnit(List.of(action), new ArrayList<>());
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "MISSING_DECISION_KEY".equals(e.getCode())));
    }

    @Test
    @DisplayName("Error when config_json is missing inputMappings")
    void shouldReturnError_whenInputMappingsMissing() {
        Map<String, Object> config = new HashMap<>();
        config.put("decisionKey", "risk_assessment");
        config.put("outputMappings", Map.of("c", "d"));
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "risk_assessment"));
        ActionDefinition action = createDecisionTableAction("Bad Action", config);
        FunctionUnit fu = createFunctionUnit(List.of(action), decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "MISSING_INPUT_MAPPINGS".equals(e.getCode())));
    }

    @Test
    @DisplayName("Error when config_json is missing outputMappings")
    void shouldReturnError_whenOutputMappingsMissing() {
        Map<String, Object> config = new HashMap<>();
        config.put("decisionKey", "risk_assessment");
        config.put("inputMappings", Map.of("a", "b"));
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "risk_assessment"));
        ActionDefinition action = createDecisionTableAction("Bad Action", config);
        FunctionUnit fu = createFunctionUnit(List.of(action), decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "MISSING_OUTPUT_MAPPINGS".equals(e.getCode())));
    }

    @Test
    @DisplayName("Error when config_json is null/empty")
    void shouldReturnError_whenConfigJsonIsEmpty() {
        ActionDefinition action = createDecisionTableAction("Empty Config", new HashMap<>());
        FunctionUnit fu = createFunctionUnit(List.of(action), new ArrayList<>());
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "MISSING_DECISION_CONFIG".equals(e.getCode())));
    }

    @Test
    @DisplayName("Multiple errors when all required fields are missing")
    void shouldReturnMultipleErrors_whenAllFieldsMissing() {
        Map<String, Object> config = new HashMap<>();
        config.put("someOtherField", "value");
        ActionDefinition action = createDecisionTableAction("Incomplete", config);
        FunctionUnit fu = createFunctionUnit(List.of(action), new ArrayList<>());
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "MISSING_DECISION_KEY".equals(e.getCode())));
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "MISSING_INPUT_MAPPINGS".equals(e.getCode())));
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "MISSING_OUTPUT_MAPPINGS".equals(e.getCode())));
    }

    // --- Requirement 11.3, 11.4: decisionKey must reference existing DecisionDefinition ---

    @Test
    @DisplayName("Error when decisionKey references non-existent DecisionDefinition")
    void shouldReturnError_whenDecisionKeyReferencesNonExistentDefinition() {
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "existing_key"));
        ActionDefinition action = createDecisionTableAction("Bad Ref", validConfig("nonexistent_key"));
        FunctionUnit fu = createFunctionUnit(List.of(action), decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())
                        && e.getMessage().contains("nonexistent_key")));
    }

    @Test
    @DisplayName("Error when decisionKey references decision but no decisions exist")
    void shouldReturnError_whenNoDecisionDefinitionsExist() {
        ActionDefinition action = createDecisionTableAction("No Decisions", validConfig("some_key"));
        FunctionUnit fu = createFunctionUnit(List.of(action), new ArrayList<>());
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())));
    }

    // --- Non-DECISION_TABLE actions should be ignored ---

    @Test
    @DisplayName("Non-DECISION_TABLE actions are not validated for decision config")
    void shouldNotValidate_nonDecisionTableActions() {
        ActionDefinition approveAction = ActionDefinition.builder()
                .id(2L)
                .actionName("Approve")
                .actionType(ActionType.APPROVE)
                .configJson(new HashMap<>())
                .build();
        FunctionUnit fu = createFunctionUnit(List.of(approveAction), new ArrayList<>());
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertTrue(result.getErrors().stream()
                .noneMatch(e -> "MISSING_DECISION_CONFIG".equals(e.getCode())
                        || "MISSING_DECISION_KEY".equals(e.getCode())
                        || "MISSING_INPUT_MAPPINGS".equals(e.getCode())
                        || "MISSING_OUTPUT_MAPPINGS".equals(e.getCode())
                        || "INVALID_DECISION_REFERENCE".equals(e.getCode())));
    }

    // --- Edge cases ---

    @Test
    @DisplayName("No errors when function unit has no actions")
    void shouldNotProduceErrors_whenNoActions() {
        FunctionUnit fu = createFunctionUnit(new ArrayList<>(), new ArrayList<>());
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertTrue(result.getErrors().stream()
                .noneMatch(e -> e.getCode().startsWith("MISSING_DECISION")
                        || "INVALID_DECISION_REFERENCE".equals(e.getCode())));
    }

    @Test
    @DisplayName("Error when decisionKey is blank string")
    void shouldReturnError_whenDecisionKeyIsBlank() {
        Map<String, Object> config = new HashMap<>();
        config.put("decisionKey", "   ");
        config.put("inputMappings", Map.of("a", "b"));
        config.put("outputMappings", Map.of("c", "d"));
        ActionDefinition action = createDecisionTableAction("Blank Key", config);
        FunctionUnit fu = createFunctionUnit(List.of(action), new ArrayList<>());
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "MISSING_DECISION_KEY".equals(e.getCode())));
    }

    @Test
    @DisplayName("Error when decisionKey is not a String type")
    void shouldReturnError_whenDecisionKeyIsNotString() {
        Map<String, Object> config = new HashMap<>();
        config.put("decisionKey", 12345);
        config.put("inputMappings", Map.of("a", "b"));
        config.put("outputMappings", Map.of("c", "d"));
        ActionDefinition action = createDecisionTableAction("Wrong Type", config);
        FunctionUnit fu = createFunctionUnit(List.of(action), new ArrayList<>());
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "MISSING_DECISION_KEY".equals(e.getCode())));
    }

    @Test
    @DisplayName("Multiple DECISION_TABLE actions: one valid, one invalid reference")
    void shouldValidateEachDecisionTableActionIndependently() {
        List<DecisionDefinition> decisions = List.of(createDecision(1L, "risk_assessment"));
        ActionDefinition validAction = createDecisionTableAction("Valid", validConfig("risk_assessment"));
        validAction.setId(1L);
        ActionDefinition invalidAction = createDecisionTableAction("Invalid", validConfig("nonexistent"));
        invalidAction.setId(2L);
        FunctionUnit fu = createFunctionUnit(List.of(validAction, invalidAction), decisions);
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));

        ValidationResult result = functionUnitComponent.validate(1L);

        assertFalse(result.isValid());
        // Only one INVALID_DECISION_REFERENCE error (for the invalid action)
        long refErrors = result.getErrors().stream()
                .filter(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode()))
                .count();
        assertEquals(1, refErrors);
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "INVALID_DECISION_REFERENCE".equals(e.getCode())
                        && e.getMessage().contains("nonexistent")));
    }
}
