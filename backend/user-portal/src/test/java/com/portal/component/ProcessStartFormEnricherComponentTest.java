package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.dto.PkGenerationConfig;
import com.platform.common.fk.PrimaryKeyAllocationService;
import com.portal.exception.PortalException;
import com.portal.service.UserDisplayNameResolver;
import com.portal.util.SystemAuditFieldFiller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessStartFormEnricherComponentTest {

    private static final long FU_ID = 42L;
    private static final long MAIN_TABLE = 100L;
    private static final long SUB_TABLE = 200L;
    private static final long SUB_BINDING = 300L;

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PrimaryKeyAllocationService primaryKeyAllocationService;
    @Mock
    private PortalPrimaryKeyAllocationComponent portalPrimaryKeyAllocationComponent;
    @Mock
    private ProcessSubTablePrimaryKeyEnricherComponent processSubTablePrimaryKeyEnricherComponent;
    @Mock
    private OwnerFieldComponent ownerFieldComponent;
    @Mock
    private ComputedFieldRecalculator computedFieldRecalculator;
    @Mock
    private RequestIdEnricher requestIdEnricher;
    @Mock
    private UserDisplayNameResolver userDisplayNameResolver;

    private ProcessStartFormEnricherComponent enricher;

    @BeforeEach
    void setUp() {
        enricher = new ProcessStartFormEnricherComponent(
                jdbcTemplate,
                new ObjectMapper(),
                primaryKeyAllocationService,
                portalPrimaryKeyAllocationComponent,
                processSubTablePrimaryKeyEnricherComponent,
                ownerFieldComponent,
                computedFieldRecalculator,
                requestIdEnricher,
                userDisplayNameResolver);
        when(portalPrimaryKeyAllocationComponent.resolveFunctionUnitIdForAllocation(anyString()))
                .thenReturn(FU_ID);
        when(userDisplayNameResolver.resolve("system")).thenReturn("System");
        stubJdbc();
        when(primaryKeyAllocationService.allocate(
                eq(MAIN_TABLE), eq("id"), any(PkGenerationConfig.class), anyInt(), anyString()))
                .thenReturn(List.of("MAIN-1"));
    }

    @Test
    void allocateMainAutoPkWhenBlank() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("title", "from-email");

        enricher.enrichOnInsert("FU-MCY", "system", variables);

        assertThat(variables.get("id")).isEqualTo("MAIN-1");
        assertThat(variables.get(SystemAuditFieldFiller.CREATED_BY)).isEqualTo("System");
        verify(ownerFieldComponent).applyOnSubmit(eq("FU-MCY"), any(), eq(variables));
        verify(computedFieldRecalculator).recalculate("FU-MCY", variables);
        verify(requestIdEnricher).stampRequestId("FU-MCY", variables);
    }

    @Test
    void keepsExistingMainPk() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("id", "CASE-9");

        enricher.enrichOnInsert("FU-MCY", "system", variables);

        assertThat(variables.get("id")).isEqualTo("CASE-9");
        verify(primaryKeyAllocationService, never()).allocate(anyLong(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    void skipsManualPrimaryKey() {
        stubManualPk();
        Map<String, Object> variables = new LinkedHashMap<>();

        enricher.enrichOnInsert("FU-MCY", "system", variables);

        assertThat(variables).doesNotContainKey("case_number");
        verify(primaryKeyAllocationService, never()).allocate(anyLong(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    void fillsStructuralFkFromMainPkAndDoesNotGuessEmptyRefPk() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("card_number", "4111");
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("__subTables__", new LinkedHashMap<>(Map.of(
                String.valueOf(SUB_BINDING), new ArrayList<>(List.of(row)))));

        enricher.enrichOnInsert("FU-MCY", "system", variables);

        assertThat(row.get("main_id")).isEqualTo("MAIN-1");
        assertThat(row).doesNotContainKey("orphan_fk");
    }

    @Test
    void doesNotOverwriteExistingFk() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("main_id", "KEEP");
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("__subTables__", new LinkedHashMap<>(Map.of(
                String.valueOf(SUB_BINDING), new ArrayList<>(List.of(row)))));

        enricher.enrichOnInsert("FU-MCY", "system", variables);

        assertThat(row.get("main_id")).isEqualTo("KEEP");
    }

    @Test
    void formulaSeesAllocatedMainPk() {
        doAnswer(inv -> {
            Map<String, Object> vars = inv.getArgument(1);
            assertThat(vars.get("id")).isEqualTo("MAIN-1");
            vars.put("total", "99");
            return null;
        }).when(computedFieldRecalculator).recalculate(anyString(), any());

        Map<String, Object> variables = new LinkedHashMap<>();
        enricher.enrichOnInsert("FU-MCY", "system", variables);

        assertThat(variables.get("total")).isEqualTo("99");
    }

    @Test
    void throwsWhenFunctionUnitNotResolved() {
        when(portalPrimaryKeyAllocationComponent.resolveFunctionUnitIdForAllocation("MISSING"))
                .thenThrow(new PortalException("FUNCTION_UNIT_NOT_FOUND", "Function unit not found"));

        assertThatThrownBy(() -> enricher.enrichOnInsert("MISSING", "system", new LinkedHashMap<>()))
                .isInstanceOf(PortalException.class)
                .hasMessageContaining("Function unit not found");

        verify(computedFieldRecalculator, never()).recalculate(anyString(), any());
    }

    @Test
    void throwsWhenFunctionUnitCodeBlank() {
        assertThatThrownBy(() -> enricher.enrichOnInsert("  ", "system", new LinkedHashMap<>()))
                .isInstanceOf(PortalException.class)
                .hasMessageContaining("functionUnitCode");

        verify(computedFieldRecalculator, never()).recalculate(anyString(), any());
    }

    @Test
    void encodeCompositePkMatchesFrontendContract() {
        assertThat(ProcessStartFormEnricherComponent.encodeCompositePk(
                List.of("id"), Map.of("id", "A-1"))).isEqualTo("A-1");
        assertThat(ProcessStartFormEnricherComponent.encodeCompositePk(
                List.of("b", "a"), Map.of("a", "1", "b", "2")))
                .isEqualTo("a=1" + '\u001f' + "b=2");
        assertThat(ProcessStartFormEnricherComponent.encodeCompositePk(List.of("id"), Map.of()))
                .isNull();
        assertThat(ProcessStartFormEnricherComponent.encodeCompositePk(List.of(), Map.of("id", "x")))
                .isNull();
    }

    private void stubJdbc() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    ResultSetExtractor<?> extractor = inv.getArgument(1);
                    if (sql.contains("binding_type = 'PRIMARY'")) {
                        return extractor.extractData(singleLongRs("table_id", MAIN_TABLE));
                    }
                    if (sql.contains("is_primary_key")) {
                        return extractor.extractData(autoPkRs());
                    }
                    if (sql.contains("is_foreign_key")) {
                        return extractor.extractData(fkRs());
                    }
                    return extractor.extractData(emptyRs());
                });
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(FU_ID)))
                .thenReturn(List.of(bindingRow(SUB_BINDING, SUB_TABLE, "txn")));
    }

    private void stubManualPk() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    ResultSetExtractor<?> extractor = inv.getArgument(1);
                    if (sql.contains("binding_type = 'PRIMARY'")) {
                        return extractor.extractData(singleLongRs("table_id", MAIN_TABLE));
                    }
                    if (sql.contains("is_primary_key")) {
                        return extractor.extractData(manualPkRs());
                    }
                    if (sql.contains("is_foreign_key")) {
                        return extractor.extractData(emptyRs());
                    }
                    return extractor.extractData(emptyRs());
                });
    }

    private static Map<String, Object> bindingRow(long bindingId, long tableId, String tableName) {
        Map<String, Object> m = new HashMap<>();
        m.put("bindingId", bindingId);
        m.put("tableId", tableId);
        m.put("tableName", tableName);
        return m;
    }

    private static ResultSet singleLongRs(String column, long value) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong(column)).thenReturn(value);
        return rs;
    }

    private static ResultSet autoPkRs() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("field_name")).thenReturn("id");
        when(rs.getString("json")).thenReturn("{\"strategy\":\"uuid\"}");
        return rs;
    }

    private static ResultSet manualPkRs() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("field_name")).thenReturn("case_number");
        when(rs.getString("json")).thenReturn("{\"strategy\":\"manual\"}");
        return rs;
    }

    private static ResultSet fkRs() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getLong("table_id")).thenReturn(SUB_TABLE, SUB_TABLE);
        when(rs.getString("field_name")).thenReturn("main_id", "orphan_fk");
        when(rs.getLong("ref_table_id")).thenReturn(MAIN_TABLE, MAIN_TABLE);
        when(rs.getString("ref_pk")).thenReturn("[\"id\"]", "[]");
        return rs;
    }

    private static ResultSet emptyRs() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }
}
