package com.portal.component;

import com.portal.dto.SubTableChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Task approval sub-table change-history baselines")
class TaskApprovalCompletionChangeHistoryTest {

    /**
     * The table's DESIGNER primary key, as production resolves and passes it per slice
     * ({@code dw_field_definitions.is_primary_key}). Rows below are keyed by {@code row_id}, so
     * that is this fixture's configured key — it identifies rows because the table declares it,
     * not because the platform assumes columns of that name are identities.
     */
    private static final List<String> PK = List.of("row_id");
    @Test
    @DisplayName("uses the pre-completion process state")
    void usesPreSyncSubTables() {
        Map<String, Object> existingSubTables = Map.of(
                "50938", List.of(Map.of("row_id", "transaction-1", "amount", 100)));
        Map<String, Object> preSyncVariables = Map.of("__subTables__", existingSubTables);
        Object resolved = TaskApprovalCompletionComponent.resolveSubTableHistoryBaseline(preSyncVariables);
        assertSame(existingSubTables, resolved);
        assertEquals(List.of(), TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("row_id", "transaction-1", "amount", 100)),
                List.of(Map.of("row_id", "transaction-1", "amount", 100))));
    }

    @Test
    @DisplayName("uses the latest saved process state instead of replaying the first-save baseline")
    void usesLatestPreSyncStateAfterIncrementalSave() {
        Map<String, Object> latestSavedSubTables = Map.of(
                "people", List.of(Map.of("id", "person-1", "age", 1)));
        Map<String, Object> preSyncVariables = Map.of("__subTables__", latestSavedSubTables);
        Object resolved = TaskApprovalCompletionComponent.resolveSubTableHistoryBaseline(preSyncVariables);
        assertSame(latestSavedSubTables, resolved);
    }

    @Test
    @DisplayName("does not audit workflow-node progress fields as a user sub-table edit")
    void ignoresWorkflowNodeProgressFields() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of(
                        "row_id", "transaction-1",
                        "amount", 100,
                        "task_current_node", "Transaction Investigation",
                        "sub_task_current_node", "Transaction Investigation",
                        "task_status", "IN_PROGRESS")),
                List.of(Map.of(
                        "row_id", "transaction-1",
                        "amount", 100,
                        "task_current_node", "Mark Completed",
                        "sub_task_current_node", "Mark Completed",
                        "task_status", "COMPLETED")));
        assertEquals(List.of(), changes);
    }

    @Test
    @DisplayName("same row_id with a changed business field is a row update")
    void sameRowIdWithChangedFieldIsUpdate() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("row_id", "corr-1", "channel", "Email", "assignee", "user-a")),
                List.of(Map.of("row_id", "corr-1", "channel", "Email", "assignee", "user-b")),
                PK);
        assertEquals(1, changes.size());
        assertEquals("ROW_UPDATE", changes.get(0).getChangeType());
        assertEquals("corr-1", changes.get(0).getRowIdentifier());
        assertEquals(Map.of("assignee", "user-a"), changes.get(0).getOldValues());
        assertEquals(Map.of("assignee", "user-b"), changes.get(0).getNewValues());
    }

    @Test
    @DisplayName("same business payload with a new row_id is identity churn, not a user add/delete")
    void identityChurnIsNotAUserOperation() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("row_id", "uuid-A", "channel", "Email")),
                List.of(Map.of("row_id", "uuid-B", "channel", "Email")));
        assertEquals(List.of(), changes);
    }

    @Test
    @DisplayName("anonymous resubmit of an identified row is not a user delete")
    void anonymousResubmitIsNotADelete() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("row_id", "uuid-A", "channel", "Email")),
                List.of(Map.of("channel", "Email")));
        assertEquals(List.of(), changes);
    }

    @Test
    @DisplayName("system-filled assignee_id is not a user edit")
    void assigneeAutofillIsNotAUserOperation() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("row_id", "transaction-1", "amount", 100)),
                List.of(Map.of("row_id", "transaction-1", "amount", 100, "assignee_id", "user-1")));
        assertEquals(List.of(), changes);
    }

    @Test
    @DisplayName("replacing a row's business values is recorded as an update")
    void singletonReplacementWithFieldChangeIsUpdate() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("row_id", "uuid-A", "channel", "Email")),
                List.of(Map.of("row_id", "uuid-B", "channel", "SMS")),
                PK);
        assertEquals(1, changes.size());
        assertEquals("ROW_UPDATE", changes.get(0).getChangeType());
        assertEquals("uuid-A", changes.get(0).getRowIdentifier());
        assertEquals(Map.of("channel", "Email"), changes.get(0).getOldValues());
        assertEquals(Map.of("channel", "SMS"), changes.get(0).getNewValues());
    }

    @Test
    @DisplayName("a truly new row is still recorded as add")
    void unmatchedNewRowIsAdd() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(),
                List.of(Map.of("row_id", "uuid-new", "channel", "Email")));
        assertEquals(1, changes.size());
        assertEquals("ROW_ADD", changes.get(0).getChangeType());
    }

    @Test
    @DisplayName("continues to record an actual user-editable sub-table field change")
    void recordsActualBusinessFieldChange() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("row_id", "transaction-1", "amount", 100)),
                List.of(Map.of("row_id", "transaction-1", "amount", 125)));
        assertEquals(1, changes.size());
        assertEquals("ROW_UPDATE", changes.get(0).getChangeType());
        assertEquals(Map.of("amount", 100), changes.get(0).getOldValues());
        assertEquals(Map.of("amount", 125), changes.get(0).getNewValues());
    }
}