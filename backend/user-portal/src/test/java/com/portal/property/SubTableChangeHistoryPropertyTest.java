package com.portal.property;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.client.WorkflowEngineClient;
import com.portal.component.ChangeHistoryComponent;
import com.portal.component.UserPortalAuditEnricher;
import com.portal.component.UserPortalAuditProcessInstanceMatcher;
import com.portal.component.TaskFormSubTableChangeRecorder;
import com.portal.dto.ChangeHistoryContext;
import com.portal.dto.ChangeHistoryRecord;
import com.platform.security.repository.UserRepository;
import com.portal.dto.SubTableChange;
import com.portal.entity.ChangeHistory;
import com.portal.entity.ProcessInstance;
import com.portal.enums.ChangeType;
import com.portal.repository.ChangeHistoryRepository;
import com.portal.repository.ProcessInstanceRepository;
import com.platform.security.entity.User;
import com.portal.testsupport.PortalTransactionTestSupport;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property 11: Sub-table Change_History includes table metadata
 *
 * For any sub-table modification that creates Change_History records,
 * each record should have a non-null subTableName and rowIdentifier,
 * and the changeType should be one of SUB_TABLE_ROW_ADD, SUB_TABLE_ROW_UPDATE,
 * or SUB_TABLE_ROW_DELETE.
 *
 * Validates: Requirements 6.8
 */
public class SubTableChangeHistoryPropertyTest {
        private static final Set<ChangeType> VALID_SUB_TABLE_CHANGE_TYPES = Set.of(
                        ChangeType.SUB_TABLE_ROW_ADD,
                        ChangeType.SUB_TABLE_ROW_UPDATE,
                        ChangeType.SUB_TABLE_ROW_DELETE);
        private ChangeHistoryRepository changeHistoryRepository;
        private ChangeHistoryComponent changeHistoryComponent;
        private ProcessInstanceRepository processInstanceRepository;
        private UserRepository userRepository;
        private WorkflowEngineClient workflowEngineClient;
        private JdbcTemplate jdbcTemplate;

        @SuppressWarnings("unchecked")
        private void setUp() {
                changeHistoryRepository = mock(ChangeHistoryRepository.class);
                processInstanceRepository = mock(ProcessInstanceRepository.class);
                userRepository = mock(UserRepository.class);
                workflowEngineClient = mock(WorkflowEngineClient.class);
                jdbcTemplate = mock(JdbcTemplate.class);
                // These fixtures key rows by `row_id`, so that is the designer primary key their
                // tables declare. Row identity is resolved from this configuration lookup — a
                // column identifies a row because Table Design says so, not because of its name.
                when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                                org.mockito.ArgumentMatchers.<Object>any()))
                                .thenReturn(List.of("row_id"));
                when(changeHistoryRepository.saveAll(anyList()))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                changeHistoryComponent = new ChangeHistoryComponent(
                                changeHistoryRepository,
                                processInstanceRepository,
                                userRepository,
                                workflowEngineClient,
                                jdbcTemplate,
                                new ObjectMapper(),
                                mock(UserPortalAuditEnricher.class),
                                mock(UserPortalAuditProcessInstanceMatcher.class),
                                PortalTransactionTestSupport.noopPlatformTransactionManager());
        }

        /**
         * Property 11: Sub-table Change_History includes table metadata.
         *
         * Validates: Requirements 6.8
         */
        @Property(tries = 100)
        @Label("Feature: process-task-form-separation, Property 11: Sub-table Change_History includes table metadata")
        void subTableChangeHistoryIncludesMetadata(
                        @ForAll("subTableChangeSets") SubTableChangeSet changeSet) {
                setUp();
                ChangeHistoryContext context = ChangeHistoryContext.builder()
                                .processInstanceId(changeSet.processInstanceId)
                                .taskInstanceId(changeSet.taskInstanceId)
                                .stageId(changeSet.stageId)
                                .userId(changeSet.userId)
                                .build();
                changeHistoryComponent.recordSubTableChanges(context, changeSet.subTableName, changeSet.changes);
                if (changeSet.changes.isEmpty()) {
                        verify(changeHistoryRepository, never()).saveAll(anyList());
                } else {
                        @SuppressWarnings("unchecked")
                        ArgumentCaptor<List<ChangeHistory>> captor = ArgumentCaptor.forClass(List.class);
                        verify(changeHistoryRepository, times(1)).saveAll(captor.capture());
                        List<ChangeHistory> saved = captor.getValue();
                        assertThat(saved).hasSize(changeSet.changes.size());
                        for (ChangeHistory record : saved) {
                                // subTableName must be non-null (normalized to lowercase; pure-numeric keys are
                                // skipped)
                                assertThat(record.getSubTableName()).isNotNull();
                                assertThat(record.getSubTableName()).isEqualTo(changeSet.subTableName.toLowerCase());
                                // rowIdentifier must be non-null
                                assertThat(record.getRowIdentifier()).isNotNull();
                                // changeType must be one of the valid sub-table types
                                assertThat(record.getChangeType()).isIn(VALID_SUB_TABLE_CHANGE_TYPES);
                                // Verify context fields
                                assertThat(record.getProcessInstanceId()).isEqualTo(changeSet.processInstanceId);
                                assertThat(record.getUserId()).isEqualTo(changeSet.userId);
                                assertThat(record.getTimestamp()).isNotNull();
                        }
                }
        }

        @Example
        @Label("Sub-table row identity prefers row_id over id and display fields")
        void rowIdentifierPrefersRowIdOverDisplayFields() {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", "business-id");
                row.put("arn", "1");
                row.put("row_id", "ATM-DC-PW-TRANS-000010");
                row.put("card_number", "12");
                // The identity is the column this table DECLARES as its primary key. Both `id` and
                // `row_id` are present here and either could look like an identity by name; only
                // configuration says which one actually is. This used to assert a fixed precedence
                // between the two names, which answered wrong for any table keyed by something
                // else — `correspondence_id`, say — and treated a business column called `id` as an
                // identity on tables that never declared it.
                assertThat(ChangeHistoryComponent.resolveRowIdentifier(row, List.of("row_id")))
                                .isEqualTo("ATM-DC-PW-TRANS-000010");
                assertThat(ChangeHistoryComponent.resolveRowIdentifier(row, List.of("id")))
                                .isEqualTo("business-id");
        }

        @Example
        @Label("Anonymous rows do not invent an identity from business values")
        void rowIdentifierDoesNotFallBackToBusinessFields() {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("channel", "Email");
                row.put("mode", "Auto");
                assertThat(ChangeHistoryComponent.resolveRowIdentifier(row)).isNull();
        }

        @Example
        @Label("Sub-table changes persist one physical audit row per changed field")
        void subTableChangesPersistOneAuditRowPerChangedField() {
                setUp();
                ChangeHistoryContext context = ChangeHistoryContext.builder()
                                .processInstanceId("process-1")
                                .taskInstanceId("task-1")
                                .stageId("stage-1")
                                .userId("user-1")
                                .build();
                SubTableChange change = SubTableChange.builder()
                                .changeType("ROW_UPDATE")
                                .rowIdentifier("row-1")
                                .oldValues(Map.of("amount", "100", "currency", "USD", "row_id", "row-1"))
                                .newValues(Map.of("amount", "200", "currency", "EUR", "row_id", "row-1"))
                                .build();
                changeHistoryComponent.recordSubTableChanges(context, "expense_items", List.of(change));
                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<ChangeHistory>> captor = ArgumentCaptor.forClass(List.class);
                verify(changeHistoryRepository).saveAll(captor.capture());
                assertThat(captor.getValue())
                                .extracting(ChangeHistory::getFieldName)
                                .containsExactly("amount", "currency");
                assertThat(captor.getValue())
                                .allSatisfy(record -> {
                                        assertThat(record.getSubTableName()).isEqualTo("expense_items");
                                        assertThat(record.getRowIdentifier()).isEqualTo("row-1");
                                        assertThat(record.getOldValue()).doesNotContain("{");
                                        assertThat(record.getNewValue()).doesNotContain("{");
                                });
        }

        @Example
        @Label("Assignee object and matching user ID are the same semantic value")
        void matchingAssigneeObjectAndUserIdDoNotCreateHistory() {
                setUp();
                ChangeHistoryContext context = ChangeHistoryContext.builder()
                                .processInstanceId("process-1")
                                .taskInstanceId("task-1")
                                .stageId("assignment")
                                .userId("user-1")
                                .build();
                SubTableChange change = SubTableChange.builder()
                                .changeType("ROW_UPDATE")
                                .rowIdentifier("Test-000002")
                                .oldValues(Map.of("assignee", Map.of(
                                                "id", "user-1", "username", "123456", "full_name", "liam")))
                                .newValues(Map.of("assignee", "user-1"))
                                .build();
                changeHistoryComponent.recordSubTableChanges(context, "participants", List.of(change));
                verify(changeHistoryRepository, never()).saveAll(anyList());
        }

        @Example
        @Label("Stable platform row metadata never creates user-visible audit rows")
        void platformRowMetadataDoesNotCreateHistory() {
                setUp();
                ChangeHistoryContext context = ChangeHistoryContext.builder()
                                .processInstanceId("process-1")
                                .taskInstanceId("task-1")
                                .stageId("sub form1")
                                .userId("user-1")
                                .build();
                Map<String, Object> generated = new LinkedHashMap<>();
                generated.put("__subTables__", Map.of("people", List.of()));
                generated.put("id", "Test-000002");
                generated.put("created_at", "2026-07-24T10:00:00Z");
                generated.put("created_by", "system");
                generated.put("updated_at", "2026-07-24T10:00:00Z");
                generated.put("updated_by", "system");
                generated.put("task_status", "PENDING");
                generated.put("task_current_node", "sub form1");
                SubTableChange change = SubTableChange.builder()
                                .changeType("ROW_UPDATE")
                                .rowIdentifier("Test-000002")
                                .oldValues(Map.of())
                                .newValues(generated)
                                .build();
                changeHistoryComponent.recordSubTableChanges(context, "participants", List.of(change));
                verify(changeHistoryRepository, never()).saveAll(anyList());
        }

        @Example
        @Label("True assignee changes use the canonical full name and username")
        void trueAssigneeChangeUsesCanonicalUserLabel() {
                setUp();
                ChangeHistory record = ChangeHistory.builder()
                                .id(1L)
                                .processInstanceId("process-1")
                                .userId("actor-1")
                                .timestamp(java.time.Instant.parse("2026-07-24T09:33:50Z"))
                                .fieldName("assignee")
                                .subTableName("participants")
                                .rowIdentifier("Test-000002")
                                .oldValue("old-user")
                                .newValue("new-user")
                                .changeType(ChangeType.SUB_TABLE_ROW_UPDATE)
                                .build();
                User oldUser = User.builder().id("old-user").username("100001").fullName("Alice").build();
                User newUser = User.builder().id("new-user").username("123456").fullName("liam").build();
                when(changeHistoryRepository.findByProcessInstanceIdOrderByTimestampAsc("process-1"))
                                .thenReturn(List.of(record));
                when(userRepository.findAllById(any(Iterable.class))).thenReturn(List.of(oldUser, newUser));
                when(workflowEngineClient.getTaskHistory("process-1")).thenReturn(Optional.empty());
                when(processInstanceRepository.findById("process-1")).thenReturn(Optional.empty());
                List<ChangeHistoryRecord> records = changeHistoryComponent.getChangeHistory("process-1");
                assertThat(records).singleElement().satisfies(item -> {
                        assertThat(item.getOldValue()).isEqualTo("Alice (100001)");
                        assertThat(item.getNewValue()).isEqualTo("liam (123456)");
                });
        }

        @Example
        @Label("Legacy semantic and system-only audit rows are hidden on read")
        void legacyNoiseIsHiddenOnRead() {
                setUp();
                ChangeHistory semanticNoop = ChangeHistory.builder()
                                .id(1L)
                                .processInstanceId("process-1")
                                .userId("user-1")
                                .timestamp(java.time.Instant.parse("2026-07-24T09:33:50Z"))
                                .fieldName("assignee")
                                .subTableName("participants")
                                .rowIdentifier("Test-000002")
                                .oldValue("{\"id\":\"user-1\",\"username\":\"123456\",\"full_name\":\"liam\"}")
                                .newValue("user-1")
                                .changeType(ChangeType.SUB_TABLE_ROW_UPDATE)
                                .build();
                ChangeHistory systemAlias = ChangeHistory.builder()
                                .id(2L)
                                .processInstanceId("process-1")
                                .userId("user-1")
                                .timestamp(java.time.Instant.parse("2026-07-24T09:35:17Z"))
                                .fieldName("participantId")
                                .subTableName("participants")
                                .rowIdentifier("Test-000002")
                                .oldValue(null)
                                .newValue("Test-000002")
                                .changeType(ChangeType.SUB_TABLE_ROW_UPDATE)
                                .build();
                when(changeHistoryRepository.findByProcessInstanceIdOrderByTimestampAsc("process-1"))
                                .thenReturn(List.of(semanticNoop, systemAlias));
                List<ChangeHistoryRecord> records = changeHistoryComponent.getChangeHistory("process-1");
                assertThat(records).isEmpty();
                verify(userRepository, never()).findAllById(any(Iterable.class));
        }

        @Example
        @Label("Legacy whole-row payloads suppress semantic assignee no-ops after splitting")
        void legacyWholeRowAssigneeNoopIsHiddenAfterSplit() {
                setUp();
                ChangeHistory legacyRecord = ChangeHistory.builder()
                                .id(1L)
                                .processInstanceId("process-1")
                                .userId("user-1")
                                .timestamp(java.time.Instant.parse("2026-07-24T09:33:50Z"))
                                .fieldName("participants")
                                .subTableName("participants")
                                .rowIdentifier("Test-000002")
                                .oldValue("{\"assignee\":{\"id\":\"user-1\",\"username\":\"123456\",\"full_name\":\"liam\"},\"name\":\"1\"}")
                                .newValue("{\"assignee\":\"user-1\",\"name\":\"12\"}")
                                .changeType(ChangeType.SUB_TABLE_ROW_UPDATE)
                                .build();
                when(changeHistoryRepository.findByProcessInstanceIdOrderByTimestampAsc("process-1"))
                                .thenReturn(List.of(legacyRecord));
                when(userRepository.findAllById(any(Iterable.class))).thenReturn(List.of());
                when(workflowEngineClient.getTaskHistory("process-1")).thenReturn(Optional.empty());
                when(processInstanceRepository.findById("process-1")).thenReturn(Optional.empty());
                List<ChangeHistoryRecord> records = changeHistoryComponent.getChangeHistory("process-1");
                assertThat(records).singleElement().satisfies(item -> {
                        assertThat(item.getFieldName()).isEqualTo("name");
                        assertThat(item.getOldValue()).isEqualTo("1");
                        assertThat(item.getNewValue()).isEqualTo("12");
                });
        }

        @Example
        @Label("Sub-table ROW_ADD is deduplicated across save and completion paths")
        void subTableRowAddDeduplicatesAcrossSaveAndCompletion() {
                setUp();
                ChangeHistoryContext context = ChangeHistoryContext.builder()
                                .processInstanceId("process-1")
                                .taskInstanceId("task-1")
                                .stageId("stage-1")
                                .userId("user-1")
                                .build();
                ChangeHistory persisted = ChangeHistory.builder()
                                .processInstanceId("process-1")
                                .taskInstanceId("task-1")
                                .stageId("stage-1")
                                .userId("user-1")
                                .fieldName("arn")
                                .subTableName("acq_transaction")
                                .rowIdentifier("ACQ-DC-PW-TRANS-000003")
                                .oldValue(null)
                                .newValue("1")
                                .changeType(ChangeType.SUB_TABLE_ROW_ADD)
                                .build();
                when(changeHistoryRepository
                                .findTopByProcessInstanceIdAndSubTableNameAndRowIdentifierAndFieldNameAndChangeTypeOrderByTimestampDesc(
                                                "process-1", "acq_transaction", "ACQ-DC-PW-TRANS-000003", "arn",
                                                ChangeType.SUB_TABLE_ROW_ADD))
                                .thenReturn(persisted);
                SubTableChange duplicateChange = SubTableChange.builder()
                                .changeType("ROW_ADD")
                                .rowIdentifier("ACQ-DC-PW-TRANS-000003")
                                .oldValues(null)
                                .newValues(Map.of("arn", "1"))
                                .build();
                changeHistoryComponent.recordSubTableChanges(context, "acq_transaction", List.of(duplicateChange));
                verify(changeHistoryRepository, never()).saveAll(anyList());
        }

        @Example
        @Label("Legacy JSON audit rows expand while structured atomic values remain one row")
        void changeHistoryExpandsOnlyLegacySubTablePayloads() {
                setUp();
                ChangeHistory legacyRecord = ChangeHistory.builder()
                                .id(1L)
                                .processInstanceId("process-1")
                                .userId("user-1")
                                .timestamp(java.time.Instant.parse("2026-07-23T00:00:00Z"))
                                .fieldName("expense_items")
                                .subTableName("expense_items")
                                .rowIdentifier("row-1")
                                .oldValue("{\"amount\":\"100\",\"currency\":\"USD\"}")
                                .newValue("{\"amount\":\"200\",\"currency\":\"EUR\"}")
                                .changeType(ChangeType.SUB_TABLE_ROW_UPDATE)
                                .build();
                ChangeHistory atomicStructuredValue = ChangeHistory.builder()
                                .id(2L)
                                .processInstanceId("process-1")
                                .userId("user-1")
                                .timestamp(java.time.Instant.parse("2026-07-23T00:00:01Z"))
                                .fieldName("approver")
                                .subTableName("expense_items")
                                .rowIdentifier("row-1")
                                .oldValue("{\"id\":\"user-1\",\"name\":\"Alice\"}")
                                .newValue("{\"id\":\"user-2\",\"name\":\"Bob\"}")
                                .changeType(ChangeType.SUB_TABLE_ROW_UPDATE)
                                .build();
                when(changeHistoryRepository.findByProcessInstanceIdOrderByTimestampAsc("process-1"))
                                .thenReturn(List.of(legacyRecord, atomicStructuredValue));
                when(userRepository.findAllById(any(Iterable.class))).thenReturn(List.of());
                when(workflowEngineClient.getTaskHistory("process-1")).thenReturn(Optional.empty());
                when(processInstanceRepository.findById("process-1")).thenReturn(Optional.empty());
                var records = changeHistoryComponent.getChangeHistory("process-1");
                assertThat(records).extracting(record -> record.getFieldName())
                                .containsExactly("amount", "currency", "approver");
                assertThat(records.get(2).getNewValue()).isEqualTo("{\"id\":\"user-2\",\"name\":\"Bob\"}");
        }

        @Example
        @Label("Technical rows are not exposed as user changes")
        void technicalFieldIsNotUserVisible() {
                setUp();
                ChangeHistory record = ChangeHistory.builder()
                                .id(1L)
                                .processInstanceId("process-1")
                                .taskInstanceId("task-1")
                                .stageId("Task_Assign")
                                .userId("user-1")
                                .timestamp(java.time.Instant.parse("2026-07-23T00:00:00Z"))
                                .fieldName("assignee_display_name")
                                .subTableName("transaction_assignment")
                                .rowIdentifier("ACQ-DC-PW-000003")
                                .oldValue(null)
                                .newValue("Liam L Li")
                                .changeType(ChangeType.SUB_TABLE_ROW_UPDATE)
                                .build();
                when(changeHistoryRepository.findByProcessInstanceIdOrderByTimestampAsc("process-1"))
                                .thenReturn(List.of(record));
                when(userRepository.findAllById(any(Iterable.class))).thenReturn(List.of());
                when(workflowEngineClient.getTaskHistory("process-1")).thenReturn(Optional.empty());
                when(processInstanceRepository.findById("process-1"))
                                .thenReturn(Optional.of(ProcessInstance.builder()
                                                .id("process-1")
                                                .processDefinitionKey("FU_ASSIGN")
                                                .build()));
                when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("FU_ASSIGN")))
                                .thenReturn(List.of(
                                                "{\"rule\":[{\"field\":\"assignee\",\"title\":\"Assign To\"}],\"subForms\":{\"64\":{\"rule\":[{\"field\":\"assignee\",\"title\":\"Assign To\"}]}}}"));
                List<ChangeHistoryRecord> records = changeHistoryComponent.getChangeHistory("process-1");
                assertThat(records).isEmpty();
        }

        @Example
        @Label("Unconfigured ID fields are not exposed as user changes")
        void unconfiguredIdFieldIsNotUserVisible() {
                setUp();
                ChangeHistory record = ChangeHistory.builder()
                                .id(1L)
                                .processInstanceId("process-1")
                                .userId("user-1")
                                .timestamp(java.time.Instant.parse("2026-07-23T00:00:00Z"))
                                .fieldName("customer_id")
                                .oldValue("customer-1")
                                .newValue("customer-2")
                                .changeType(ChangeType.FIELD_UPDATE)
                                .build();
                when(changeHistoryRepository.findByProcessInstanceIdOrderByTimestampAsc("process-1"))
                                .thenReturn(List.of(record));
                when(userRepository.findAllById(any(Iterable.class))).thenReturn(List.of());
                when(workflowEngineClient.getTaskHistory("process-1")).thenReturn(Optional.empty());
                when(processInstanceRepository.findById("process-1"))
                                .thenReturn(Optional.of(ProcessInstance.builder()
                                                .id("process-1")
                                                .processDefinitionKey("FU_CUSTOMER")
                                                .build()));
                when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("FU_CUSTOMER")))
                                .thenReturn(List.of("{\"rule\":[{\"field\":\"customer\",\"title\":\"Customer\"}]}"));
                List<ChangeHistoryRecord> records = changeHistoryComponent.getChangeHistory("process-1");
                assertThat(records).isEmpty();
        }

        @Example
        @Label("Editable legacy alias in one sub-table does not expose the same field in another")
        @SuppressWarnings("unchecked")
        void legacyAliasEditabilityIsScopedToItsPhysicalSubTable() throws Exception {
                setUp();
                ChangeHistory record = ChangeHistory.builder()
                                .id(1L).processInstanceId("process-1").userId("user-1")
                                .timestamp(java.time.Instant.parse("2026-07-23T00:00:00Z"))
                                .fieldName("participant_id").subTableName("beta").rowIdentifier("ROW-1")
                                .oldValue("OLD").newValue("NEW")
                                .changeType(ChangeType.SUB_TABLE_ROW_UPDATE).build();
                when(changeHistoryRepository.findByProcessInstanceIdOrderByTimestampAsc("process-1"))
                                .thenReturn(List.of(record));
                when(userRepository.findAllById(any(Iterable.class))).thenReturn(List.of());
                when(workflowEngineClient.getTaskHistory("process-1")).thenReturn(Optional.empty());
                when(processInstanceRepository.findById("process-1")).thenReturn(Optional.of(
                                ProcessInstance.builder().id("process-1").processDefinitionKey("FU_SCOPE").build()));
                when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("FU_SCOPE")))
                                .thenAnswer(invocation -> {
                                        String sql = invocation.getArgument(0);
                                        RowMapper<Object> mapper = invocation.getArgument(1);
                                        ResultSet rs = mock(ResultSet.class);
                                        String config = "{\"rule\":[],\"subForms\":{\"alpha\":{\"rule\":[{\"field\":\"participant_id\"}]}}}";
                                        if (sql.contains("SELECT binding.id")) {
                                                when(rs.getString("id")).thenReturn("50001");
                                                when(rs.getString("table_name")).thenReturn("alpha");
                                                when(rs.getString("table_display_name")).thenReturn("Alpha");
                                        } else if (sql.contains("SELECT fd.id,")) {
                                                when(rs.getLong("id")).thenReturn(60001L);
                                                when(rs.getString("config_json")).thenReturn(config);
                                        } else if (sql.contains("SELECT td.table_name")) {
                                                when(rs.getString("table_name")).thenReturn("beta");
                                                when(rs.getString("field_name")).thenReturn("participant_id");
                                                when(rs.getString("display_name")).thenReturn("Participant");
                                                when(rs.getInt("sort_order")).thenReturn(1);
                                        } else {
                                                when(rs.getString("config_json")).thenReturn(config);
                                        }
                                        return List.of(mapper.mapRow(rs, 0));
                                });
                assertThat(changeHistoryComponent.getChangeHistory("process-1")).isEmpty();
        }

        @Example
        @Label("Sub-table diff treats space and underscore aliases as one table")
        void subTableDiffMatchesAliasesByNormalizedNameAndRowId() {
                ChangeHistoryComponent mockedHistory = mock(ChangeHistoryComponent.class);
                // These rows are keyed by `row_id`, so that is the primary key their table declares.
                // Identity comes from this configuration lookup, not from the column's name.
                when(mockedHistory.designerPrimaryKeyFieldsForSliceKey(anyString()))
                                .thenReturn(List.of("row_id"));
                TaskFormSubTableChangeRecorder recorder = new TaskFormSubTableChangeRecorder(mockedHistory);
                ChangeHistoryContext context = ChangeHistoryContext.builder()
                                .processInstanceId("process-1")
                                .taskInstanceId("task-1")
                                .stageId("stage-1")
                                .userId("45455063")
                                .build();
                Map<String, Object> oldRow = new LinkedHashMap<>();
                oldRow.put("arn", "1");
                oldRow.put("row_id", "ATM-DC-PW-TRANS-000010");
                oldRow.put("card_number", "1");
                oldRow.put("merchant_name", "2");
                oldRow.put("updated_at", "2026-07-06 14:16:33");
                oldRow.put("updated_by", "Liam L Li");
                Map<String, Object> newRow = new LinkedHashMap<>();
                newRow.put("arn", "1");
                newRow.put("row_id", "ATM-DC-PW-TRANS-000010");
                newRow.put("card_number", "12");
                newRow.put("merchant_name", "23");
                newRow.put("updated_at", "2026-07-06 14:22:21");
                newRow.put("updated_by", "Liam L Li");
                Map<String, Object> oldSubTables = Map.of("atm transaction", List.of(oldRow));
                Map<String, Object> newSubTables = Map.of("atm_transaction", List.of(newRow));
                recorder.recordSubTableChangeHistory(context, oldSubTables, newSubTables);
                ArgumentCaptor<String> tableNameCaptor = ArgumentCaptor.forClass(String.class);
                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<SubTableChange>> changesCaptor = ArgumentCaptor.forClass(List.class);
                verify(mockedHistory, times(1)).recordSubTableChanges(eq(context), tableNameCaptor.capture(),
                                changesCaptor.capture());
                assertThat(tableNameCaptor.getValue()).isEqualTo("atm_transaction");
                assertThat(changesCaptor.getValue()).hasSize(1);
                SubTableChange change = changesCaptor.getValue().get(0);
                assertThat(change.getChangeType()).isEqualTo("ROW_UPDATE");
                assertThat(change.getRowIdentifier()).isEqualTo("ATM-DC-PW-TRANS-000010");
                assertThat(change.getOldValues())
                                .containsEntry("card_number", "1")
                                .containsEntry("merchant_name", "2");
                assertThat(change.getNewValues())
                                .containsEntry("card_number", "12")
                                .containsEntry("merchant_name", "23");
                assertThat(change.getOldValues()).doesNotContainKeys("row_id", "updated_at", "updated_by");
                assertThat(change.getNewValues()).doesNotContainKeys("row_id", "updated_at", "updated_by");
        }

        // ========== Data class ==========
        static class SubTableChangeSet {
                String processInstanceId;
                String taskInstanceId;
                String stageId;
                String userId;
                String subTableName;
                List<SubTableChange> changes;
        }

        // ========== Arbitraries ==========
        @Provide
        Arbitrary<SubTableChangeSet> subTableChangeSets() {
                Arbitrary<String> processIds = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(15)
                                .map(s -> "proc_" + s);
                Arbitrary<String> taskIds = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(15)
                                .map(s -> "task_" + s);
                Arbitrary<String> stageIds = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10)
                                .map(s -> "stage_" + s);
                Arbitrary<String> userIds = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10)
                                .map(s -> "user_" + s);
                Arbitrary<String> tableNames = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                                .map(s -> "table_" + s);
                Arbitrary<Integer> changeCounts = Arbitraries.integers().between(1, 8);
                return Combinators
                                .combine(processIds, taskIds.injectNull(0.3), stageIds, userIds, tableNames,
                                                changeCounts)
                                .as((procId, taskId, stageId, userId, tableName, count) -> {
                                        SubTableChangeSet set = new SubTableChangeSet();
                                        set.processInstanceId = procId;
                                        set.taskInstanceId = taskId;
                                        set.stageId = stageId;
                                        set.userId = userId;
                                        set.subTableName = tableName;
                                        set.changes = new ArrayList<>();
                                        String[] changeTypes = { "ROW_ADD", "ROW_UPDATE", "ROW_DELETE" };
                                        for (int i = 0; i < count; i++) {
                                                String changeType = changeTypes[i % changeTypes.length];
                                                Map<String, Object> oldVals = "ROW_ADD".equals(changeType) ? null
                                                                : Map.of("col_" + i, "old_" + i);
                                                Map<String, Object> newVals = "ROW_DELETE".equals(changeType) ? null
                                                                : Map.of("col_" + i, "new_" + i);
                                                SubTableChange change = SubTableChange.builder()
                                                                .changeType(changeType)
                                                                .rowIdentifier("row_" + i)
                                                                .oldValues(oldVals)
                                                                .newValues(newVals)
                                                                .build();
                                                set.changes.add(change);
                                        }
                                        return set;
                                });
        }
}