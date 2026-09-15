package com.developer.service.impl;

import com.developer.dto.AiGeneratedData;
import com.developer.dto.MainTableViewDtos.CreateMainTableViewRequest;
import com.developer.dto.MainTableViewDtos.MainTableViewDTO;
import com.developer.dto.MainTableViewDtos.UpdateMainTableViewRequest;
import com.developer.entity.FormDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.entity.MainTableViewConfig;
import com.developer.entity.TableDefinition;
import com.developer.enums.TableType;
import com.developer.exception.AiGenerationException;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.MainTableViewConfigRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.service.MainTableViewService;
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

/** VIEW_DESIGN 提案落库：按 (mainTableName, viewName) upsert，经 MainTableViewService，从不删除。 */
@ExtendWith(MockitoExtension.class)
class AiViewProposalWriterTest {

    @Mock private MainTableViewService mainTableViewService;
    @Mock private MainTableViewConfigRepository mainTableViewConfigRepository;
    @Mock private TableDefinitionRepository tableDefinitionRepository;
    @Mock private FormDefinitionRepository formDefinitionRepository;

    private AiViewProposalWriter writer;
    private FunctionUnit functionUnit;

    @BeforeEach
    void setUp() {
        writer = new AiViewProposalWriter(mainTableViewService, mainTableViewConfigRepository,
                tableDefinitionRepository, formDefinitionRepository);
        functionUnit = new FunctionUnit();
        functionUnit.setId(7L);

        TableDefinition orders = new TableDefinition();
        orders.setId(100L);
        orders.setTableName("orders");
        orders.setTableType(TableType.MAIN);
        TableDefinition lines = new TableDefinition();
        lines.setId(101L);
        lines.setTableName("order_lines");
        lines.setTableType(TableType.SUB);
        lenient().when(tableDefinitionRepository.findByFunctionUnitIdWithFields(anyLong()))
                .thenReturn(List.of(orders, lines));

        FormDefinition lineForm = new FormDefinition();
        lineForm.setId(500L);
        lineForm.setFormName("line_form");
        lenient().when(formDefinitionRepository.findByFunctionUnitId(anyLong())).thenReturn(List.of(lineForm));
        lenient().when(mainTableViewConfigRepository.findByFunctionUnitIdWithFields(anyLong())).thenReturn(List.of());
    }

    private static Map<String, Object> view(String table, String name, Map<String, Object> extra) {
        Map<String, Object> v = new HashMap<>();
        v.put("mainTableName", table);
        v.put("viewName", name);
        v.putAll(extra);
        return v;
    }

    private static MainTableViewConfig existingView(long id, long tableId, String name, Long detailFormId) {
        MainTableViewConfig c = new MainTableViewConfig();
        c.setId(id);
        c.setMainTableId(tableId);
        c.setViewName(name);
        c.setDetailFormId(detailFormId);
        return c;
    }

    @Test
    void newViewIsCreatedThenConfigured() {
        when(mainTableViewService.createView(eq(7L), any()))
                .thenReturn(MainTableViewDTO.builder().id(900L).build());
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(view("orders", "Pending", Map.of(
                        "restrictToInvolvedUsers", true,
                        "fields", List.of(
                                Map.of("fieldName", "order_no", "displayLabel", "Order", "columnWidth", 120),
                                Map.of("fieldName", "process_status", "systemField", true, "columnType", "field")),
                        "sortConfig", List.of(Map.of("fieldName", "start_time", "direction", "DESC", "systemField", true)),
                        "filterConfig", Map.of("logic", "and", "conditions", List.of(
                                Map.of("fieldName", "process_status", "operator", "eq", "value", "RUNNING", "systemField", true))),
                        "accessRules", List.of(
                                Map.of("targetType", "BUSINESS_UNIT", "targetId", "bu-1"),
                                Map.of("targetType", "ROLE", "targetId", "role-1"))))))
                .build();

        writer.write(functionUnit, data);

        ArgumentCaptor<CreateMainTableViewRequest> create = ArgumentCaptor.forClass(CreateMainTableViewRequest.class);
        verify(mainTableViewService).createView(eq(7L), create.capture());
        assertEquals("Pending", create.getValue().viewName());
        assertEquals(100L, create.getValue().tableId());

        ArgumentCaptor<UpdateMainTableViewRequest> update = ArgumentCaptor.forClass(UpdateMainTableViewRequest.class);
        verify(mainTableViewService).updateView(eq(7L), eq(900L), update.capture());
        UpdateMainTableViewRequest req = update.getValue();
        assertEquals("Pending", req.viewName());
        assertEquals(Boolean.TRUE, req.restrictToInvolvedUsers());
        assertNull(req.detailFormId());
        assertEquals(2, req.fields().size());
        assertEquals("order_no", req.fields().get(0).fieldName());
        assertEquals(120, req.fields().get(0).columnWidth());
        assertEquals(0, req.fields().get(0).sortOrder());
        assertEquals("field", req.fields().get(0).columnType());
        assertEquals(Boolean.TRUE, req.fields().get(1).systemField());
        assertEquals(1, req.sortConfig().size());
        assertEquals("DESC", req.sortConfig().get(0).get("direction"));
        assertEquals("and", req.filterConfig().get("logic"));
        assertEquals(2, req.accessRules().size());
        assertEquals("ROLE", req.accessRules().get(1).targetType());
        assertEquals("role-1", req.accessRules().get(1).targetId());
        verify(mainTableViewService, never()).deleteView(anyLong(), anyLong());
    }

    @Test
    void existingViewIsUpdatedInPlaceAndUnmentionedKeysStayNull() {
        when(mainTableViewConfigRepository.findByFunctionUnitIdWithFields(7L))
                .thenReturn(List.of(existingView(42L, 100L, "Pending", null), existingView(43L, 100L, "Other", null)));
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(view("orders", "Pending", Map.of(
                        "fields", List.of(Map.of("fieldName", "amount"))))))
                .build();

        writer.write(functionUnit, data);

        verify(mainTableViewService, never()).createView(anyLong(), any());
        ArgumentCaptor<UpdateMainTableViewRequest> update = ArgumentCaptor.forClass(UpdateMainTableViewRequest.class);
        verify(mainTableViewService).updateView(eq(7L), eq(42L), update.capture());
        UpdateMainTableViewRequest req = update.getValue();
        assertEquals(1, req.fields().size());
        // 提案没给的配置传 null → 服务跳过，原配置保留
        assertNull(req.sortConfig());
        assertNull(req.filterConfig());
        assertNull(req.accessRules());
        assertNull(req.restrictToInvolvedUsers());
        // 未提及的 "Other" 视图不被触碰
        verify(mainTableViewService, never()).updateView(eq(7L), eq(43L), any());
        verify(mainTableViewService, never()).deleteView(anyLong(), anyLong());
    }

    @Test
    void detailFormIsKeptUnlessTheKeyIsPresent() {
        when(mainTableViewConfigRepository.findByFunctionUnitIdWithFields(7L))
                .thenReturn(List.of(existingView(42L, 101L, "Lines", 500L)));
        AiGeneratedData keep = AiGeneratedData.builder()
                .mainTableViews(List.of(view("order_lines", "Lines", Map.of("fields", List.of(Map.of("fieldName", "qty"))))))
                .build();
        writer.write(functionUnit, keep);
        ArgumentCaptor<UpdateMainTableViewRequest> update = ArgumentCaptor.forClass(UpdateMainTableViewRequest.class);
        verify(mainTableViewService).updateView(eq(7L), eq(42L), update.capture());
        assertEquals(500L, update.getValue().detailFormId());

        Map<String, Object> clearing = view("order_lines", "Lines", Map.of());
        clearing.put("detailFormName", null);
        AiGeneratedData clear = AiGeneratedData.builder().mainTableViews(List.of(clearing)).build();
        writer.write(functionUnit, clear);
        ArgumentCaptor<UpdateMainTableViewRequest> second = ArgumentCaptor.forClass(UpdateMainTableViewRequest.class);
        verify(mainTableViewService, org.mockito.Mockito.times(2)).updateView(eq(7L), eq(42L), second.capture());
        assertNull(second.getAllValues().get(1).detailFormId());
    }

    @Test
    void detailFormNameIsResolvedByName() {
        when(mainTableViewService.createView(eq(7L), any()))
                .thenReturn(MainTableViewDTO.builder().id(901L).build());
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(view("order_lines", "Lines", Map.of("detailFormName", "line_form"))))
                .build();

        writer.write(functionUnit, data);

        ArgumentCaptor<UpdateMainTableViewRequest> update = ArgumentCaptor.forClass(UpdateMainTableViewRequest.class);
        verify(mainTableViewService).updateView(eq(7L), eq(901L), update.capture());
        assertEquals(500L, update.getValue().detailFormId());
    }

    @Test
    void unknownTableOrFormFailsLoudly() {
        AiGeneratedData badTable = AiGeneratedData.builder()
                .mainTableViews(List.of(view("ghost", "X", Map.of())))
                .build();
        AiGenerationException ex = assertThrows(AiGenerationException.class, () -> writer.write(functionUnit, badTable));
        assertEquals("AI_WRITE_VIEW_TABLE_NOT_FOUND", ex.getErrorCode());

        when(mainTableViewService.createView(eq(7L), any()))
                .thenReturn(MainTableViewDTO.builder().id(902L).build());
        AiGeneratedData badForm = AiGeneratedData.builder()
                .mainTableViews(List.of(view("order_lines", "Lines", Map.of("detailFormName", "nope"))))
                .build();
        AiGenerationException ex2 = assertThrows(AiGenerationException.class, () -> writer.write(functionUnit, badForm));
        assertEquals("AI_WRITE_VIEW_FORM_NOT_FOUND", ex2.getErrorCode());
        verify(mainTableViewService, never()).updateView(anyLong(), anyLong(), any());
    }

    @Test
    void hasViewSliceDetectsPresence() {
        assertFalse(AiViewProposalWriter.hasViewSlice(null));
        assertFalse(AiViewProposalWriter.hasViewSlice(AiGeneratedData.builder().build()));
        assertFalse(AiViewProposalWriter.hasViewSlice(AiGeneratedData.builder().mainTableViews(List.of()).build()));
        assertTrue(AiViewProposalWriter.hasViewSlice(AiGeneratedData.builder()
                .mainTableViews(List.of(Map.of("mainTableName", "t", "viewName", "v"))).build()));
    }
}
