package com.admin.component;

import com.admin.dto.list.AdminListPage;
import com.admin.dto.request.FunctionUnitDeploymentListQueryRequest;
import com.admin.dto.response.DeploymentInfo;
import com.admin.entity.FunctionUnitDeployment;
import com.admin.enums.DeploymentStatus;
import com.admin.list.FunctionUnitDeploymentColumnSpec;

import com.admin.list.ListQuerySupport;
import com.admin.repository.FunctionUnitDeploymentRepository;
import com.admin.service.UserReferenceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.platform.common.list.ListFilterSql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Function Unit deployment records: COUNT(*) and the page share one predicate.
 * Grouping writes {@link DeploymentInfo} only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FunctionUnitDeploymentListQueryComponent {

    static final String LIST_KEY = "admin-function-unit-deployments";

    private static final String FROM = """
             FROM sys_function_unit_deployments d
             JOIN sys_function_units fu ON fu.id = d.function_unit_id
             WHERE 1=1
            """;

    private final JdbcTemplate jdbcTemplate;
    private final FunctionUnitDeploymentRepository deploymentRepository;
    private final UserReferenceResolver userReferenceResolver;

    @Transactional(readOnly = true)
    public AdminListPage<DeploymentInfo> query(FunctionUnitDeploymentListQueryRequest request) {
        long started = System.nanoTime();
        ListFilterSql filterSql = FunctionUnitDeploymentColumnSpec.sql();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(FROM);
        appendKeyword(where, params, request.keyword());
        where.append(filterSql.whereClause(request.filters(), params));

        ResultSetExtractor<Long> countExtractor = rs -> rs.next() ? rs.getLong(1) : 0L;
        long total = ListQuerySupport.requireCount(
                ListQuerySupport.query(jdbcTemplate, "SELECT COUNT(*)" + where, params, countExtractor),
                LIST_KEY);


        PageIds pageIds = loadPageIds(filterSql, where.toString(), params, request);
        List<DeploymentInfo> rows = toRows(pageIds.ids());
        ListQuerySupport.logIfSlow(log, LIST_KEY, request.page(), request.size(), total, started);
        return new AdminListPage<>(FunctionUnitDeploymentColumnSpec.columns(), rows,
                request.page(), request.size(), total);
    }

    private PageIds loadPageIds(ListFilterSql filterSql, String where, List<Object> params,
                                FunctionUnitDeploymentListQueryRequest request) {
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(request.size());
        pageParams.add(request.page() * request.size());
        String orderBy = filterSql.orderBy(request.sortField(), request.sortDirection());
        String sql = "SELECT d.id" + where + orderBy + " LIMIT ? OFFSET ?";
        ResultSetExtractor<PageIds> extractor = rs -> {
            List<String> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getString("id"));
            }
            return new PageIds(ids);
        };
        return ListQuerySupport.query(jdbcTemplate, sql, pageParams, extractor);
    }

    private List<DeploymentInfo> toRows(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, FunctionUnitDeployment> byId = deploymentRepository.findByIdInWithFunctionUnit(ids).stream()
                .collect(Collectors.toMap(FunctionUnitDeployment::getId, Function.identity()));
        List<DeploymentInfo> ordered = new ArrayList<>(ids.size());
        for (String id : ids) {
            FunctionUnitDeployment entity = byId.get(id);
            if (entity == null) {
                throw new IllegalStateException("deployment page referenced missing deployment " + id);
            }
            ordered.add(DeploymentInfo.fromEntity(entity));
        }
        enrichDeployedBy(ordered);
        return ordered;
    }

    private void enrichDeployedBy(List<DeploymentInfo> rows) {
        var cache = userReferenceResolver.resolveUsernames(
                rows.stream().map(DeploymentInfo::getDeployedBy).toList());
        for (DeploymentInfo row : rows) {
            if (row.getDeployedBy() != null) {
                row.setDeployedBy(userReferenceResolver.resolveWithCache(row.getDeployedBy(), cache));
            }
        }
    }

    static void appendKeyword(StringBuilder where, List<Object> params, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String like = "%" + ListFilterSql.escapeLike(keyword.trim()) + "%";
        where.append("""
                 AND (
                   fu.name ILIKE ?
                   OR fu.code ILIKE ?
                   OR COALESCE(fu.version, '') ILIKE ?
                   OR CAST(d.status AS TEXT) ILIKE ?
                 )
                """);
        params.add(like);
        params.add(like);
        params.add(like);
        params.add(like);
    }



    private record PageIds(List<String> ids) {
    }
}
