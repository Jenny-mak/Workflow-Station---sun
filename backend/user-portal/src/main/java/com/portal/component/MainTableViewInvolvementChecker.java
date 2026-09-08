package com.portal.component;

import com.portal.entity.ProcessInstance;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Determines whether a user is involved in a process instance for view data scoping:
 * initiator, task assignee (historic), or MI sub-table participant fields.
 */
@Component
@RequiredArgsConstructor
public class MainTableViewInvolvementChecker {

    /**
     * Column names a participant reference may appear under.
     *
     * <p><b>Known gap: this is a hardcoded list where the answer is configuration.</b> A Function
     * Unit names its participant column in Sub-Task Config ({@code assigneeField}); every FU in dev
     * happens to use {@code assignee_user_id}, which is why the list currently works. An FU that
     * names it anything else — {@code handler_id}, {@code owner} — matches nothing here.
     *
     * <p><b>Why it has not simply been replaced.</b> This method GRANTS view access, so the failure
     * is fail-closed: an unlisted column means a genuine participant is denied their own rows, never
     * that a stranger is shown someone else's. It is a visibility bug, not a leak. Fixing it means
     * resolving {@code assigneeField} per process definition on this path (the BPMN is not in scope
     * here today) and re-verifying the SQL pre-filter invariant documented on
     * {@link #isMiParticipant}: that pre-filter is a substring match over the {@code __subTables__}
     * JSON and must stay a SUPERSET of what this method accepts, so widening the accepted key set
     * without re-checking it could start dropping rows the filter never passed through.
     *
     * <p>Until then the list stays and this comment is the record: do not read it as "these are the
     * participant columns", read it as "these are the ones we happen to recognise".
     */
    private static final Set<String> MI_PARTICIPANT_KEY_HINTS = Set.of(
            "assignee", "assignee_user_id", "participant_id", "participant", "id_idw");

    private final JdbcTemplate jdbcTemplate;

    public boolean isUserInvolved(String userId, ProcessInstance processInstance) {
        if (userId == null || userId.isBlank() || processInstance == null) {
            return false;
        }
        if (userId.equals(processInstance.getStartUserId())) {
            return true;
        }
        if (hasHistoricAssigneeTask(userId, processInstance.getId())) {
            return true;
        }
        return isMiParticipant(userId, processInstance.getVariables());
    }

    /**
     * The MI-participant branch on its own, for callers that have already settled the initiator
     * and historic-assignee branches in SQL and only need this one rechecked. Exposed rather than
     * reimplemented so pushed-down row scoping and per-instance checking cannot drift apart.
     *
     * <p>Note the invariant this guarantees to a SQL pre-filter: a true answer requires some
     * participant-hint key whose value equals {@code userId} exactly, so {@code userId} is
     * necessarily present in the JSON text of {@code __subTables__}. A substring pre-filter over
     * that text therefore returns a superset of what this method accepts — never a subset.
     */
    public boolean isMiParticipant(String userId, Map<String, Object> variables) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return isMiParticipantInVariables(userId, variables);
    }

    private boolean hasHistoricAssigneeTask(String userId, String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return false;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*) FROM ACT_HI_TASKINST
                            WHERE PROC_INST_ID_ = ? AND ASSIGNEE_ = ?
                            """,
                    Integer.class,
                    processInstanceId,
                    userId);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isMiParticipantInVariables(String userId, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return false;
        }
        Object subTablesObj = variables.get("__subTables__");
        if (!(subTablesObj instanceof Map<?, ?> subTables)) {
            return false;
        }
        for (Object sliceObj : subTables.values()) {
            if (!(sliceObj instanceof Collection<?> slice)) {
                continue;
            }
            for (Object rowObj : slice) {
                if (rowObj instanceof Map<?, ?> rowMap && rowReferencesUser(userId, (Map<String, Object>) rowMap)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean rowReferencesUser(String userId, Map<String, Object> row) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (MI_PARTICIPANT_KEY_HINTS.stream().anyMatch(lowerKey::contains)
                    && valueMatchesUser(userId, entry.getValue())) {
                return true;
            }
            if (entry.getValue() instanceof Map<?, ?> nested
                    && rowReferencesUser(userId, (Map<String, Object>) nested)) {
                return true;
            }
            if (entry.getValue() instanceof Collection<?> nestedList) {
                for (Object item : nestedList) {
                    if (item instanceof Map<?, ?> nestedMap
                            && rowReferencesUser(userId, (Map<String, Object>) nestedMap)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean valueMatchesUser(String userId, Object value) {
        if (value == null) {
            return false;
        }
        return Objects.equals(userId, String.valueOf(value).trim());
    }
}
