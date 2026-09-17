package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.TaskInfo;
import com.portal.repository.DelegationRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hold semantics for the BU Role claim pool: a request stays editable for exactly one member of the
 * role at a time, while the rest of the role keeps read access so they can see who took it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaskPermissionEvaluator BU Role claim pool")
class TaskPermissionEvaluatorClaimPoolTest {

    private static final String ALICE = "user-alice";
    private static final String BOB = "user-bob";
    private static final String OUTSIDER = "user-carol";

    @Mock
    private DelegationRuleRepository delegationRuleRepository;
    @Mock
    private WorkflowEngineClient workflowEngineClient;
    @Mock
    private WorkspaceTaskFilterComponent workspaceTaskFilterComponent;

    private TaskPermissionEvaluator evaluator() {
        return new TaskPermissionEvaluator(
                new DelegationRuleMatcher(delegationRuleRepository, workspaceTaskFilterComponent),
                workflowEngineClient, workspaceTaskFilterComponent);
    }

    @Test
    void freeRequestIsClaimableByAnyRoleMemberButProcessableByNobody() {
        TaskInfo task = poolTask(null);
        TaskPermissionEvaluator evaluator = evaluator();

        assertThat(evaluator.canClaimTask(task, ALICE, null)).isTrue();
        assertThat(evaluator.canClaimTask(task, BOB, null)).isTrue();
        assertThat(evaluator.canClaimTask(task, OUTSIDER, null)).isFalse();

        // Nobody may submit before holding it — this is what stops two members overwriting each other.
        assertThat(evaluator.canProcessTask(task, ALICE, null)).isFalse();
        assertThat(evaluator.canProcessTask(task, BOB, null)).isFalse();
    }

    @Test
    void heldRequestBelongsToItsHolderOnly() {
        TaskInfo task = poolTask(ALICE);
        TaskPermissionEvaluator evaluator = evaluator();

        assertThat(evaluator.canProcessTask(task, ALICE, null)).isTrue();
        assertThat(evaluator.canProcessTask(task, BOB, null)).isFalse();

        // A second claim would hand two members the same editable form.
        assertThat(evaluator.canClaimTask(task, BOB, null)).isFalse();
        assertThat(evaluator.canClaimTask(task, ALICE, null)).isFalse();
    }

    @Test
    void roleMembersKeepReadAccessToARequestHeldByAColleague() {
        TaskInfo task = poolTask(ALICE);
        TaskPermissionEvaluator evaluator = evaluator();

        assertThat(evaluator.canViewTaskForm(task, BOB, null)).isTrue();
        assertThat(evaluator.canViewTaskForm(task, OUTSIDER, null)).isFalse();
    }

    @Test
    void annotateMarksClaimableForFreeRequestsOnly() {
        TaskPermissionEvaluator evaluator = evaluator();

        TaskInfo free = poolTask(null);
        evaluator.annotateClaimState(free, BOB, null);
        assertThat(free.isClaimPoolTask()).isTrue();
        assertThat(free.isClaimable()).isTrue();
        assertThat(free.isClaimedByCurrentUser()).isFalse();

        TaskInfo mine = poolTask(BOB);
        evaluator.annotateClaimState(mine, BOB, null);
        assertThat(mine.isClaimedByCurrentUser()).isTrue();
        assertThat(mine.isClaimable()).isFalse();

        TaskInfo theirs = poolTask(ALICE);
        evaluator.annotateClaimState(theirs, BOB, null);
        assertThat(theirs.isClaimPoolTask()).isTrue();
        assertThat(theirs.isClaimedByCurrentUser()).isFalse();
        assertThat(theirs.isClaimable()).isFalse();
    }

    @Test
    void annotateLeavesNonPoolTasksUnflagged() {
        TaskInfo direct = TaskInfo.builder()
                .taskId("t-direct")
                .bpmnAssigneeType("INITIATOR")
                .assignmentType("USER")
                .assignee(ALICE)
                .build();

        evaluator().annotateClaimState(direct, ALICE, null);

        assertThat(direct.isClaimPoolTask()).isFalse();
        assertThat(direct.isClaimable()).isFalse();
        assertThat(direct.isClaimedByCurrentUser()).isFalse();
    }

    @Test
    void claimMatchesUsernameCandidatesNotJustUserIds() {
        TaskInfo task = TaskInfo.builder()
                .taskId("t-username")
                .bpmnAssigneeType("BU_ROLE")
                .assignmentType("CANDIDATE_USERS")
                .candidateUserIds(List.of("alice", "bob"))
                .build();
        TaskPermissionEvaluator evaluator = evaluator();

        assertThat(evaluator.canClaimTask(task, ALICE, "alice")).isTrue();

        evaluator.annotateClaimState(task, ALICE, "alice");
        assertThat(task.isClaimable()).isTrue();
    }

    /**
     * @param assignee holder of the request; {@code null} means still free for the whole role
     */
    private static TaskInfo poolTask(String assignee) {
        return TaskInfo.builder()
                .taskId("t-pool")
                .bpmnAssigneeType("BU_ROLE")
                .assignmentType(assignee == null ? "CANDIDATE_USERS" : "USER")
                .assignee(assignee)
                .candidateUserIds(List.of(ALICE, BOB))
                .build();
    }
}
