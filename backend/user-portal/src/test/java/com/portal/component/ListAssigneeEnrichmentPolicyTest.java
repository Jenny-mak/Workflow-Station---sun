package com.portal.component;

import com.portal.entity.ProcessInstance;
import com.portal.service.ProcessAssigneeSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ListAssigneeEnrichmentPolicyTest {

    @Test
    void skipsEngineWhenAssigneeAlreadyStored() {
        assertThat(ListAssigneeEnrichmentPolicy.needsEngineBackfill(running("user-1", null))).isFalse();
    }

    @Test
    void skipsEngineWhenOnlyCandidatesStored() {
        assertThat(ListAssigneeEnrichmentPolicy.needsEngineBackfill(running(null, "user-2,user-3"))).isFalse();
    }

    @Test
    void hitsEngineWhenAssigneeAndCandidatesAreEmpty() {
        assertThat(ListAssigneeEnrichmentPolicy.needsEngineBackfill(running(null, "  "))).isTrue();
    }

    @Test
    void applySnapshotSkipsPersistWhenColumnsAlreadyMatch() {
        ProcessInstance instance = running("user-1", "c1");
        ProcessAssigneeSnapshot snapshot = new ProcessAssigneeSnapshot("user-1", "c1");

        assertThat(ListAssigneeEnrichmentPolicy.applySnapshot(instance, snapshot)).isFalse();
        assertThat(instance.getCurrentAssignee()).isEqualTo("user-1");
    }

    @Test
    void applySnapshotWritesWhenEngineDiffers() {
        ProcessInstance instance = running(null, null);
        ProcessAssigneeSnapshot snapshot = new ProcessAssigneeSnapshot("user-1", "c1,c2");

        assertThat(ListAssigneeEnrichmentPolicy.applySnapshot(instance, snapshot)).isTrue();
        assertThat(instance.getCurrentAssignee()).isEqualTo("user-1");
        assertThat(instance.getCandidateUsers()).isEqualTo("c1,c2");
    }

    private static ProcessInstance running(String assignee, String candidates) {
        ProcessInstance instance = new ProcessInstance();
        instance.setStatus("RUNNING");
        instance.setCurrentAssignee(assignee);
        instance.setCandidateUsers(candidates);
        return instance;
    }
}
