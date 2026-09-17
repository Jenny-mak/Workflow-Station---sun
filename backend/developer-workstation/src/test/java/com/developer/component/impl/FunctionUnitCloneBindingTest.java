package com.developer.component.impl;

import com.developer.entity.FormDefinition;
import com.developer.entity.FormTableBinding;
import com.developer.entity.FunctionUnit;
import com.developer.entity.ProcessDefinition;
import com.developer.entity.TableDefinition;
import com.developer.util.BpmnProcessIdRewriter;
import com.developer.util.XmlEncodingUtil;
import com.developer.enums.BindingMode;
import com.developer.enums.BindingType;
import com.developer.enums.FormType;
import com.developer.enums.TableType;
import com.developer.repository.*;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.service.UserDisplayNameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FunctionUnitCloneBindingTest {

    @Mock private FunctionUnitRepository functionUnitRepository;
    @Mock private ProcessDefinitionRepository processDefinitionRepository;
    @Mock private TableDefinitionRepository tableDefinitionRepository;
    @Mock private FormDefinitionRepository formDefinitionRepository;
    @Mock private ActionDefinitionRepository actionDefinitionRepository;
    @Mock private DecisionDefinitionRepository decisionDefinitionRepository;
    @Mock private FormTableBindingRepository formTableBindingRepository;
    @Mock private FormStageBindingRepository formStageBindingRepository;
    @Mock private TableRelationRepository tableRelationRepository;
    @Mock private SubTableViewConfigRepository subTableViewConfigRepository;
    @Mock private VersionRepository versionRepository;
    @Mock private IconRepository iconRepository;
    @Mock private UserDisplayNameService userDisplayNameService;
    @Mock private FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService;
    @Mock private FunctionUnitDevGroupAssignmentRepository functionUnitDevGroupAssignmentRepository;
    @Mock private com.developer.component.VersionComponent versionComponent;
    @Mock private com.developer.util.DeveloperWorkstationSequenceSynchronizer sequenceSynchronizer;
    @Mock private com.developer.component.TableDesignComponent tableDesignComponent;

    private FunctionUnitComponentImpl component;

    @BeforeEach
    void setUp() {
        lenient().when(functionUnitDevGroupAssignmentRepository.findByFunctionUnitId(anyLong()))
                .thenReturn(List.of());
        lenient().when(tableDesignComponent.isTableNameAvailable(anyString(), isNull()))
                .thenReturn(true);
        component = new FunctionUnitComponentImpl(
                functionUnitRepository,
                processDefinitionRepository,
                tableDefinitionRepository,
                formDefinitionRepository,
                actionDefinitionRepository,
                decisionDefinitionRepository,
                formTableBindingRepository,
                formStageBindingRepository,
                tableRelationRepository,
                subTableViewConfigRepository,
                versionRepository,
                iconRepository,
                new ObjectMapper(),
                userDisplayNameService,
                functionUnitWorkspaceAccessService,
                functionUnitDevGroupAssignmentRepository,
                versionComponent,
                sequenceSynchronizer,
                mock(com.developer.service.MainTableViewService.class),
                mock(com.developer.repository.ForeignKeyRepository.class),
                mock(com.developer.component.impl.FunctionUnitExporter.class),
                tableDesignComponent,
                mock(com.developer.repository.EmailConnectionRepository.class),
                mock(com.developer.repository.EmailMonitorRuleRepository.class),
                mock(com.developer.repository.EmailTemplateRepository.class),
                new com.developer.service.impl.FunctionUnitDocumentService(
                org.mockito.Mockito.mock(com.developer.repository.AiDocumentRepository.class)));
    }

    @Test
    void clone_remapsBindingIdsAndCopiesRelationTableId() {
        FunctionUnit source = FunctionUnit.builder().id(1L).name("Source").build();

        TableDefinition mainTable = TableDefinition.builder()
                .id(10L).functionUnit(source).tableName("Main").tableType(TableType.MAIN).build();
        mainTable.setFieldDefinitions(new ArrayList<>());

        FormTableBinding primaryBinding = FormTableBinding.builder()
                .id(101L).bindingType(BindingType.PRIMARY).bindingMode(BindingMode.EDITABLE)
                .table(mainTable).sortOrder(0).build();
        FormTableBinding relatedBinding = FormTableBinding.builder()
                .id(102L).bindingType(BindingType.RELATED).bindingMode(BindingMode.READONLY)
                .relationTableId(999L).foreignKeyField("user_id").sortOrder(1).build();

        Map<String, Object> configJson = new HashMap<>();
        configJson.put("rule", List.of(Map.of("type", "subTable", "_bindingId", 101, "title", "Canvas Sub")));
        configJson.put("subForms", new LinkedHashMap<>(Map.of("101", Map.of("title", "Main sub"))));
        configJson.put("relationViews", new LinkedHashMap<>(Map.of("102", Map.of("columns", List.of()))));

        FormDefinition sourceForm = FormDefinition.builder()
                .id(11L).functionUnit(source).formName("MainForm").formType(FormType.PROCESS)
                .configJson(configJson).showLiveValues(true).build();
        sourceForm.setTableBindings(List.of(primaryBinding, relatedBinding));

        String sourceBpmn = """
                <?xml version="1.0"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI">
                  <bpmn:process id="Process_1_xx" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1" />
                  </bpmn:process>
                  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
                    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1_xx" />
                  </bpmndi:BPMNDiagram>
                </bpmn:definitions>
                """;
        ProcessDefinition sourceProcess = ProcessDefinition.builder()
                .id(50L)
                .functionUnit(source)
                .bpmnXml(sourceBpmn)
                .build();
        source.setProcessDefinition(sourceProcess);

        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(source));
        when(functionUnitRepository.existsByName("Cloned")).thenReturn(false);
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenAnswer(inv -> {
            FunctionUnit fu = inv.getArgument(0);
            if (fu.getId() == null) {
                fu.setId(2L);
            }
            return fu;
        });
        when(tableDefinitionRepository.findByFunctionUnitIdWithFields(1L)).thenReturn(List.of(mainTable));
        when(formDefinitionRepository.findByFunctionUnitIdWithBindings(1L)).thenReturn(List.of(sourceForm));
        when(tableRelationRepository.findByFunctionUnitId(1L)).thenReturn(List.of());
        when(tableDefinitionRepository.save(any(TableDefinition.class))).thenAnswer(inv -> {
            TableDefinition t = inv.getArgument(0);
            t.setId(20L);
            return t;
        });
        when(formDefinitionRepository.save(any(FormDefinition.class))).thenAnswer(inv -> {
            FormDefinition f = inv.getArgument(0);
            if (f.getId() == null) {
                f.setId(21L);
            }
            return f;
        });
        when(formTableBindingRepository.findByFormIdWithTable(11L))
                .thenReturn(List.of(primaryBinding, relatedBinding));
        when(formStageBindingRepository.findByFormId(11L)).thenReturn(List.of());
        when(formTableBindingRepository.save(any(FormTableBinding.class))).thenAnswer(inv -> {
            FormTableBinding b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId(b.getBindingType() == BindingType.PRIMARY ? 501L : 502L);
            }
            return b;
        });

        component.clone(1L, "Cloned");

        ArgumentCaptor<FunctionUnit> functionUnitCaptor = ArgumentCaptor.forClass(FunctionUnit.class);
        verify(functionUnitRepository).save(functionUnitCaptor.capture());
        String clonedCode = functionUnitCaptor.getValue().getCode();
        assertNotNull(clonedCode);
        assertFalse(clonedCode.isBlank());

        ArgumentCaptor<ProcessDefinition> processCaptor = ArgumentCaptor.forClass(ProcessDefinition.class);
        verify(processDefinitionRepository).save(processCaptor.capture());
        String clonedBpmn = XmlEncodingUtil.smartDecode(processCaptor.getValue().getBpmnXml());
        assertEquals(clonedCode, BpmnProcessIdRewriter.extractProcessId(clonedBpmn));
        assertFalse(clonedBpmn.contains("Process_1_xx"));

        ArgumentCaptor<FormTableBinding> bindingCaptor = ArgumentCaptor.forClass(FormTableBinding.class);
        verify(formTableBindingRepository, times(2)).save(bindingCaptor.capture());
        FormTableBinding savedRelated = bindingCaptor.getAllValues().stream()
                .filter(b -> b.getBindingType() == BindingType.RELATED)
                .findFirst()
                .orElseThrow();
        assertEquals(999L, savedRelated.getRelationTableId());
        assertNull(savedRelated.getTable());

        ArgumentCaptor<FormDefinition> formCaptor = ArgumentCaptor.forClass(FormDefinition.class);
        verify(formDefinitionRepository, atLeast(2)).save(formCaptor.capture());
        FormDefinition finalForm = formCaptor.getAllValues().get(formCaptor.getAllValues().size() - 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> subForms = (Map<String, Object>) finalForm.getConfigJson().get("subForms");
        assertTrue(subForms.containsKey("501"));
        assertFalse(subForms.containsKey("101"));
        @SuppressWarnings("unchecked")
        Map<String, Object> relationViews = (Map<String, Object>) finalForm.getConfigJson().get("relationViews");
        assertTrue(relationViews.containsKey("502"));
        assertFalse(relationViews.containsKey("102"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rule = (List<Map<String, Object>>) finalForm.getConfigJson().get("rule");
        assertEquals(501L, ((Number) rule.get(0).get("_bindingId")).longValue());
    }
}
