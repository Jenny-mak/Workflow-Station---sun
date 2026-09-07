package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.client.WorkflowEngineClient;
import com.portal.dto.ChangeHistoryRecord;
import com.portal.entity.ChangeHistory;
import com.portal.entity.ProcessInstance;
import com.portal.enums.ChangeType;
import com.portal.repository.ChangeHistoryRepository;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.testsupport.PortalTransactionTestSupport;
import com.platform.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ATM / card layouts nest business fields under elCard → fcRow → col. Visibility
 * used a shallow {@code rule[*].field} allow-list, so those nested names were
 * dropped and every top-level history row disappeared on My Request / To Do /
 * Completed Tasks (same GET /change-history).
 */
class ChangeHistoryNestedFormFieldVisibilityTest {

    private static final String PROCESS_ID = "proc-nested-history";
    private static final String FU_CODE = "atm-nested-fu";

    /**
     * PROCESS form: widget ids + a card that actually holds case_stage.
     * DETAIL form: unrelated top-level fields that used to make the allow-list
     * non-empty without including the nested names.
     */
    private static final String PROCESS_FORM_JSON = """
            {"rule":[
              {"field":"Fkr0mtbbiqzlabc","type":"elButton"},
              {"type":"elCard","children":[
                {"type":"fcRow","children":[
                  {"field":"case_stage","title":"Case Stage","type":"select"},
                  {"field":"case_status","title":"Case Status","type":"select"}
                ]}
              ]}
            ],"subForms":{"1141":{"rule":[
              {"field":"correspondence_id","title":"Correspondence ID","type":"input"}
            ]}}}
            """;
    private static final String DETAIL_FORM_JSON = """
            {"rule":[
              {"field":"merchant_credit","title":"Merchant Credit","type":"input"}
            ]}
            """;

    private ChangeHistoryRepository changeHistoryRepository;
    private ChangeHistoryComponent component;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        changeHistoryRepository = mock(ChangeHistoryRepository.class);
        ProcessInstanceRepository processInstanceRepository = mock(ProcessInstanceRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        WorkflowEngineClient workflowEngineClient = mock(WorkflowEngineClient.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        UserPortalAuditEnricher auditEnricher = mock(UserPortalAuditEnricher.class);

        when(userRepository.findAllById(any())).thenReturn(Collections.emptyList());
        when(workflowEngineClient.getTaskHistory(anyString())).thenReturn(Optional.empty());
        when(auditEnricher.resolveStageNamesFromDb(any())).thenReturn(Map.of());
        when(processInstanceRepository.findById(PROCESS_ID)).thenReturn(Optional.of(
                ProcessInstance.builder().id(PROCESS_ID).processDefinitionKey(FU_CODE).build()));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(FU_CODE)))
                .thenReturn(List.of(PROCESS_FORM_JSON, DETAIL_FORM_JSON));

        component = new ChangeHistoryComponent(
                changeHistoryRepository,
                processInstanceRepository,
                userRepository,
                workflowEngineClient,
                jdbcTemplate,
                new ObjectMapper(),
                auditEnricher,
                mock(UserPortalAuditProcessInstanceMatcher.class),
                PortalTransactionTestSupport.noopPlatformTransactionManager());
    }

    @Test
    void nestedMainFormFieldsRemainVisibleWhenOtherFormsHaveTopLevelFields() {
        Instant now = Instant.parse("2026-09-04T03:50:00Z");
        when(changeHistoryRepository.findByProcessInstanceIdOrderByTimestampAsc(PROCESS_ID))
                .thenReturn(List.of(
                        history(1L, "case_stage", "Open", now),
                        history(2L, "case_status", "New", now.plusSeconds(1)),
                        history(3L, "not_on_any_form", "secret", now.plusSeconds(2))));

        List<ChangeHistoryRecord> records = component.getChangeHistory(PROCESS_ID);

        assertThat(records).extracting(ChangeHistoryRecord::getFieldName)
                .containsExactly("case_stage", "case_status");
        assertThat(records.get(0).getFieldLabel()).isEqualTo("Case Stage");
        assertThat(records.get(1).getFieldLabel()).isEqualTo("Case Status");
    }

    @Test
    @SuppressWarnings("unchecked")
    void miTaskRowFilterKeepsSharedSubTablesAndHidesOtherCollectionRows() {
        Instant now = Instant.parse("2026-09-04T10:36:00Z");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(PROCESS_ID)))
                .thenReturn(List.of("ATM_Transaction"));
        when(changeHistoryRepository.findByProcessInstanceIdOrderByTimestampAsc(PROCESS_ID))
                .thenReturn(List.of(
                        history(1L, "case_stage", "Open", now),
                        subTableHistory(2L, "atm_correspondence", "Corr-000043",
                                "correspondence_id", "Corr-000043", now.plusSeconds(1)),
                        subTableHistory(3L, "atm_transaction", "ATM-DC-PW-TRANS-000039",
                                "card_number", "1", now.plusSeconds(2)),
                        subTableHistory(4L, "atm_transaction", "ATM-DC-PW-TRANS-OTHER",
                                "card_number", "9", now.plusSeconds(3))));

        List<ChangeHistoryRecord> records = component.getChangeHistory(
                PROCESS_ID, "ATM-DC-PW-TRANS-000039");

        assertThat(records).extracting(ChangeHistoryRecord::getFieldName)
                .containsExactly("case_stage", "correspondence_id", "card_number");
        assertThat(records).extracting(ChangeHistoryRecord::getRowIdentifier)
                .containsExactly(null, "Corr-000043", "ATM-DC-PW-TRANS-000039");
    }

    private static ChangeHistory history(long id, String fieldName, String newValue, Instant timestamp) {
        return ChangeHistory.builder()
                .id(id)
                .processInstanceId(PROCESS_ID)
                .userId("user-1")
                .timestamp(timestamp)
                .fieldName(fieldName)
                .oldValue(null)
                .newValue(newValue)
                .changeType(ChangeType.FIELD_UPDATE)
                .build();
    }

    private static ChangeHistory subTableHistory(long id, String table, String rowId,
            String fieldName, String newValue, Instant timestamp) {
        return ChangeHistory.builder()
                .id(id)
                .processInstanceId(PROCESS_ID)
                .userId("user-1")
                .timestamp(timestamp)
                .fieldName(fieldName)
                .subTableName(table)
                .rowIdentifier(rowId)
                .oldValue(null)
                .newValue(newValue)
                .changeType(ChangeType.SUB_TABLE_ROW_ADD)
                .build();
    }
}
