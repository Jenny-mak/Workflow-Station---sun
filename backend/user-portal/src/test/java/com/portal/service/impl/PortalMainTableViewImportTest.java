package com.portal.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.component.FunctionUnitAccessComponent;
import com.portal.component.MainTableViewAccessResolver;
import com.portal.component.MainTableViewJdbcQuery;
import com.portal.component.ProcessComponent;
import com.portal.dto.MainTableViewImportResult;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.dto.ProcessStartRequest;
import com.portal.entity.ProcessInstance;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.repository.UserBusinessUnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortalMainTableViewImportTest {

    private static final Long VIEW_ID = 3L;
    private static final String FU_CODE = "fu-test";
    private static final String USER_ID = "user-dev";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private FunctionUnitAccessComponent functionUnitAccessComponent;

    @Mock
    private UserBusinessUnitRepository userBusinessUnitRepository;

    @Mock
    private MainTableViewJdbcQuery mainTableViewJdbcQuery;

    @Mock
    private ProcessInstanceRepository processInstanceRepository;

    @Mock
    private ProcessComponent processComponent;

    private PortalMainTableViewServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        MainTableViewAccessResolver accessResolver = new MainTableViewAccessResolver(
                functionUnitAccessComponent, userBusinessUnitRepository);
        service = new PortalMainTableViewServiceImpl(
                jdbcTemplate,
                new ObjectMapper(),
                functionUnitAccessComponent,
                accessResolver,
                mainTableViewJdbcQuery,
                processInstanceRepository,
                processComponent);
        when(functionUnitAccessComponent.canAccessFunctionUnit(USER_ID, FU_CODE)).thenReturn(true);
        when(functionUnitAccessComponent.isFunctionUnitEnabled(FU_CODE)).thenReturn(true);
        when(functionUnitAccessComponent.isSystemAdministrator(USER_ID)).thenReturn(true);
        stubPublishedView();
    }

    @SuppressWarnings("unchecked")
    private void stubPublishedView() throws Exception {
        when(jdbcTemplate.queryForList(contains("dw_main_table_view_configs"), eq(VIEW_ID)))
                .thenReturn(List.of(Map.of(
                        "id", VIEW_ID,
                        "view_name", "Main",
                        "sort_config", "[]",
                        "filter_config", "{}",
                        "fu_code", FU_CODE,
                        "main_table_id", 1L,
                        "table_type", "MAIN",
                        "restrict_to_involved_users", false)));

        when(jdbcTemplate.queryForList(contains("dw_main_table_view_access"), eq(VIEW_ID)))
                .thenReturn(List.of());

        when(jdbcTemplate.query(contains("dw_main_table_view_fields"), any(RowMapper.class), eq(VIEW_ID)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(
                            mapper.mapRow(mockFieldRow("case_number", "Case Number", 0), 0),
                            mapper.mapRow(mockFieldRow("legal_hold", "Legal Hold", 1), 1));
                });
    }

    private ResultSet mockFieldRow(String fieldName, String label, int sortOrder) throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getString("field_name")).thenReturn(fieldName);
        when(rs.getString("display_label")).thenReturn(label);
        when(rs.getObject("column_width")).thenReturn(null);
        when(rs.getInt("sort_order")).thenReturn(sortOrder);
        when(rs.getBoolean("visible")).thenReturn(true);
        when(rs.getBoolean("is_system_field")).thenReturn(false);
        return rs;
    }

    @Test
    void importViewCsv_blankProcessInstanceId_createsNewProcess() {
        when(processComponent.startProcess(eq(USER_ID), eq(FU_CODE), any(ProcessStartRequest.class)))
                .thenReturn(ProcessInstanceInfo.builder().id("new-proc-1").build());

        String csv = "processInstanceId,Case Number,Legal Hold\n,99,true\n";
        MainTableViewImportResult result = service.importViewCsv(USER_ID, VIEW_ID, csv.getBytes(StandardCharsets.UTF_8));

        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.errorCount()).isZero();

        ArgumentCaptor<ProcessStartRequest> captor = ArgumentCaptor.forClass(ProcessStartRequest.class);
        verify(processComponent).startProcess(eq(USER_ID), eq(FU_CODE), captor.capture());
        assertThat(captor.getValue().getFormData()).containsEntry("case_number", "99");
        assertThat(captor.getValue().getFormData()).containsEntry("legal_hold", true);
        assertThat(captor.getValue().getBusinessKey()).isEqualTo("99");
    }

    @Test
    void importViewCsv_missingProcessInstanceIdColumn_createsNewProcess() {
        when(processComponent.startProcess(eq(USER_ID), eq(FU_CODE), any(ProcessStartRequest.class)))
                .thenReturn(ProcessInstanceInfo.builder().id("new-proc-2").build());

        String csv = "Case Number,Legal Hold\n88,false\n";
        MainTableViewImportResult result = service.importViewCsv(USER_ID, VIEW_ID, csv.getBytes(StandardCharsets.UTF_8));

        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.errorCount()).isZero();
    }

    @Test
    void importViewCsv_blankProcessInstanceIdWithNoFields_reportsError() {
        String csv = "processInstanceId,Case Number,Legal Hold,Notes\n,,,draft\n";
        MainTableViewImportResult result = service.importViewCsv(USER_ID, VIEW_ID, csv.getBytes(StandardCharsets.UTF_8));

        assertThat(result.createdCount()).isZero();
        assertThat(result.errorCount()).isEqualTo(1);
        assertThat(result.errors().get(0)).contains("no field values for new record");
        verify(processComponent, never()).startProcess(any(), any(), any());
    }

    @Test
    void importViewCsv_existingProcessInstanceId_updatesRecord() {
        ProcessInstance pi = ProcessInstance.builder()
                .id("existing-1")
                .startUserId(USER_ID)
                .functionUnitCode(FU_CODE)
                .variables(new HashMap<>(Map.of("case_number", "1")))
                .build();
        when(processInstanceRepository.findById("existing-1")).thenReturn(Optional.of(pi));

        String csv = "processInstanceId,Case Number,Legal Hold\nexisting-1,42,false\n";
        MainTableViewImportResult result = service.importViewCsv(USER_ID, VIEW_ID, csv.getBytes(StandardCharsets.UTF_8));

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.createdCount()).isZero();
        verify(processComponent, never()).startProcess(any(), any(), any());
        verify(processInstanceRepository).save(pi);
        assertThat(pi.getVariables().get("case_number")).isEqualTo("42");
        assertThat(pi.getVariables().get("legal_hold")).isEqualTo("false");
    }

    @Test
    void importViewCsv_createFailure_recordsRowError() {
        when(processComponent.startProcess(eq(USER_ID), eq(FU_CODE), any(ProcessStartRequest.class)))
                .thenThrow(new IllegalStateException("Workflow engine unavailable"));

        String csv = "processInstanceId,Case Number\n,77\n";
        MainTableViewImportResult result = service.importViewCsv(USER_ID, VIEW_ID, csv.getBytes(StandardCharsets.UTF_8));

        assertThat(result.createdCount()).isZero();
        assertThat(result.errorCount()).isEqualTo(1);
        assertThat(result.errors().get(0)).contains("create failed");
    }
}
