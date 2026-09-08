package com.portal.enums;

/**
 * Change-history rows that are user assignment actions, not form fields.
 */
public final class AssignmentChangeTypes {

    public static final String FIELD_NAME = "claimed_by";

    private AssignmentChangeTypes() {
    }

    public static boolean isAssignmentAction(ChangeType changeType) {
        return changeType == ChangeType.CLAIM
                || changeType == ChangeType.UNCLAIM
                || changeType == ChangeType.FORCE_UNCLAIM
                || changeType == ChangeType.REASSIGN;
    }

    public static boolean isAssignmentActionName(String changeType) {
        if (changeType == null || changeType.isBlank()) {
            return false;
        }
        try {
            return isAssignmentAction(ChangeType.valueOf(changeType.trim()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
