package com.portal.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OwnerCaseHandlerCalculator")
class OwnerCaseHandlerCalculatorTest {

    @Test
    @DisplayName("legacy CURRENT_ASSIGNEE is Case Handler; CURRENT_HANDLER is not an alias")
    void legacySourceAlias() {
        assertThat(OwnerCaseHandlerCalculator.normalizeSource("CURRENT_ASSIGNEE"))
                .isEqualTo(OwnerCaseHandlerCalculator.SOURCE_CASE_HANDLER);
        assertThat(OwnerCaseHandlerCalculator.isCaseHandler("CASE_HANDLER")).isTrue();
        assertThat(OwnerCaseHandlerCalculator.isCaseHandler("CURRENT_ASSIGNEE")).isTrue();
        assertThat(OwnerCaseHandlerCalculator.isCaseHandler("CURRENT_HANDLER")).isFalse();
        assertThat(OwnerCaseHandlerCalculator.isCaseHandler("CREATOR")).isFalse();
    }

    @Test
    @DisplayName("in-progress ordinary node writes people; MI writes step:; terminal clears")
    void mainInProgressRules() {
        assertThat(OwnerCaseHandlerCalculator.mainInProgress("RUNNING", null, "u-a", null))
                .isEqualTo("user:u-a");
        assertThat(OwnerCaseHandlerCalculator.mainInProgress("RUNNING", "multi", "u-a", null))
                .isEqualTo("step:multi");
        assertThat(OwnerCaseHandlerCalculator.mainInProgress("COMPLETED", null, "u-a", null))
                .isEmpty();
        assertThat(OwnerCaseHandlerCalculator.mainInProgress("RUNNING", null, null, "u-a,u-b"))
                .isEqualTo("user:u-a,user:u-b");
    }

    @Test
    @DisplayName("complete writes the actor, not the assignee")
    void actorValue() {
        assertThat(OwnerCaseHandlerCalculator.actorValue("u-delegatee")).isEqualTo("user:u-delegatee");
        assertThat(OwnerCaseHandlerCalculator.actorValue("  ")).isEmpty();
        assertThat(OwnerCaseHandlerCalculator.mainOnComplete(null, "u-delegatee"))
                .isEqualTo("user:u-delegatee");
        assertThat(OwnerCaseHandlerCalculator.mainOnComplete("multi", "u-delegatee"))
                .isEqualTo("step:multi");
    }

    @Test
    @DisplayName("application Current Assignee display matches main Case Handler")
    void applicationDisplay() {
        Map<String, String> mi = Map.of("sub form1", "multi");
        assertThat(OwnerCaseHandlerCalculator.applicationCurrentAssigneeDisplay(
                "RUNNING", "Review", Map.of(), "Bob")).isEqualTo("Bob");
        assertThat(OwnerCaseHandlerCalculator.applicationCurrentAssigneeDisplay(
                "RUNNING", "sub form1", mi, "Bob")).isEqualTo("multi");
        assertThat(OwnerCaseHandlerCalculator.applicationCurrentAssigneeDisplay(
                "COMPLETED", "Review", Map.of(), "Bob")).isNull();
        assertThat(OwnerCaseHandlerCalculator.applicationCurrentAssigneeDisplay(
                "RUNNING", "sub form1", null, "Bob")).isNull();
    }
}
