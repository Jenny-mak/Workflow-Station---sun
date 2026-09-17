package com.portal.component;

import com.portal.dto.TaskInfo;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stateless mapping helpers between workflow-engine REST payloads and portal {@link TaskInfo}.
 * Extracted from {@link TaskQueryComponent}; pure functions only (no Spring dependencies).
 */
@Slf4j
final class EngineTaskMapper {

    private EngineTaskMapper() {
    }

    /**
     * Copies Flowable task variables then overlays portal {@code ProcessInstance} snapshot variables (richer payloads
     * such as {@code __subTables__}). Keeps Flowable-supplied execution-scoped {@code _currentItem}/{@code currentItem}
     * when present: the portal snapshot is single process-wide JSON and would otherwise overwrite MI iteration context.
     */
    /**
     * Overwrites {@code _currentItem} with the value scoped to THIS task's own MI execution.
     *
     * <p>{@code _currentItem} identifies which collection row an MI sub-task owns. It is
     * execution-scoped, but a copy also exists at process-instance level, and a task whose own
     * execution carries no copy inherits that instance-wide one — which belongs to whichever
     * participant wrote it last. Measured on FU fu-20260422: task 30fb0670 owns participant
     * {@code Test-000001}, yet task detail reported {@code Test-000002}; the form then submitted the
     * wrong participant and the backend's MI guard rejected every Save.
     *
     * <p>Best-effort: any failure leaves {@code target} untouched (previous behaviour).
     *
     * @param engineTaskBody the workflow engine's task payload, or empty when unavailable
     */
    static void applyTaskScopedMiCurrentItem(Map<String, Object> engineTaskBody, Map<String, Object> target) {
        if (engineTaskBody == null || engineTaskBody.isEmpty() || target == null) {
            return;
        }
        Object dataRaw = engineTaskBody.containsKey("data") ? engineTaskBody.get("data") : engineTaskBody;
        if (!(dataRaw instanceof Map<?, ?> data)) {
            return;
        }
        Object varsRaw = data.get("variables");
        if (!(varsRaw instanceof Map<?, ?> vars)) {
            return;
        }
        Object currentItem = vars.get("_currentItem");
        if (currentItem == null) {
            currentItem = vars.get("currentItem");
        }
        if (currentItem instanceof Map) {
            target.put("_currentItem", currentItem);
        }
    }

    static void mergePortalProcessVariablesPreferringFlowableMiElementItem(
            Map<String, Object> mergedOut,
            Map<String, Object> flowableVariables,
            Map<String, Object> portalProcessVariables) {
        mergedOut.clear();
        if (flowableVariables != null) {
            mergedOut.putAll(flowableVariables);
        }
        boolean hadUnderscore = mergedOut.containsKey("_currentItem");
        Object underscoreVal = mergedOut.get("_currentItem");
        boolean hadBare = mergedOut.containsKey("currentItem");
        Object bareVal = mergedOut.get("currentItem");
        if (portalProcessVariables != null) {
            mergedOut.putAll(portalProcessVariables);
        }
        if (hadUnderscore) {
            mergedOut.put("_currentItem", underscoreVal);
        }
        if (hadBare) {
            mergedOut.put("currentItem", bareVal);
        }
    }

    /**
     * When the engine REST response uses Map deserialization, user IDs may come as JSON numbers
     * (Long) and cannot be cast directly to (String); doing so causes a runtime exception or
     * field loss, making the portal see an empty assignee and fail permission checks.
     */
    static String engineStringField(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s.isBlank() ? null : s.trim();
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isFinite(d) && Math.floor(d) == d) {
                return String.valueOf(n.longValue());
            }
            return n.toString();
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        String t = value.toString().trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Convert a Map to TaskInfo.
     */
    static TaskInfo convertMapToTaskInfo(Map<String, Object> taskMap) {
        // Prefer processDefinitionKey; extract from processDefinitionId if absent
        String processDefinitionKey = (String) taskMap.get("processDefinitionKey");
        if (processDefinitionKey == null || processDefinitionKey.isEmpty()) {
            String processDefinitionId = engineStringField(taskMap.get("processDefinitionId"));
            processDefinitionKey = extractProcessDefinitionKey(processDefinitionId);
        }

        // Get process definition name; fall back to processDefinitionKey if not returned
        String processDefinitionName = (String) taskMap.get("processDefinitionName");
        if (processDefinitionName == null || processDefinitionName.isEmpty()) {
            processDefinitionName = processDefinitionKey;
        }

        // Get initiator info
        String initiatorId = engineStringField(taskMap.get("initiatorId"));
        String initiatorName = engineStringField(taskMap.get("initiatorName"));

        // Get current assignee
        String currentAssignee = engineStringField(taskMap.get("currentAssignee"));
        // Get current assignee name; fall back to currentAssignee if not available
        String currentAssigneeName = engineStringField(taskMap.get("currentAssigneeName"));
        if (currentAssigneeName == null || currentAssigneeName.isEmpty()) {
            currentAssigneeName = currentAssignee;
        }

        List<String> candidateUserIds = parseStringIdList(taskMap.get("candidateUserIds"));
        List<String> candidateGroupIds = parseStringIdList(taskMap.get("candidateGroupIds"));
        String assignmentTarget = engineStringField(taskMap.get("assignmentTarget"));

        // Determine assignment type: prefer engine value, otherwise infer
        String assignmentType = null;
        Object atObj = taskMap.get("assignmentType");
        if (atObj instanceof Enum<?> en) {
            assignmentType = en.name();
        } else if (atObj != null) {
            assignmentType = atObj.toString().trim();
        }
        if (assignmentType == null || assignmentType.isEmpty()) {
            if (currentAssignee != null && !currentAssignee.isEmpty()) {
                assignmentType = "USER";
            } else if (candidateUserIds != null && !candidateUserIds.isEmpty()) {
                assignmentType = "CANDIDATE_USERS";
            } else {
                assignmentType = "VIRTUAL_GROUP";
            }
        }

        // Get process variables
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) taskMap.get("variables");

        return TaskInfo.builder()
                .taskId(engineStringField(taskMap.get("taskId")))
                .taskName((String) taskMap.get("taskName"))
                .currentStepName(engineStringField(taskMap.get("currentStepName")))
                .description((String) taskMap.get("taskDescription"))
                .processInstanceId(engineStringField(taskMap.get("processInstanceId")))
                .processDefinitionKey(processDefinitionKey)
                .processDefinitionName(processDefinitionName)
                .assignmentType(assignmentType)
                .bpmnAssigneeType(engineStringField(taskMap.get("bpmnAssigneeType")))
                .bpmnBusinessUnitId(engineStringField(taskMap.get("bpmnBusinessUnitId")))
                .bpmnRoleIds(parseStringIdList(taskMap.get("bpmnRoleIds")))
                .miAssigneeMode(engineStringField(taskMap.get("miAssigneeMode")))
                .miRoleCode(engineStringField(taskMap.get("miRoleCode")))
                .miBusinessUnitCode(engineStringField(taskMap.get("miBusinessUnitCode")))
                .assignmentTarget(assignmentTarget)
                .assignee(currentAssignee)
                .assigneeName(currentAssigneeName)
                .initiatorId(initiatorId)
                .initiatorName(initiatorName)
                .priority(taskMap.get("priority") != null ? taskMap.get("priority").toString() : "NORMAL")
                .status((String) taskMap.get("status"))
                .createTime(parseDateTime(taskMap.get("createdTime")))
                .completedTime(parseDateTime(taskMap.get("completedTime")))
                .dueDate(parseDateTime(taskMap.get("dueDate")))
                .isOverdue(taskMap.get("isOverdue") != null ? (Boolean) taskMap.get("isOverdue") : false)
                .formKey((String) taskMap.get("formKey"))
                .taskDefinitionKey((String) taskMap.get("taskDefinitionKey"))
                .variables(variables)
                .functionUnitCode(functionUnitCodeFromEngineMap(taskMap, variables))
                .candidateUserIds(candidateUserIds)
                .candidateGroupIds(candidateGroupIds)
                .delegatorId(engineStringField(taskMap.get("delegatedBy")))
                .delegatedTo(engineStringField(taskMap.get("delegatedTo")))
                .delegatedTargetType(engineStringField(taskMap.get("delegatedTargetType")))
                .delegatedBuCode(engineStringField(taskMap.get("delegatedBuCode")))
                .delegatedRoleCode(engineStringField(taskMap.get("delegatedRoleCode")))
                .delegated(booleanField(taskMap.get("isDelegated")))
                .build();
    }

    static String functionUnitCodeFromEngineMap(Map<String, Object> taskMap, Map<String, Object> variables) {
        String direct = engineStringField(taskMap != null ? taskMap.get("functionUnitCode") : null);
        if (direct != null) {
            return direct;
        }
        return engineStringField(variables != null ? variables.get("functionUnitCode") : null);
    }

    static Boolean booleanField(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if (s.isBlank()) {
                return null;
            }
            return Boolean.parseBoolean(s.trim());
        }
        return null;
    }

    /**
     * Parse the candidate user/group ID lists returned by the engine (JSON array or comma-separated string).
     */
    static List<String> parseStringIdList(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().trim());
                }
            }
            return out.isEmpty() ? null : out;
        }
        if (raw instanceof String s && !s.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }

    /**
     * Extract processDefinitionKey from processDefinitionId.
     * Format: key:version:uuid (e.g. Process_PurchaseRequest:2:b550b1fe-f0b0-11f0-b82f-00ff197375e0)
     */
    static String extractProcessDefinitionKey(String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isEmpty()) {
            return null;
        }
        int colonIndex = processDefinitionId.indexOf(':');
        if (colonIndex > 0) {
            return processDefinitionId.substring(0, colonIndex);
        }
        return processDefinitionId;
    }

    /**
     * Parse a date-time value.
     */
    static LocalDateTime parseDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof String) {
            try {
                return LocalDateTime.parse((String) value);
            } catch (Exception e) {
                log.warn("Failed to parse datetime: {}", value);
                return null;
            }
        }
        return null;
    }

    static long extractEngineTotalCount(Map<String, Object> responseBody) {
        if (responseBody == null) {
            return 0L;
        }
        Object tc = responseBody.get("totalCount");
        if (tc instanceof Number n) {
            return Math.max(n.longValue(), 0L);
        }
        return 0L;
    }

    static void clearTaskVariablesForList(List<TaskInfo> tasks) {
        if (tasks == null) {
            return;
        }
        for (TaskInfo t : tasks) {
            if (t != null) {
                t.setVariables(null);
            }
        }
    }
}
