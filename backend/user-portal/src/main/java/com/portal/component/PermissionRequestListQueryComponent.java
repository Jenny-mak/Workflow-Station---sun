package com.portal.component;

import com.portal.dto.PermissionListQueryRequest;
import com.portal.dto.PermissionRequestListItem;
import com.portal.dto.PortalListGroup;
import com.portal.dto.PortalListPage;
import com.portal.enums.PermissionRequestType;
import com.portal.util.ListFilterSql;
import com.portal.util.ListQuerySupport;
import com.portal.util.PermissionRequestColumnSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Permission my-requests and approvals shared lists. Visibility predicates match the existing
 * GET endpoints; filters/sort/group compile through {@link ListFilterSql}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionRequestListQueryComponent {

    static final String LIST_KEY = "permission-requests";

    private static final List<String> BU_APPROVER_TYPES = List.of(
            PermissionRequestType.BUSINESS_UNIT_JOIN.name(),
            PermissionRequestType.BUSINESS_UNIT_ROLE_REMOVAL.name(),
            PermissionRequestType.BUSINESS_UNIT_EXIT.name());

    private final JdbcTemplate jdbcTemplate;
    private final PermissionRequestEnrichmentComponent enrichmentComponent;
    private final VirtualGroupAccessComponent virtualGroupAccessComponent;

    public PortalListPage<PermissionRequestListItem> query(String userId, PermissionListQueryRequest request) {
        if (userId == null || userId.isBlank()) {
            throw new InsufficientAuthenticationException("User identity required");
        }
        long started = System.nanoTime();
        ListFilterSql filterSql = PermissionRequestColumnSpec.sql();
        List<Object> params = new ArrayList<>();
        StringBuilder fromWhere = new StringBuilder(" FROM up_permission_request p");
        appendScopeWhere(fromWhere, params, userId, request.scopeEnum());
        fromWhere.append(filterSql.whereClause(request.filters(), params));

        long total = ListQuerySupport.requireCount(
                ListQuerySupport.query(jdbcTemplate, "SELECT COUNT(*)" + fromWhere, params,
                        rs -> rs.next() ? rs.getLong(1) : 0L),
                LIST_KEY);

        String groupExpression = blankToNull(request.groupBy()) == null
                ? null
                : filterSql.groupByExpression(request.groupBy());
        List<PortalListGroup> groups = groupExpression == null
                ? List.of()
                : ListQuerySupport.groupsOf(jdbcTemplate, groupExpression, fromWhere.toString(), params);
        if (groupExpression != null && total > 0 && groups.isEmpty()) {
            throw new IllegalStateException("GROUP BY returned no groups for a non-empty permission-requests result");
        }

        List<PermissionRequestListItem> rows = loadPage(filterSql, fromWhere.toString(), params, request, groupExpression);
        enrichmentComponent.enrichListItemUsernames(rows);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        ListQuerySupport.logIfSlow(log, LIST_KEY, request.page(), request.size(), total, started);
        ListQuerySupport.logIfOverSla(log, LIST_KEY, request.page(), request.size(), total, elapsedMs, elapsedMs, 0L);
        return new PortalListPage<>(PermissionRequestColumnSpec.columns(), rows, groups,
                request.page(), request.size(), total);
    }

    private void appendScopeWhere(StringBuilder where, List<Object> params, String userId,
                                  PermissionListQueryRequest.Scope scope) {
        switch (scope) {
            case MY_PENDING -> {
                where.append(" WHERE (p.applicant_id = ? OR p.submitted_by_user_id = ?)");
                where.append(" AND p.request_type <> 'VIRTUAL_GROUP_JOIN' AND p.status = 'PENDING'");
                params.add(userId);
                params.add(userId);
            }
            case MY_COMPLETED -> {
                where.append(" WHERE (p.applicant_id = ? OR p.submitted_by_user_id = ?)");
                where.append(" AND p.request_type <> 'VIRTUAL_GROUP_JOIN' AND p.status <> 'PENDING'");
                params.add(userId);
                params.add(userId);
            }
            case APPROVALS_PENDING -> appendApprovalsPendingWhere(where, params, userId);
            case APPROVALS_HISTORY -> {
                where.append(" WHERE p.approver_id = ? AND p.status IN ('APPROVED','REJECTED','CANCELLED')");
                params.add(userId);
            }
        }
    }

    private void appendApprovalsPendingWhere(StringBuilder where, List<Object> params, String userId) {
        List<String> buIds = Optional.ofNullable(virtualGroupAccessComponent.getApproverBusinessUnitIds(userId))
                .orElseGet(Collections::emptyList);
        List<String> vgIds = Optional.ofNullable(virtualGroupAccessComponent.getApproverVirtualGroupIds(userId))
                .orElseGet(Collections::emptyList);
        boolean hasBu = !buIds.isEmpty();
        boolean hasVg = !vgIds.isEmpty();
        if (!hasBu && !hasVg) {
            where.append(" WHERE 1=0");
            return;
        }
        where.append(" WHERE p.status = 'PENDING' AND (");
        if (hasBu) {
            where.append("(p.request_type IN (");
            for (int i = 0; i < BU_APPROVER_TYPES.size(); i++) {
                if (i > 0) {
                    where.append(',');
                }
                where.append('?');
                params.add(BU_APPROVER_TYPES.get(i));
            }
            where.append(") AND p.business_unit_id IN (");
            appendInPlaceholders(where, params, buIds);
            where.append("))");
        }
        if (hasBu && hasVg) {
            where.append(" OR ");
        }
        if (hasVg) {
            where.append("(p.request_type = ? AND p.virtual_group_id IN (");
            params.add(PermissionRequestType.VIRTUAL_GROUP_JOIN.name());
            appendInPlaceholders(where, params, vgIds);
            where.append("))");
        }
        where.append(')');
    }

    private static void appendInPlaceholders(StringBuilder sql, List<Object> params, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
            params.add(values.get(i));
        }
    }

    private List<PermissionRequestListItem> loadPage(ListFilterSql filterSql, String fromWhere,
                                                     List<Object> params,
                                                     PermissionListQueryRequest request,
                                                     String groupExpression) {
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(request.size());
        pageParams.add(request.page() * request.size());
        String orderBy = groupExpression == null
                ? filterSql.orderBy(request.sortField(), request.sortDirection())
                : filterSql.orderByGrouped(groupExpression, request.sortField(), request.sortDirection());
        String groupedSelect = groupExpression == null ? "" : ", " + groupExpression + " AS grouped_value";
        String sql = "SELECT p.id, p.applicant_id, p.submitted_by_user_id, p.request_type, p.role_id, p.role_name,"
                + " p.organization_unit_id, p.organization_unit_name, p.virtual_group_id, p.virtual_group_name,"
                + " p.business_unit_id, p.business_unit_name, p.status, p.reason, p.approver_id,"
                + " p.approve_time, p.approve_comment, p.created_at, p.updated_at" + groupedSelect
                + fromWhere + orderBy + " LIMIT ? OFFSET ?";
        return ListQuerySupport.query(jdbcTemplate, sql, pageParams, rs -> {
            List<PermissionRequestListItem> page = new ArrayList<>();
            while (rs.next()) {
                page.add(mapRow(rs));
            }
            return page;
        });
    }

    private PermissionRequestListItem mapRow(ResultSet rs) throws SQLException {
        String buName = rs.getString("business_unit_name");
        String vgName = rs.getString("virtual_group_name");
        String ouName = rs.getString("organization_unit_name");
        String targetName = enrichmentComponent.firstNonBlank(
                enrichmentComponent.nonBlankString(buName),
                enrichmentComponent.nonBlankString(vgName),
                enrichmentComponent.nonBlankString(ouName));
        if (targetName == null) {
            targetName = "-";
        }
        String buId = rs.getString("business_unit_id");
        String vgId = rs.getString("virtual_group_id");
        String ouId = rs.getString("organization_unit_id");
        String targetId = enrichmentComponent.firstNonBlank(
                enrichmentComponent.nonBlankString(buId),
                enrichmentComponent.nonBlankString(vgId),
                enrichmentComponent.nonBlankString(ouId));
        if (targetId == null) {
            targetId = "";
        }
        String roleName = enrichmentComponent.nonBlankString(rs.getString("role_name"));
        List<String> roleNames = roleName != null ? List.of(roleName) : List.of();
        return PermissionRequestListItem.builder()
                .id(rs.getObject("id", Long.class))
                .applicantId(enrichmentComponent.nonBlankString(rs.getString("applicant_id")))
                .submittedByUserId(enrichmentComponent.nonBlankString(rs.getString("submitted_by_user_id")))
                .requestType(enrichmentComponent.nonBlankString(rs.getString("request_type")))
                .targetId(targetId)
                .targetName(targetName)
                .roleNames(roleNames)
                .reason(enrichmentComponent.nonBlankString(rs.getString("reason")))
                .status(enrichmentComponent.nonBlankString(rs.getString("status")))
                .approverId(enrichmentComponent.nonBlankString(rs.getString("approver_id")))
                .approverComment(enrichmentComponent.nonBlankString(rs.getString("approve_comment")))
                .approvedAt(formatTimestampUtc(rs.getTimestamp("approve_time")))
                .createdAt(formatTimestampUtc(rs.getTimestamp("created_at")))
                .updatedAt(formatTimestampUtc(rs.getTimestamp("updated_at")))
                .build();
    }

    private static String formatTimestampUtc(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        return ts.toInstant().atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
