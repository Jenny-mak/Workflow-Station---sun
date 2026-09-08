package com.developer.component.impl;

import com.developer.entity.FieldDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.entity.TableDefinition;
import com.developer.enums.DataType;
import com.developer.enums.TableType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.ActionDefinitionRepository;
import com.developer.repository.DecisionDefinitionRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FormStageBindingRepository;
import com.developer.repository.FunctionUnitRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.validation.DmnXmlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Computed-field formulas must survive export → import (and therefore version rollback,
 * which reuses {@link FunctionUnitImportWriter}). Legacy / incomplete serializers must
 * refuse rather than persist a formula without going through this path.
 */
@ExtendWith(MockitoExtension.class)
class FunctionUnitComputedFieldPortabilityTest {

    @Mock private FunctionUnitRepository functionUnitRepository;
    @Mock private TableDefinitionRepository tableDefinitionRepository;
    @Mock private FormDefinitionRepository formDefinitionRepository;
    @Mock private ActionDefinitionRepository actionDefinitionRepository;
    @Mock private DecisionDefinitionRepository decisionDefinitionRepository;
    @Mock private FormStageBindingRepository formStageBindingRepository;
    @Mock private TableRelationRepository tableRelationRepository;
    @Mock private FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService;

    private FunctionUnitExporter exporter;
    private FunctionUnitImportWriter importWriter;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        exporter = ExportImportTestComponents.exporter(
                functionUnitRepository,
                tableDefinitionRepository,
                formDefinitionRepository,
                actionDefinitionRepository,
                decisionDefinitionRepository,
                formStageBindingRepository,
                tableRelationRepository,
                functionUnitWorkspaceAccessService,
                objectMapper);
        importWriter = new FunctionUnitImportWriter(
                tableDefinitionRepository,
                formDefinitionRepository,
                actionDefinitionRepository,
                decisionDefinitionRepository,
                mock(com.developer.repository.EmailConnectionRepository.class),
                mock(com.developer.repository.EmailTemplateRepository.class),
                mock(com.developer.repository.FormTableBindingRepository.class),
                mock(com.developer.repository.LinkFormComponentRepository.class),
                mock(com.developer.repository.TableRelationRepository.class),
                mock(com.developer.repository.SubTableViewConfigRepository.class),
                mock(DmnXmlParser.class),
                objectMapper);
        lenient().when(tableDefinitionRepository.save(any(TableDefinition.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void exportImport_roundTripsComputedFieldDefinition() {
        Map<String, Object> formula = sampleFormula();
        FunctionUnit fu = FunctionUnit.builder().id(1L).name("Orders").code("orders").build();
        TableDefinition table = TableDefinition.builder()
                .id(10L).functionUnit(fu).tableName("order").tableType(TableType.MAIN).build();
        table.setFieldDefinitions(new ArrayList<>(List.of(
                FieldDefinition.builder()
                        .fieldName("qty").dataType(DataType.INTEGER).sortOrder(0).isComputed(false).build(),
                FieldDefinition.builder()
                        .fieldName("amount").dataType(DataType.DECIMAL).sortOrder(1)
                        .isComputed(true).computedFieldJson(formula).build())));

        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));
        when(tableDefinitionRepository.findByFunctionUnitIdWithFields(1L)).thenReturn(List.of(table));
        when(formDefinitionRepository.findByFunctionUnitIdWithBindings(1L)).thenReturn(List.of());
        when(actionDefinitionRepository.findByFunctionUnitId(1L)).thenReturn(List.of());
        when(decisionDefinitionRepository.findByFunctionUnitId(1L)).thenReturn(List.of());
        when(tableRelationRepository.findByFunctionUnitId(1L)).thenReturn(List.of());

        Map<String, Object> payload = exporter.buildVersionSnapshotPayload(1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) payload.get("tables");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) tables.get(0).get("fields");
        Map<String, Object> amount = fields.stream()
                .filter(f -> "amount".equals(f.get("fieldName")))
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.TRUE, amount.get("isComputed"));
        assertEquals(formula, amount.get("computedField"));

        TableDefinition imported = importWriter.importTable(fu, tables.get(0));
        FieldDefinition importedAmount = imported.getFieldDefinitions().stream()
                .filter(f -> "amount".equals(f.getFieldName()))
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.TRUE, importedAmount.getIsComputed());
        assertEquals(formula, importedAmount.getComputedFieldJson());
        assertNotSame(formula, importedAmount.getComputedFieldJson());

        FieldDefinition importedQty = imported.getFieldDefinitions().stream()
                .filter(f -> "qty".equals(f.getFieldName()))
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.FALSE, importedQty.getIsComputed());
        assertNull(importedQty.getComputedFieldJson());
    }

    @Test
    void import_rejectsComputedFlagWithoutFormula() {
        Map<String, Object> tableData = new LinkedHashMap<>();
        tableData.put("tableName", "order");
        tableData.put("tableType", "MAIN");
        tableData.put("fields", List.of(Map.of(
                "fieldName", "amount",
                "dataType", "DECIMAL",
                "isComputed", true)));

        DeveloperBusinessException ex = assertThrows(DeveloperBusinessException.class,
                () -> importWriter.importTable(FunctionUnit.builder().id(1L).build(), tableData));
        assertEquals("COMPUTED_FIELD_IMPORT_INVALID", ex.getErrorCode());
    }

    @Test
    void export_rejectsComputedFlagWithoutFormula() {
        FunctionUnit fu = FunctionUnit.builder().id(1L).name("Orders").code("orders").build();
        TableDefinition table = TableDefinition.builder()
                .id(10L).functionUnit(fu).tableName("order").tableType(TableType.MAIN).build();
        table.setFieldDefinitions(new ArrayList<>(List.of(
                FieldDefinition.builder()
                        .fieldName("amount").dataType(DataType.DECIMAL).sortOrder(0)
                        .isComputed(true).build())));

        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(fu));
        when(tableDefinitionRepository.findByFunctionUnitIdWithFields(1L)).thenReturn(List.of(table));
        when(formDefinitionRepository.findByFunctionUnitIdWithBindings(1L)).thenReturn(List.of());
        when(actionDefinitionRepository.findByFunctionUnitId(1L)).thenReturn(List.of());
        when(decisionDefinitionRepository.findByFunctionUnitId(1L)).thenReturn(List.of());
        when(tableRelationRepository.findByFunctionUnitId(1L)).thenReturn(List.of());

        DeveloperBusinessException ex = assertThrows(DeveloperBusinessException.class,
                () -> exporter.buildVersionSnapshotPayload(1L));
        assertEquals("COMPUTED_FIELD_EXPORT_INVALID", ex.getErrorCode());
    }

    @Test
    void import_acceptsComputedFieldJsonString() {
        Map<String, Object> formula = sampleFormula();
        String formulaJson;
        try {
            formulaJson = new ObjectMapper().writeValueAsString(formula);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Map<String, Object> tableData = new LinkedHashMap<>();
        tableData.put("tableName", "order");
        tableData.put("tableType", "MAIN");
        tableData.put("fields", List.of(Map.of(
                "fieldName", "amount",
                "dataType", "DECIMAL",
                "isComputed", true,
                "computedField", formulaJson)));

        TableDefinition imported = importWriter.importTable(FunctionUnit.builder().id(1L).build(), tableData);
        FieldDefinition amount = imported.getFieldDefinitions().get(0);
        assertEquals(Boolean.TRUE, amount.getIsComputed());
        assertEquals("qty * price", amount.getComputedFieldJson().get("source"));
    }

    @Test
    void legacyRestore_rejectsComputedFieldInsteadOfDroppingIt() {
        DeveloperBusinessException ex = assertThrows(DeveloperBusinessException.class,
                () -> FunctionUnitSnapshotRestorer.rejectComputedOnLegacyPath(Map.of(
                        "fieldName", "amount",
                        "isComputed", true,
                        "computedField", sampleFormula())));
        assertEquals("COMPUTED_FIELD_LEGACY_PATH", ex.getErrorCode());
    }

    @Test
    void legacySnapshotFactory_rejectsComputedFieldInsteadOfDroppingIt() {
        FunctionUnit fu = FunctionUnit.builder().name("Orders").build();
        TableDefinition table = TableDefinition.builder().tableName("order").build();
        table.setFieldDefinitions(new ArrayList<>(List.of(
                FieldDefinition.builder()
                        .fieldName("amount").dataType(DataType.DECIMAL)
                        .isComputed(true).computedFieldJson(sampleFormula()).build())));
        fu.setTableDefinitions(new ArrayList<>(List.of(table)));

        FunctionUnitSnapshotFactory factory = new FunctionUnitSnapshotFactory(new ObjectMapper());
        DeveloperBusinessException ex = assertThrows(DeveloperBusinessException.class,
                () -> factory.createSnapshot(fu));
        assertEquals("COMPUTED_FIELD_LEGACY_PATH", ex.getErrorCode());
    }

    @Test
    void legacySnapshotFactory_stillSerializesTablesWithoutComputedFields() throws Exception {
        FunctionUnit fu = FunctionUnit.builder().name("Orders").code("orders").build();
        TableDefinition table = TableDefinition.builder().tableName("order").tableType(TableType.MAIN).build();
        table.setFieldDefinitions(new ArrayList<>(List.of(
                FieldDefinition.builder().fieldName("qty").dataType(DataType.INTEGER).sortOrder(0).build())));
        fu.setTableDefinitions(new ArrayList<>(List.of(table)));
        fu.setFormDefinitions(new ArrayList<>());
        fu.setActionDefinitions(new ArrayList<>());
        fu.setDecisionDefinitions(new ArrayList<>());

        byte[] snapshot = new FunctionUnitSnapshotFactory(new ObjectMapper()).createSnapshot(fu);
        assertTrue(snapshot.length > 0);
    }

    private static Map<String, Object> sampleFormula() {
        Map<String, Object> formula = new LinkedHashMap<>();
        formula.put("source", "qty * price");
        formula.put("scope", "row");
        formula.put("onError", "fail");
        formula.put("dependsOn", List.of("qty", "price"));
        return formula;
    }
}
