package com.portal.component;

import com.portal.entity.ChangeHistory;
import com.portal.enums.ChangeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeHistoryDisplayCollapserTest {

    private static final Instant START = Instant.parse("2026-09-04T11:41:06.594Z");
    private static final Instant ASSIGNMENT = Instant.parse("2026-09-04T11:41:59.978Z");
    private static final Instant INVESTIGATION = Instant.parse("2026-09-04T11:43:07.068Z");

    @Test
    void investigationFillInPlusIdenticalAddKeepsOneUpdate() {
        List<ChangeHistory> kept = ChangeHistoryDisplayCollapser.collapse(List.of(
                row(1L, "Activity_1c23xsu", INVESTIGATION, ChangeType.SUB_TABLE_ROW_UPDATE,
                        "f65cf040", "correspondence_channel", "", "Letter"),
                row(2L, "Activity_1c23xsu", INVESTIGATION, ChangeType.SUB_TABLE_ROW_UPDATE,
                        "f65cf040", "mdc_status", "", "Draft"),
                row(3L, "Activity_1c23xsu", INVESTIGATION, ChangeType.SUB_TABLE_ROW_ADD,
                        "0ef1ffde", "correspondence_channel", null, "Letter"),
                row(4L, "Activity_1c23xsu", INVESTIGATION, ChangeType.SUB_TABLE_ROW_ADD,
                        "0ef1ffde", "mdc_status", null, "Draft"),
                row(5L, "Activity_1c23xsu", INVESTIGATION, ChangeType.SUB_TABLE_ROW_ADD,
                        "0ef1ffde", "correspondence_type", null, "Customer Notification")));
        assertThat(kept).extracting(ChangeHistory::getChangeType)
                .containsOnly(ChangeType.SUB_TABLE_ROW_UPDATE);
        assertThat(kept).extracting(ChangeHistory::getRowIdentifier)
                .containsOnly("f65cf040");
    }

    @Test
    void assignmentClearAllRemainsVisible() {
        List<ChangeHistory> kept = ChangeHistoryDisplayCollapser.collapse(List.of(
                row(1L, "Activity_0ayvpi1", ASSIGNMENT, ChangeType.SUB_TABLE_ROW_UPDATE,
                        "fcbc3589", "correspondence_channel", "Email", ""),
                row(2L, "Activity_0ayvpi1", ASSIGNMENT, ChangeType.SUB_TABLE_ROW_UPDATE,
                        "fcbc3589", "mdc_status", "Draft", "")));
        assertThat(kept).hasSize(2);
        assertThat(kept).extracting(ChangeHistory::getChangeType)
                .containsOnly(ChangeType.SUB_TABLE_ROW_UPDATE);
    }

    @Test
    void startAddIsKept() {
        List<ChangeHistory> kept = ChangeHistoryDisplayCollapser.collapse(List.of(
                row(1L, "Activity_092hlui", START, ChangeType.SUB_TABLE_ROW_ADD,
                        null, "correspondence_channel", null, "Email")));
        assertThat(kept).hasSize(1);
    }

    @Test
    void editingOneRowAndAddingADistinctRowKeepsUpdateAndAdd() {
        Instant ts = Instant.parse("2026-09-04T12:00:00Z");
        List<ChangeHistory> kept = ChangeHistoryDisplayCollapser.collapse(List.of(
                row(1L, "stage", ts, ChangeType.SUB_TABLE_ROW_UPDATE,
                        "row-a", "correspondence_channel", "Email", "Letter"),
                row(2L, "stage", ts, ChangeType.SUB_TABLE_ROW_ADD,
                        "row-b", "correspondence_channel", null, "SMS")));
        assertThat(kept).hasSize(2);
        assertThat(kept).extracting(ChangeHistory::getChangeType)
                .containsExactly(ChangeType.SUB_TABLE_ROW_UPDATE, ChangeType.SUB_TABLE_ROW_ADD);
    }

    @Test
    void clearingASingleFieldIsStillShown() {
        Instant ts = Instant.parse("2026-09-04T12:01:00Z");
        List<ChangeHistory> kept = ChangeHistoryDisplayCollapser.collapse(List.of(
                row(1L, "stage", ts, ChangeType.SUB_TABLE_ROW_UPDATE,
                        "row-a", "correspondence_channel", "Email", ""),
                row(2L, "stage", ts, ChangeType.SUB_TABLE_ROW_UPDATE,
                        "row-a", "mdc_status", "Draft", "Acknowledged")));
        assertThat(kept).hasSize(2);
    }

    private static ChangeHistory row(
            Long id,
            String stageId,
            Instant timestamp,
            ChangeType type,
            String rowId,
            String field,
            String oldValue,
            String newValue) {
        return ChangeHistory.builder()
                .id(id)
                .processInstanceId("83ba5829-a855-11f1-9caf-deda9a91026e")
                .stageId(stageId)
                .userId("user-1")
                .timestamp(timestamp)
                .changeType(type)
                .subTableName("atm_correspondence")
                .rowIdentifier(rowId)
                .fieldName(field)
                .oldValue(oldValue)
                .newValue(newValue)
                .build();
    }
}
