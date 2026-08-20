package com.portal.component;

import com.portal.entity.ProcessInstance;
import com.portal.service.ProcessAssigneeSnapshot;

import java.util.Objects;

/**
 * List-path rule for when a running My Requests row must hit the workflow engine to backfill
 * {@code current_assignee}. Stored identity is enough for the cell (display names are resolved
 * in batch from {@code sys_users}); re-fetching every page/filter made the list O(page size) HTTP.
 */
public final class ListAssigneeEnrichmentPolicy {

    private ListAssigneeEnrichmentPolicy() {
    }

    public static boolean needsEngineBackfill(ProcessInstance instance) {
        return isBlank(instance.getCurrentAssignee()) && isBlank(instance.getCandidateUsers());
    }

    /**
     * @return true when local columns changed and the caller should persist
     */
    public static boolean applySnapshot(ProcessInstance instance, ProcessAssigneeSnapshot snapshot) {
        String assignee = snapshot.getAssigneeUserId();
        String candidates = snapshot.getCandidateUserIds();
        boolean changed = !Objects.equals(instance.getCurrentAssignee(), assignee)
                || !Objects.equals(instance.getCandidateUsers(), candidates);
        if (!changed) {
            return false;
        }
        instance.setCurrentAssignee(assignee);
        instance.setCandidateUsers(candidates);
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
