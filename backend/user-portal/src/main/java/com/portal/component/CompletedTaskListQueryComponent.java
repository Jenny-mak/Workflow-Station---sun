package com.portal.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.dto.CompletedTaskQueryRequest;
import com.portal.dto.ListColumnFilter;
import com.portal.dto.PortalListGroup;
import com.portal.dto.PortalListPage;
import com.portal.dto.TaskInfo;
import com.portal.util.CompletedTaskColumnSpec;
import com.portal.util.ListFilterSql;
import com.portal.util.ListQuerySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Completed Tasks list: one historic user-task is one row. Visibility is exact
 * ({@code ASSIGNEE_ = current user} and {@code END_TIME_ IS NOT NULL}); filters, sort and
 * grouping compile through {@link ListFilterSql} so {@code COUNT(*)}, the page and group
 * counts share one predicate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompletedTaskListQueryComponent {

    static final String LIST_KEY = "completed-tasks";
    private static final String FROM_WHERE = " FROM ACT_HI_TASKINST ht"
            + " LEFT JOIN up_process_instance pi ON pi.id = ht.PROC_INST_ID_"
            + " WHERE ht.ASSIGNEE_ = ? AND ht.END_TIME_ IS NOT NULL";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RequestIdEnricher requestIdEnricher;

    public PortalListPage<TaskInfo> query(String userId, CompletedTaskQueryRequest request) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required for completed-tasks");
        }
        long started = System.nanoTime();
        ListFilterSql filterSql = CompletedTaskColumnSpec.sql();
        List<ListColumnFilter> filters = withLegacyFilters(request);
        List<Object> params = new ArrayList<>();
        params.add(userId);
        StringBuilder where = new StringBuilder(FROM_WHERE);
        where.append(filterSql.whereClause(filters, params));

        long total = ListQuerySupport.requireCount(
                ListQuerySupport.query(jdbcTemplate, "SELECT COUNT(*)" + where, params,
                        rs -> rs.next() ? rs.getLong(1) : 0L),
                LIST_KEY);

        String groupExpression = blankToNull(request.groupBy()) == null
                ? null
                : filterSql.groupByExpression(request.groupBy());
        List<PortalListGroup> groups = groupExpression == null
                ? List.of()
                : ListQuerySupport.groupsOf(jdbcTemplate, groupExpression, where.toString(), params);
        requireGroupsWhenRowsExist(request.groupBy(), groups, total);

        List<TaskInfo> rows = loadPage(filterSql, where.toString(), params, request, groupExpression);
        tagMultiInstanceTasks(rows);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        ListQuerySupport.logIfSlow(log, LIST_KEY, request.page(), request.size(), total, started);
        ListQuerySupport.logIfOverSla(log, LIST_KEY, request.page(), request.size(), total, elapsedMs, elapsedMs, 0L);
        return new PortalListPage<>(CompletedTaskColumnSpec.columns(), rows, groups,
                request.page(), request.size(), total);
    }

    private List<TaskInfo> loadPage(ListFilterSql filterSql, String where, List<Object> params,
                                    CompletedTaskQueryRequest request, String groupExpression) {
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(request.size());
        pageParams.add(request.page() * request.size());
        String orderBy = groupExpression == null
                ? filterSql.orderBy(request.sortField(), request.sortDirection())
                : filterSql.orderByGrouped(groupExpression, request.sortField(), request.sortDirection());
        String groupedSelect = groupExpression == null ? "" : ", " + groupExpression + " AS grouped_value";
        String sql = "SELECT ht.ID_ AS task_id, ht.NAME_ AS task_name, ht.PROC_INST_ID_ AS process_instance_id,"
                + " ht.PROC_DEF_ID_ AS process_definition_id, ht.TASK_DEF_KEY_ AS task_definition_key,"
                + " ht.ASSIGNEE_ AS assignee, ht.START_TIME_ AS start_time, ht.END_TIME_ AS end_time,"
                + " ht.DURATION_ AS duration, " + CompletedTaskColumnSpec.ACTION_SQL + " AS action,"
                + " pi.process_definition_key, pi.process_definition_name, pi.function_unit_code,"
                + " (pi.variables - '__subTables__')::text AS variables" + groupedSelect
                + where + orderBy + " LIMIT ? OFFSET ?";
        String groupBy = request.groupBy();
        List<TaskInfo> tasks = ListQuerySupport.query(jdbcTemplate, sql, pageParams, rs -> {
            List<TaskInfo> page = new ArrayList<>();
            List<String> fuCodes = new ArrayList<>();
            List<Map<String, Object>> variables = new ArrayList<>();
            while (rs.next()) {
                page.add(mapRow(rs, groupBy));
                fuCodes.add(rs.getString("function_unit_code"));
                variables.add(readVariables(rs.getString("variables")));
            }
            fillRequestIds(page, fuCodes, variables);
            return page;
        });
        return tasks;
    }

    private TaskInfo mapRow(ResultSet rs, String groupBy) throws SQLException {
        String taskName = rs.getString("task_name");
        String processDefKey = rs.getString("process_definition_key");
        if (processDefKey == null || processDefKey.isBlank()) {
            processDefKey = processKeyFromDefinitionId(rs.getString("process_definition_id"));
        }
        String processName = rs.getString("process_definition_name");
        if (processName == null || processName.isBlank()) {
            processName = processDefKey;
        }
        TaskInfo task = TaskInfo.builder()
                .taskId(rs.getString("task_id"))
                .taskName(taskName)
                .currentStepName(taskName)
                .processInstanceId(rs.getString("process_instance_id"))
                .processDefinitionKey(processDefKey)
                .processDefinitionName(processName)
                .taskDefinitionKey(rs.getString("task_definition_key"))
                .assignee(rs.getString("assignee"))
                .status("COMPLETED")
                .createTime(toLocalDateTime(rs.getTimestamp("start_time")))
                .completedTime(toLocalDateTime(rs.getTimestamp("end_time")))
                .durationInMillis(longOrNull(rs, "duration"))
                .action(rs.getString("action"))
                .build();
        if (groupBy != null && !groupBy.isBlank()) {
            writeGroupedValue(task, groupBy, rs.getString("grouped_value"));
        }
        return task;
    }

    private void fillRequestIds(List<TaskInfo> tasks, List<String> fuCodes,
                                List<Map<String, Object>> variables) {
        RequestIdEnricher.SpecCache specs = requestIdEnricher.resolveSpecs(new LinkedHashSet<>(fuCodes));
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).setRequestId(requestIdEnricher.buildRequestId(specs, fuCodes.get(i), variables.get(i)));
        }
    }

    private void tagMultiInstanceTasks(List<TaskInfo> tasks) {
        List<String> ids = tasks.stream().map(TaskInfo::getTaskId).filter(id -> id != null).toList();
        if (ids.isEmpty()) {
            return;
        }
        Set<String> miIds = findMultiInstanceTaskIds(ids);
        for (TaskInfo task : tasks) {
            if (miIds.contains(task.getTaskId())) {
                task.setMultiInstanceSubTask(true);
            }
        }
    }

    private Set<String> findMultiInstanceTaskIds(List<String> taskIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(taskIds.size(), "?"));
        String sql = "SELECT task_id FROM wf_extended_task_info WHERE task_id IN (" + placeholders + ")"
                + " AND is_deleted = false AND extended_properties LIKE '%\"multiInstance\":true%'";
        return new HashSet<>(jdbcTemplate.query(sql, (rs, i) -> rs.getString("task_id"), taskIds.toArray()));
    }

    static List<ListColumnFilter> withLegacyFilters(CompletedTaskQueryRequest request) {
        List<ListColumnFilter> filters = new ArrayList<>(request.filters());
        if (request.keyword() != null && !request.keyword().isBlank()) {
            filters.add(new ListColumnFilter("taskName", "contains", request.keyword(), null));
        }
        String start = dayPrefix(request.startTime());
        String end = dayPrefix(request.endTime());
        if (start != null && end != null) {
            filters.add(new ListColumnFilter("completedTime", "between", start, end));
        } else if (start != null) {
            filters.add(new ListColumnFilter("completedTime", "after", start, null));
        } else if (end != null) {
            filters.add(new ListColumnFilter("completedTime", "before", end, null));
        }
        return List.copyOf(filters);
    }

    private Map<String, Object> readVariables(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Process variables are not readable JSON", e);
        }
    }

    private static void writeGroupedValue(TaskInfo task, String groupBy, String value) {
        String label = value == null ? "" : value;
        if ("action".equals(groupBy)) {
            task.setAction(label);
            return;
        }
        throw new IllegalStateException("grouped field was not selected: " + groupBy);
    }

    private static void requireGroupsWhenRowsExist(String groupBy, List<PortalListGroup> groups, long total) {
        if (groupBy != null && !groupBy.isBlank() && total > 0 && groups.isEmpty()) {
            throw new IllegalStateException("GROUP BY returned no groups for a non-empty completed-tasks result");
        }
    }

    private static String processKeyFromDefinitionId(String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            return null;
        }
        int colon = processDefinitionId.indexOf(':');
        return colon > 0 ? processDefinitionId.substring(0, colon) : processDefinitionId;
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String dayPrefix(String iso) {
        if (iso == null || iso.isBlank() || iso.length() < 10) {
            return null;
        }
        return iso.substring(0, 10);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
