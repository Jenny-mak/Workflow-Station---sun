package com.developer.component.impl;

import com.developer.dto.RequestIdConfig;
import com.developer.entity.FieldDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.entity.TableDefinition;
import com.developer.enums.DataType;
import com.developer.enums.TableType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.*;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.service.UserDisplayNameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Clone MUST carry the field-level FK/PK runtime metadata (is_foreign_key / ref_table_id /
 * ref_primary_key_fields / pk_generation_json / fk_display_mode / relation_cardinality),
 * computed-field formulas ({@code is_computed} / {@code computed_field_json}), plus the
 * main table's Request ID config. The runtime FK auto-fill and PK generation read these columns;
 * omitting them leaves cloned FUs without PK/FK behavior even when dw_foreign_keys exist.
 * dw_foreign_keys alone is not enough, so dropping them silently degrades a cloned FU to
 * default (uuid) PK generation and no structural FK auto-fill. The same is true of computed
 * columns: a clone that keeps the column but drops the formula becomes a writable blank.
 */
@ExtendWith(MockitoExtension.class)
class FunctionUnitCloneFieldMetadataTest {

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

    private static final long SOURCE_ORDER_TABLE_ID = 10L;
    private static final long SOURCE_SHIPMENT_TABLE_ID = 11L;

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
                org.mockito.Mockito.mock(com.developer.repository.AiDocumentRepository.class),
                org.mockito.Mockito.mock(com.developer.repository.AiStudioThreadStateRepository.class)));
    }

    @Test
    void clone_keepsFieldFkPkMetadataAndRemapsRefTableId() {
        FunctionUnit source = FunctionUnit.builder().id(1L).name("Source").build();

        Map<String, Object> pkGeneration = new LinkedHashMap<>();
        pkGeneration.put("strategy", "prefixedSequence");
        pkGeneration.put("prefix", "ORD-");
        pkGeneration.put("padding", 6);

        Map<String, Object> amountFormula = new LinkedHashMap<>();
        amountFormula.put("source", "qty * price");
        amountFormula.put("scope", "row");
        amountFormula.put("onError", "fail");
        amountFormula.put("dependsOn", List.of("qty", "price"));

        TableDefinition orderTable = TableDefinition.builder()
                .id(SOURCE_ORDER_TABLE_ID).functionUnit(source).tableName("order").tableType(TableType.MAIN)
                .requestIdConfig(RequestIdConfig.builder()
                        .fieldNames(new ArrayList<>(List.of("order_no", "customer")))
                        .separator("-")
                        .build())
                .build();
        orderTable.setFieldDefinitions(new ArrayList<>(List.of(
                FieldDefinition.builder()
                        .id(100L).tableDefinition(orderTable).fieldName("order_no").dataType(DataType.VARCHAR)
                        .length(50).nullable(false).isPrimaryKey(true).isUnique(true).sortOrder(0)
                        .pkGenerationJson(pkGeneration)
                        .build(),
                FieldDefinition.builder()
                        .id(101L).tableDefinition(orderTable).fieldName("amount").dataType(DataType.DECIMAL)
                        .nullable(true).isPrimaryKey(false).sortOrder(1)
                        .isComputed(true)
                        .computedFieldJson(amountFormula)
                        .build())));

        TableDefinition shipmentTable = TableDefinition.builder()
                .id(SOURCE_SHIPMENT_TABLE_ID).functionUnit(source).tableName("shipment").tableType(TableType.SUB)
                .build();
        shipmentTable.setFieldDefinitions(new ArrayList<>(List.of(
                FieldDefinition.builder()
                        .id(200L).tableDefinition(shipmentTable).fieldName("shipment_no").dataType(DataType.VARCHAR)
                        .length(50).isPrimaryKey(true).sortOrder(0)
                        .pkGenerationJson(new LinkedHashMap<>(Map.of("strategy", "autoIncrement")))
                        .build(),
                FieldDefinition.builder()
                        .id(201L).tableDefinition(shipmentTable).fieldName("order_id").dataType(DataType.VARCHAR)
                        .length(50).sortOrder(1)
                        .isForeignKey(true)
                        .refTableId(SOURCE_ORDER_TABLE_ID)
                        .refPrimaryKeyFields(new ArrayList<>(List.of("order_no")))
                        .fkDisplayMode("editable")
                        .relationCardinality("ONE_TO_MANY")
                        .build())));

        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(source));
        when(functionUnitRepository.existsByName("Cloned")).thenReturn(false);
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenAnswer(inv -> {
            FunctionUnit fu = inv.getArgument(0);
            if (fu.getId() == null) {
                fu.setId(2L);
            }
            return fu;
        });
        when(tableDefinitionRepository.findByFunctionUnitIdWithFields(1L))
                .thenReturn(List.of(orderTable, shipmentTable));
        when(formDefinitionRepository.findByFunctionUnitIdWithBindings(1L)).thenReturn(List.of());
        when(tableRelationRepository.findByFunctionUnitId(1L)).thenReturn(List.of());

        // Each cloned table gets a fresh id, like the DB identity column would assign.
        AtomicLong nextTableId = new AtomicLong(1000L);
        Map<String, TableDefinition> clonedByTableName = new LinkedHashMap<>();
        when(tableDefinitionRepository.save(any(TableDefinition.class))).thenAnswer(inv -> {
            TableDefinition t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(nextTableId.getAndIncrement());
            }
            clonedByTableName.put(t.getTableName(), t);
            return t;
        });

        component.clone(1L, "Cloned");

        TableDefinition clonedOrder = findClone(clonedByTableName, "order");
        TableDefinition clonedShipment = findClone(clonedByTableName, "shipment");
        assertNotEquals(SOURCE_ORDER_TABLE_ID, clonedOrder.getId(), "cloned table must get a new id");

        // Request ID config survives, and is a copy rather than the source instance.
        assertNotNull(clonedOrder.getRequestIdConfig());
        assertEquals(List.of("order_no", "customer"), clonedOrder.getRequestIdConfig().getFieldNames());
        assertEquals("-", clonedOrder.getRequestIdConfig().getSeparator());
        assertNotSame(orderTable.getRequestIdConfig(), clonedOrder.getRequestIdConfig());

        // PK generation strategy survives (prefixedSequence + autoIncrement), deep-copied.
        FieldDefinition clonedOrderNo = field(clonedOrder, "order_no");
        assertEquals(Map.of("strategy", "prefixedSequence", "prefix", "ORD-", "padding", 6),
                clonedOrderNo.getPkGenerationJson());
        assertNotSame(pkGeneration, clonedOrderNo.getPkGenerationJson());
        assertEquals("autoIncrement", field(clonedShipment, "shipment_no").getPkGenerationJson().get("strategy"));

        // Field-level FK metadata survives; refTableId is remapped to the cloned table.
        FieldDefinition clonedOrderId = field(clonedShipment, "order_id");
        assertEquals(Boolean.TRUE, clonedOrderId.getIsForeignKey());
        assertEquals(clonedOrder.getId(), clonedOrderId.getRefTableId(),
                "refTableId must point at the cloned order table, not the source one");
        assertEquals(List.of("order_no"), clonedOrderId.getRefPrimaryKeyFields());
        assertEquals("editable", clonedOrderId.getFkDisplayMode());
        assertEquals("ONE_TO_MANY", clonedOrderId.getRelationCardinality());

        // Computed formula survives as a deep copy, not a shared map.
        FieldDefinition clonedAmount = field(clonedOrder, "amount");
        assertEquals(Boolean.TRUE, clonedAmount.getIsComputed());
        assertEquals(amountFormula, clonedAmount.getComputedFieldJson());
        assertNotSame(amountFormula, clonedAmount.getComputedFieldJson());

        // Source rows are untouched.
        assertEquals(SOURCE_ORDER_TABLE_ID, field(shipmentTable, "order_id").getRefTableId());
        assertSame(amountFormula, field(orderTable, "amount").getComputedFieldJson());
    }

    @Test
    void clone_rejectsComputedFlagWithoutFormula() {
        FunctionUnit source = FunctionUnit.builder().id(1L).name("Source").build();
        TableDefinition orderTable = TableDefinition.builder()
                .id(SOURCE_ORDER_TABLE_ID).functionUnit(source).tableName("order").tableType(TableType.MAIN)
                .build();
        orderTable.setFieldDefinitions(new ArrayList<>(List.of(
                FieldDefinition.builder()
                        .id(101L).tableDefinition(orderTable).fieldName("amount").dataType(DataType.DECIMAL)
                        .nullable(true).sortOrder(0)
                        .isComputed(true)
                        .build())));

        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(source));
        when(functionUnitRepository.existsByName("Cloned")).thenReturn(false);
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenAnswer(inv -> {
            FunctionUnit fu = inv.getArgument(0);
            if (fu.getId() == null) {
                fu.setId(2L);
            }
            return fu;
        });
        when(tableDefinitionRepository.findByFunctionUnitIdWithFields(1L)).thenReturn(List.of(orderTable));
        when(formDefinitionRepository.findByFunctionUnitIdWithBindings(1L)).thenReturn(List.of());
        when(tableRelationRepository.findByFunctionUnitId(1L)).thenReturn(List.of());
        when(tableDefinitionRepository.save(any(TableDefinition.class))).thenAnswer(inv -> {
            TableDefinition t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(1000L);
            }
            return t;
        });

        DeveloperBusinessException ex = assertThrows(DeveloperBusinessException.class,
                () -> component.clone(1L, "Cloned"));
        assertEquals("COMPUTED_FIELD_CLONE_INVALID", ex.getErrorCode());
    }

    private TableDefinition findClone(Map<String, TableDefinition> clonedByTableName, String sourceTableName) {
        return clonedByTableName.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().startsWith(sourceTableName + "_copy"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No clone found for table " + sourceTableName));
    }

    private FieldDefinition field(TableDefinition table, String fieldName) {
        return table.getFieldDefinitions().stream()
                .filter(f -> fieldName.equals(f.getFieldName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Field not found: " + fieldName));
    }
}
