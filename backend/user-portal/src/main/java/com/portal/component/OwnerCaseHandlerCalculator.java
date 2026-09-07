package com.portal.component;

import com.portal.service.ProcessAssigneeSnapshot;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure Case Handler value rules ({@code docs/design/owner-field-component.md} §3.3.3 / §6.7).
 * One calculation, two consumers: Owner column writes and My Request Current Assignee display.
 */
public final class OwnerCaseHandlerCalculator {

    public static final String USER_PREFIX = "user:";
    public static final String STEP_PREFIX = "step:";
    static final String SOURCE_CREATOR = "CREATOR";
    static final String SOURCE_CASE_HANDLER = "CASE_HANDLER";
    static final String SOURCE_CURRENT_ASSIGNEE = "CURRENT_ASSIGNEE";

    private OwnerCaseHandlerCalculator() {
    }

    static String normalizeSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return SOURCE_CREATOR;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (SOURCE_CASE_HANDLER.equals(normalized)
                || SOURCE_CURRENT_ASSIGNEE.equals(normalized)) {
            return SOURCE_CASE_HANDLER;
        }
        return normalized;
    }

    static boolean isCaseHandler(String source) {
        return SOURCE_CASE_HANDLER.equals(normalizeSource(source));
    }

    static boolean isTerminalStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String s = status.trim().toUpperCase(Locale.ROOT);
        return "COMPLETED".equals(s) || "WITHDRAWN".equals(s) || "REJECTED".equals(s)
                || "CANCELLED".equals(s);
    }

    static boolean isStepValue(String value) {
        return value != null && value.startsWith(STEP_PREFIX) && value.length() > STEP_PREFIX.length();
    }

    static String stepValue(String outerName) {
        if (outerName == null || outerName.isBlank()) {
            return "";
        }
        return STEP_PREFIX + outerName.trim();
    }

    static String actorValue(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            return "";
        }
        return USER_PREFIX + actorUserId.trim();
    }

    /**
     * MAIN value at Complete: keep {@code step:} while this node is an inner MI task;
     * otherwise write the actual operator. Inner MI people never land on MAIN.
     */
    static String mainOnComplete(String miOuterName, String actorUserId) {
        if (miOuterName != null && !miOuterName.isBlank()) {
            return stepValue(miOuterName);
        }
        return actorValue(actorUserId);
    }

    /**
     * Main-table in-progress (or terminal) stored value.
     *
     * @param miOuterName non-null when {@code currentNode} is an inner MI user task
     */
    static String mainInProgress(String processStatus, String miOuterName,
                                 String assigneeUserId, String candidateUserIds) {
        if (isTerminalStatus(processStatus)) {
            return "";
        }
        if (miOuterName != null && !miOuterName.isBlank()) {
            return stepValue(miOuterName);
        }
        return peopleValue(assigneeUserId, candidateUserIds);
    }

    static String peopleValue(String assigneeUserId, String candidateUserIds) {
        List<String> ids = peopleIds(assigneeUserId, candidateUserIds);
        if (ids.isEmpty()) {
            return "";
        }
        return OwnerFieldComponent.joinStoredUserValues(ids);
    }

    static List<String> peopleIds(String assigneeUserId, String candidateUserIds) {
        List<String> assigneeKeys = assigneeUserId == null || assigneeUserId.isBlank()
                ? List.of()
                : ProcessAssigneeSnapshot.parseDelimitedUserKeys(assigneeUserId);
        if (assigneeKeys.size() == 1) {
            return assigneeKeys;
        }
        return List.copyOf(ProcessAssigneeSnapshot.collectUserKeys(assigneeUserId, candidateUserIds));
    }

    /**
     * My Request / application-detail Current Assignee display. Same rule as main Case Handler.
     * Does not change To Do task-header Current Assignee.
     *
     * @param innerTaskToOuterName resolved inner-task → outer-box map. Empty map means
     *        this process has no inner MI tasks (show people). {@code null} means the map
     *        could not be loaded — do not treat that as "not MI" and do not show people.
     */
    public static String applicationCurrentAssigneeDisplay(
            String status, String currentNode, Map<String, String> innerTaskToOuterName,
            String peopleDisplay) {
        if (isTerminalStatus(status) || currentNode == null || currentNode.isBlank()) {
            return null;
        }
        if (innerTaskToOuterName == null) {
            return null;
        }
        if (innerTaskToOuterName.containsKey(currentNode)) {
            return innerTaskToOuterName.get(currentNode);
        }
        return peopleDisplay;
    }

    static String outerNameIfInner(String currentNode, Map<String, String> innerTaskToOuterName) {
        if (currentNode == null || currentNode.isBlank() || innerTaskToOuterName == null) {
            return null;
        }
        return innerTaskToOuterName.containsKey(currentNode) ? innerTaskToOuterName.get(currentNode) : null;
    }

    static boolean knownSource(String normalized) {
        return SOURCE_CREATOR.equals(normalized) || SOURCE_CASE_HANDLER.equals(normalized);
    }

    static Set<String> terminalStatuses() {
        return Set.of("COMPLETED", "WITHDRAWN", "REJECTED", "CANCELLED");
    }
}
