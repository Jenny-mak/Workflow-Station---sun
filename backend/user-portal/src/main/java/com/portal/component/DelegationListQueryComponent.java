package com.portal.component;

import com.portal.dto.DelegationListQueryRequest;
import com.portal.dto.PortalListGroup;
import com.portal.dto.PortalListPage;
import com.portal.entity.DelegationAudit;
import com.portal.entity.DelegationRule;
import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
import com.portal.util.DelegationAuditColumnSpec;
import com.portal.util.DelegationRuleColumnSpec;
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
import java.util.List;

/**
 * My Rules and Audit shared lists: visibility is SQL-owned (rules = current user as
 * delegator; audit = current user as either party). Filters, sort and grouping compile
 * through {@link ListFilterSql} so COUNT, page and group counts share one predicate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelegationListQueryComponent {

    static final String RULES_KEY = "delegation-rules";
    static final String AUDIT_KEY = "delegation-audit";

    private static final String RULES_FROM = " FROM up_delegation_rule r WHERE r.delegator_id = ?";
    private static final String AUDIT_FROM =
            " FROM up_delegation_audit a WHERE (a.delegator_id = ? OR a.delegate_id = ?)";

    private final JdbcTemplate jdbcTemplate;

    public PortalListPage<DelegationRule> queryRules(String userId, DelegationListQueryRequest request) {
        requireUser(userId);
        long started = System.nanoTime();
        ListFilterSql filterSql = DelegationRuleColumnSpec.sql();
        List<Object> params = new ArrayList<>();
        params.add(userId);
        StringBuilder where = new StringBuilder(RULES_FROM);
        where.append(filterSql.whereClause(request.filters(), params));

        long total = ListQuerySupport.requireCount(
                ListQuerySupport.query(jdbcTemplate, "SELECT COUNT(*)" + where, params,
                        rs -> rs.next() ? rs.getLong(1) : 0L),
                RULES_KEY);

        String groupExpression = blankToNull(request.groupBy()) == null
                ? null
                : filterSql.groupByExpression(request.groupBy());
        List<PortalListGroup> groups = groupExpression == null
                ? List.of()
                : ListQuerySupport.groupsOf(jdbcTemplate, groupExpression, where.toString(), params);
        requireGroupsWhenRowsExist(request.groupBy(), groups, total, RULES_KEY);

        List<DelegationRule> rows = loadRulesPage(filterSql, where.toString(), params, request, groupExpression);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        ListQuerySupport.logIfSlow(log, RULES_KEY, request.page(), request.size(), total, started);
        ListQuerySupport.logIfOverSla(log, RULES_KEY, request.page(), request.size(), total, elapsedMs, elapsedMs, 0L);
        return new PortalListPage<>(DelegationRuleColumnSpec.columns(), rows, groups,
                request.page(), request.size(), total);
    }

    public PortalListPage<DelegationAudit> queryAudit(String userId, DelegationListQueryRequest request) {
        requireUser(userId);
        long started = System.nanoTime();
        ListFilterSql filterSql = DelegationAuditColumnSpec.sql();
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.add(userId);
        StringBuilder where = new StringBuilder(AUDIT_FROM);
        where.append(filterSql.whereClause(request.filters(), params));

        long total = ListQuerySupport.requireCount(
                ListQuerySupport.query(jdbcTemplate, "SELECT COUNT(*)" + where, params,
                        rs -> rs.next() ? rs.getLong(1) : 0L),
                AUDIT_KEY);

        String groupExpression = blankToNull(request.groupBy()) == null
                ? null
                : filterSql.groupByExpression(request.groupBy());
        List<PortalListGroup> groups = groupExpression == null
                ? List.of()
                : ListQuerySupport.groupsOf(jdbcTemplate, groupExpression, where.toString(), params);
        requireGroupsWhenRowsExist(request.groupBy(), groups, total, AUDIT_KEY);

        List<DelegationAudit> rows = loadAuditPage(filterSql, where.toString(), params, request, groupExpression);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        ListQuerySupport.logIfSlow(log, AUDIT_KEY, request.page(), request.size(), total, started);
        ListQuerySupport.logIfOverSla(log, AUDIT_KEY, request.page(), request.size(), total, elapsedMs, elapsedMs, 0L);
        return new PortalListPage<>(DelegationAuditColumnSpec.columns(), rows, groups,
                request.page(), request.size(), total);
    }

    private List<DelegationRule> loadRulesPage(ListFilterSql filterSql, String where, List<Object> params,
                                               DelegationListQueryRequest request, String groupExpression) {
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(request.size());
        pageParams.add(request.page() * request.size());
        String orderBy = groupExpression == null
                ? filterSql.orderBy(request.sortField(), request.sortDirection())
                : filterSql.orderByGrouped(groupExpression, request.sortField(), request.sortDirection());
        String groupedSelect = groupExpression == null ? "" : ", " + groupExpression + " AS grouped_value";
        String sql = "SELECT r.id, r.delegator_id, r.delegate_id, r.delegation_type, r.start_time,"
                + " r.end_time, r.status, r.reason, r.created_at, r.updated_at" + groupedSelect
                + where + orderBy + " LIMIT ? OFFSET ?";
        return ListQuerySupport.query(jdbcTemplate, sql, pageParams, rs -> {
            List<DelegationRule> page = new ArrayList<>();
            while (rs.next()) {
                page.add(mapRule(rs));
            }
            return page;
        });
    }

    private List<DelegationAudit> loadAuditPage(ListFilterSql filterSql, String where, List<Object> params,
                                                DelegationListQueryRequest request, String groupExpression) {
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(request.size());
        pageParams.add(request.page() * request.size());
        String orderBy = groupExpression == null
                ? filterSql.orderBy(request.sortField(), request.sortDirection())
                : filterSql.orderByGrouped(groupExpression, request.sortField(), request.sortDirection());
        String groupedSelect = groupExpression == null ? "" : ", " + groupExpression + " AS grouped_value";
        String sql = "SELECT a.id, a.delegator_id, a.delegate_id, a.task_id, a.operation_type,"
                + " a.operation_result, a.operation_detail, a.ip_address, a.user_agent, a.created_at"
                + groupedSelect + where + orderBy + " LIMIT ? OFFSET ?";
        return ListQuerySupport.query(jdbcTemplate, sql, pageParams, rs -> {
            List<DelegationAudit> page = new ArrayList<>();
            while (rs.next()) {
                page.add(mapAudit(rs));
            }
            return page;
        });
    }

    private static DelegationRule mapRule(ResultSet rs) throws SQLException {
        return DelegationRule.builder()
                .id(rs.getLong("id"))
                .delegatorId(rs.getString("delegator_id"))
                .delegateId(rs.getString("delegate_id"))
                .delegationType(enumOrNull(rs.getString("delegation_type"), DelegationType.class))
                .startTime(toLocalDateTime(rs.getTimestamp("start_time")))
                .endTime(toLocalDateTime(rs.getTimestamp("end_time")))
                .status(enumOrNull(rs.getString("status"), DelegationStatus.class))
                .reason(rs.getString("reason"))
                .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
                .updatedAt(toLocalDateTime(rs.getTimestamp("updated_at")))
                .build();
    }

    private static DelegationAudit mapAudit(ResultSet rs) throws SQLException {
        return DelegationAudit.builder()
                .id(rs.getLong("id"))
                .delegatorId(rs.getString("delegator_id"))
                .delegateId(rs.getString("delegate_id"))
                .taskId(rs.getString("task_id"))
                .operationType(rs.getString("operation_type"))
                .operationResult(rs.getString("operation_result"))
                .operationDetail(rs.getString("operation_detail"))
                .ipAddress(rs.getString("ip_address"))
                .userAgent(rs.getString("user_agent"))
                .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
                .build();
    }

    private static <E extends Enum<E>> E enumOrNull(String raw, Class<E> type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Enum.valueOf(type, raw);
    }

    private static void requireGroupsWhenRowsExist(String groupBy, List<PortalListGroup> groups,
                                                   long total, String listKey) {
        if (groupBy != null && !groupBy.isBlank() && total > 0 && groups.isEmpty()) {
            throw new IllegalStateException(
                    "GROUP BY returned no groups for a non-empty " + listKey + " result");
        }
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required for delegation list query");
        }
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
