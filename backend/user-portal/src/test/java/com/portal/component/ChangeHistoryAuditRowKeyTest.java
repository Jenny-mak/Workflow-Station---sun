package com.portal.component;

import com.platform.common.jdbc.SubTableRowIdentity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeHistoryAuditRowKeyTest {

    @Test
    void configuredPrimaryKeyWinsOverPlatformKey() {
        Map<String, Object> left = row("item-1", "uuid-A");
        Map<String, Object> right = row("item-1", "uuid-B");
        assertThat(ChangeHistoryAuditRowKey.sameLogicalRow(left, right, List.of("item_id"))).isTrue();
    }

    @Test
    void differentPrimaryKeysAreNotTheSameRowEvenWithTheSameStage() {
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("item_id", "a");
        left.put("stage", "OPEN");
        Map<String, Object> right = new LinkedHashMap<>();
        right.put("item_id", "b");
        right.put("stage", "OPEN");
        assertThat(ChangeHistoryAuditRowKey.sameLogicalRow(left, right, List.of("item_id"))).isFalse();
    }

    @Test
    void platformKeyMatchesWhenNoPrimaryKeyIsPresent() {
        Map<String, Object> left = new LinkedHashMap<>();
        left.put(SubTableRowIdentity.CANONICAL_FIELD, "uuid-1");
        left.put("stage", "OPEN");
        Map<String, Object> right = new LinkedHashMap<>();
        right.put(SubTableRowIdentity.CANONICAL_FIELD, "uuid-1");
        right.put("stage", "OPEN");
        assertThat(ChangeHistoryAuditRowKey.sameLogicalRow(left, right, List.of())).isTrue();
    }

    @Test
    void businessValuesAloneNeverIdentifyARow() {
        Map<String, Object> left = Map.of("stage", "OPEN", "channel", "Email");
        Map<String, Object> right = Map.of("stage", "OPEN", "channel", "Email");
        assertThat(ChangeHistoryAuditRowKey.sameLogicalRow(left, right, List.of("item_id"))).isFalse();
        assertThat(ChangeHistoryAuditRowKey.derive(left, List.of("item_id"))).isNull();
    }

    @Test
    void changeHistoryIdentifierPrefersConfiguredPrimaryKeyOverPlatformUuid() {
        Map<String, Object> row = row("item-1", "uuid-A");
        assertThat(ChangeHistoryComponent.resolveRowIdentifier(row, List.of("item_id")))
                .isEqualTo("item-1");
        assertThat(ChangeHistoryAuditRowKey.derive(row, List.of("item_id"))).isEqualTo("item-1");
    }

    @Test
    void incompleteCompositePrimaryKeyDoesNotIdentifyARow() {
        Map<String, Object> left = Map.of("id", "1");
        Map<String, Object> right = Map.of("id", "1", "row_id", "a");
        assertThat(ChangeHistoryAuditRowKey.matchPrimaryKey(left, List.of("id", "row_id"))).isNull();
        assertThat(ChangeHistoryAuditRowKey.sameLogicalRow(left, right, List.of("id", "row_id"))).isFalse();
    }

    @Test
    void compositePrimaryKeyIncludesEveryConfiguredFieldName() {
        Map<String, Object> left = Map.of("id", "1", "row_id", "a");
        Map<String, Object> right = Map.of("id", "1", "row_id", "b");
        assertThat(ChangeHistoryAuditRowKey.matchPrimaryKey(left, List.of("id", "row_id")))
                .isEqualTo("id=1|row_id=a");
        assertThat(ChangeHistoryAuditRowKey.sameLogicalRow(left, right, List.of("id", "row_id")))
                .isFalse();
    }

    private static Map<String, Object> row(String itemId, String platformKey) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("item_id", itemId);
        map.put(SubTableRowIdentity.CANONICAL_FIELD, platformKey);
        return map;
    }
}
