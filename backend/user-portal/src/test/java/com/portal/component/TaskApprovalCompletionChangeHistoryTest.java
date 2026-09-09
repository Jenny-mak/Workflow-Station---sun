package com.portal.component;

import com.platform.common.jdbc.SubTableRowIdentity;
import com.portal.dto.SubTableChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Task approval sub-table change-history baselines")
class TaskApprovalCompletionChangeHistoryTest {

    /**
     * Designer primary key for fixtures that store identity in {@code row_id}.
     * That name identifies a row only because the fixture declares it, not
     * because the platform guesses columns called {@code row_id}.
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
                List.of(Map.of("row_id", "transaction-1", "amount", 100)),
                PK));
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
    @DisplayName("workflow node fields that reach the diff are recorded, not skipped by name")
    void workflowNodeProgressFieldsAreRecordedWhenPresent() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of(
                        "row_id", "transaction-1",
                        "amount", 100,
                        "task_current_node", "Transaction Investigation")),
                List.of(Map.of(
                        "row_id", "transaction-1",
                        "amount", 100,
                        "task_current_node", "Mark Completed")),
                PK);
        assertEquals(1, changes.size());
        assertEquals("ROW_UPDATE", changes.get(0).getChangeType());
        assertEquals(Map.of("task_current_node", "Transaction Investigation"), changes.get(0).getOldValues());
        assertEquals(Map.of("task_current_node", "Mark Completed"), changes.get(0).getNewValues());
    }

    @Test
    @DisplayName("same configured primary key with a changed business field is a row update")
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
    @DisplayName("same configured PK with a new platform key is not add/delete")
    void identityChurnIsNotAUserOperation() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(row("item-1", "uuid-A", "channel", "Email")),
                List.of(row("item-1", "uuid-B", "channel", "Email")),
                List.of("item_id"));
        assertEquals(List.of(), changes);
    }

    @Test
    @DisplayName("sharing only a default stage value does not turn a new row into an update")
    void sharedDefaultStageDoesNotTurnAddIntoUpdate() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("item_id", "existing", "stage", "OPEN", "channel", "Email")),
                List.of(
                        Map.of("item_id", "existing", "stage", "OPEN", "channel", "Email"),
                        Map.of("item_id", "added", "stage", "OPEN", "channel", "SMS")),
                List.of("item_id"));
        assertEquals(1, changes.size());
        assertEquals("ROW_ADD", changes.get(0).getChangeType());
        assertEquals("added", changes.get(0).getRowIdentifier());
    }

    @Test
    @DisplayName("an unchanged default stage is not recorded again on a real field edit")
    void unchangedDefaultStageIsNotRecordedAgain() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("item_id", "existing", "stage", "OPEN", "channel", "Email")),
                List.of(Map.of("item_id", "existing", "stage", "OPEN", "channel", "Letter")),
                List.of("item_id"));
        assertEquals(1, changes.size());
        assertEquals("ROW_UPDATE", changes.get(0).getChangeType());
        assertEquals(Map.of("channel", "Email"), changes.get(0).getOldValues());
        assertEquals(Map.of("channel", "Letter"), changes.get(0).getNewValues());
        assertTrue(!changes.get(0).getNewValues().containsKey("stage"));
    }

    @Test
    @DisplayName("a new row that only shares a default stage is add, not an update of the old row")
    void newRowSharingOnlyDefaultStageIsAddNotUpdate() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(platformRow("uuid-A", "OPEN", null)),
                List.of(platformRow("uuid-B", "OPEN", "Email")));
        assertEquals(2, changes.size());
        assertEquals(1, changes.stream().filter(c -> "ROW_ADD".equals(c.getChangeType())).count());
        assertEquals(1, changes.stream().filter(c -> "ROW_DELETE".equals(c.getChangeType())).count());
        assertEquals(0, changes.stream().filter(c -> "ROW_UPDATE".equals(c.getChangeType())).count());
    }

    @Test
    @DisplayName("different platform keys with the same business values are add and delete")
    void differentPlatformKeysWithSameBusinessValuesAreAddAndDelete() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(platformRow("uuid-A", "OPEN", "Email")),
                List.of(platformRow("uuid-B", "OPEN", "Email")));
        assertEquals(2, changes.size());
        assertEquals(1, changes.stream().filter(c -> "ROW_ADD".equals(c.getChangeType())).count());
        assertEquals(1, changes.stream().filter(c -> "ROW_DELETE".equals(c.getChangeType())).count());
        assertEquals(0, changes.stream().filter(c -> "ROW_UPDATE".equals(c.getChangeType())).count());
    }

    @Test
    @DisplayName("empty-to-filled assignee_id is a real field change, not skipped by column name")
    void assigneeIdEmptyToFilledIsRecorded() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("row_id", "transaction-1", "amount", 100)),
                List.of(Map.of("row_id", "transaction-1", "amount", 100, "assignee_id", "user-1")),
                PK);
        assertEquals(1, changes.size());
        assertEquals("ROW_UPDATE", changes.get(0).getChangeType());
        assertEquals(Map.of("assignee_id", "user-1"), changes.get(0).getNewValues());
    }

    @Test
    @DisplayName("a business column named id is recorded when it is not the configured primary key")
    void businessColumnNamedIdIsRecorded() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("participant_key", "p1", "id", "old")),
                List.of(Map.of("participant_key", "p1", "id", "new")),
                List.of("participant_key"));
        assertEquals(1, changes.size());
        assertEquals("ROW_UPDATE", changes.get(0).getChangeType());
        assertEquals(Map.of("id", "old"), changes.get(0).getOldValues());
        assertEquals(Map.of("id", "new"), changes.get(0).getNewValues());
    }

    @Test
    @DisplayName("a truly new row is still recorded as add")
    void unmatchedNewRowIsAdd() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(),
                List.of(Map.of("row_id", "uuid-new", "channel", "Email")),
                PK);
        assertEquals(1, changes.size());
        assertEquals("ROW_ADD", changes.get(0).getChangeType());
    }

    @Test
    @DisplayName("two distinct identities with the same payload stay two rows")
    void twoCompleteIdenticalPayloadsRemainTwoRows() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("item_id", "old", "channel", "Email", "status", "Draft")),
                List.of(
                        Map.of("item_id", "new-1", "channel", "Letter", "status", "Draft"),
                        Map.of("item_id", "new-2", "channel", "Letter", "status", "Draft")),
                List.of("item_id"));
        assertEquals(3, changes.size());
        assertEquals(2, changes.stream().filter(c -> "ROW_ADD".equals(c.getChangeType())).count());
        assertEquals(1, changes.stream().filter(c -> "ROW_DELETE".equals(c.getChangeType())).count());
        assertEquals(0, changes.stream().filter(c -> "ROW_UPDATE".equals(c.getChangeType())).count());
    }

    @Test
    @DisplayName("editing one identified row and adding another is update+add")
    void extraDistinctRowIsUpdateAndAddNotDelete() {
        List<SubTableChange> changes = TaskApprovalCompletionComponent.computeSubTableRowChanges(
                List.of(Map.of("item_id", "kept", "status", "Draft")),
                List.of(
                        Map.of("item_id", "kept", "status", "Acknowledged"),
                        Map.of("item_id", "added", "status", "Closed")),
                List.of("item_id"));
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
                List.of(Map.of("row_id", "uuid-same")),
                PK);
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
                        "mdc_status", "")),
                PK);
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
                List.of(Map.of("row_id", "transaction-1", "amount", 125)),
                PK);
        assertEquals(1, changes.size());
        assertEquals("ROW_UPDATE", changes.get(0).getChangeType());
        assertEquals(Map.of("amount", 100), changes.get(0).getOldValues());
        assertEquals(Map.of("amount", 125), changes.get(0).getNewValues());
    }

    private static Map<String, Object> row(String itemId, String platformKey, String field, String value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("item_id", itemId);
        map.put(SubTableRowIdentity.CANONICAL_FIELD, platformKey);
        map.put(field, value);
        return map;
    }

    private static Map<String, Object> platformRow(String platformKey, String stage, String channel) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(SubTableRowIdentity.CANONICAL_FIELD, platformKey);
        map.put("stage", stage);
        if (channel != null) {
            map.put("channel", channel);
        }
        return map;
    }
}
