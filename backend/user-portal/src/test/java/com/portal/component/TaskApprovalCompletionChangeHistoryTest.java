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
                List.of(Map.of("row_id", "corr-1", "channel", "Email", "assignee", "user-b")));
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
                List.of(Map.of("row_id", "uuid-B", "channel", "SMS")));
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
    @DisplayName("two complete rows with the same payload stay two rows at the diff layer")
    void twoCompleteIdenticalPayloadsRemainTwoRows() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of(
                        "row_id", "uuid-old",
                        "correspondence_channel", "Email",
                        "correspondence_type", "Customer Notification",
                        "mdc_status", "Draft",
                        "processed_date", "2026-09-05")),
                List.of(
                        Map.of(
                                "row_id", "uuid-new-1",
                                "correspondence_channel", "Letter",
                                "correspondence_type", "Customer Notification",
                                "mdc_status", "Draft",
                                "processed_date", "2026-09-05"),
                        Map.of(
                                "row_id", "uuid-new-2",
                                "correspondence_channel", "Letter",
                                "correspondence_type", "Customer Notification",
                                "mdc_status", "Draft",
                                "processed_date", "2026-09-05")));
        assertEquals(2, changes.size());
        assertEquals(1, changes.stream().filter(c -> "ROW_UPDATE".equals(c.getChangeType())).count());
        assertEquals(1, changes.stream().filter(c -> "ROW_ADD".equals(c.getChangeType())).count());
        assertEquals(0, changes.stream().filter(c -> "ROW_DELETE".equals(c.getChangeType())).count());
    }

    @Test
    @DisplayName("editing one row and adding a distinct second row is update+add, not a phantom delete")
    void extraDistinctRowIsUpdateAndAddNotDelete() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of(
                        "row_id", "uuid-old",
                        "correspondence_type", "Customer Notification",
                        "mdc_status", "Draft",
                        "processed_date", "2026-09-05")),
                List.of(
                        Map.of(
                                "row_id", "uuid-new-1",
                                "correspondence_type", "Customer Notification",
                                "mdc_status", "Acknowledged",
                                "processed_date", "2026-09-26"),
                        Map.of(
                                "row_id", "uuid-new-2",
                                "correspondence_type", "Complaint",
                                "mdc_status", "Closed",
                                "processed_date", "2026-09-10")));
        assertEquals(2, changes.size());
        assertEquals(1, changes.stream().filter(c -> "ROW_UPDATE".equals(c.getChangeType())).count());
        assertEquals(1, changes.stream().filter(c -> "ROW_ADD".equals(c.getChangeType())).count());
        assertEquals(0, changes.stream().filter(c -> "ROW_DELETE".equals(c.getChangeType())).count());
    }

    @Test
    @DisplayName("an identity-only same-id overlay does not record clearing every business field")
    void identityOnlyOverlayDoesNotRecordFieldClears() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of(
                        "row_id", "uuid-same",
                        "correspondence_channel", "Email",
                        "correspondence_mode", "Outbound",
                        "mdc_status", "Draft")),
                List.of(Map.of("row_id", "uuid-same")));
        assertEquals(List.of(), changes);
    }

    @Test
    @DisplayName("clearing every business field on the same row is recorded as an update")
    void clearingEveryBusinessFieldIsRecorded() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of(
                        "row_id", "uuid-same",
                        "correspondence_channel", "Email",
                        "correspondence_mode", "Outbound",
                        "mdc_status", "Draft")),
                List.of(Map.of(
                        "row_id", "uuid-same",
                        "correspondence_channel", "",
                        "correspondence_mode", "",
                        "mdc_status", "")));
        assertEquals(1, changes.size());
        assertEquals("ROW_UPDATE", changes.get(0).getChangeType());
        assertEquals(Map.of(
                "correspondence_channel", "Email",
                "correspondence_mode", "Outbound",
                "mdc_status", "Draft"), changes.get(0).getOldValues());
        assertEquals(Map.of(
                "correspondence_channel", "",
                "correspondence_mode", "",
                "mdc_status", ""), changes.get(0).getNewValues());
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