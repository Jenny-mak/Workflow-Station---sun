package com.portal.util;

import com.platform.common.list.ListColumnMeta;
import com.platform.common.list.ListColumnMeta.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoTaskColumnSpecTest {

    @Test
    void requestIdIsTextSearchableAndSortable() {
        ListColumnMeta requestId = column("requestId");
        assertThat(requestId.kind()).isEqualTo(Kind.TEXT);
        assertThat(requestId.filterable()).isTrue();
        assertThat(requestId.sortable()).isTrue();
        assertThat(requestId.operators()).contains("contains", "startsWith", "eq");
    }

    @Test
    void taskNameRemainsOrdinaryText() {
        ListColumnMeta taskName = column("taskName");
        assertThat(taskName.filterable()).isTrue();
        assertThat(taskName.sortable()).isTrue();
        assertThat(taskName.operators()).contains("contains");
    }

    @Test
    void assignmentTypeOptionsDropVirtualGroupAndAddBuRole() {
        List<String> values = column("assignmentType").options().stream()
                .map(ListColumnMeta.Option::value)
                .toList();
        assertThat(values).contains("USER", "BU_ROLE", "DEPT_ROLE").doesNotContain("VIRTUAL_GROUP");
    }

    @Test
    void claimedByColumnIsPresent() {
        assertThat(column("assigneeName").label()).isEqualTo("task.claimedBy");
    }

    @Test
    void currentStepIsNotAVisibleColumn() {
        assertThat(TodoTaskColumnSpec.columns()).noneMatch(c -> "currentStepName".equals(c.field()));
    }

    @Test
    void functionUnitIsTextSearchableAndSortable() {
        ListColumnMeta functionUnit = column("functionUnitCode");
        assertThat(functionUnit.kind()).isEqualTo(Kind.TEXT);
        assertThat(functionUnit.filterable()).isTrue();
        assertThat(functionUnit.sortable()).isTrue();
        assertThat(functionUnit.operators()).contains("contains", "startsWith", "eq");
    }

    @Test
    void hiddenColumnsStayDeclaredForRestore() {
        assertThat(TodoTaskColumnSpec.columns())
                .extracting(ListColumnMeta::field)
                .containsExactly(
                        "requestId",
                        "functionUnitCode",
                        "taskName",
                        "assignmentType",
                        "createTime",
                        "processDefinitionName",
                        "initiatorName",
                        "assigneeName",
                        "priority",
                        "dueDate");
        assertThat(TodoTaskColumnSpec.VISIBLE_FIELDS).containsExactly(
                "requestId",
                "functionUnitCode",
                "taskName",
                "assignmentType",
                "assigneeName",
                "createTime");
    }

    private static ListColumnMeta column(String field) {
        return TodoTaskColumnSpec.columns().stream()
                .filter(c -> field.equals(c.field()))
                .findFirst()
                .orElseThrow();
    }
}
