package com.admin.bi.component;

import com.admin.bi.dto.response.DashboardRegistryResponse;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.dto.list.AdminListPage;
import com.admin.dto.request.BiDashboardListQueryRequest;
import com.admin.list.BiDashboardColumnSpec;

import com.admin.list.ListQuerySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import com.platform.common.list.ListFilterSql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * BI Dashboard Registry list: COUNT(*) and the page share toolbar
 * title/tags/status plus column filters. Outer alias is {@code d}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiDashboardListQueryComponent {

    static final String LIST_KEY = "admin-bi-dashboards";

    private final JdbcTemplate jdbcTemplate;
    private final BiDashboardRegistryRepository registryRepository;
    private final BiDashboardRegistryResponseAssembler responseAssembler;

    public AdminListPage<DashboardRegistryResponse> query(BiDashboardListQueryRequest request) {
        long started = System.nanoTime();
        ListFilterSql filterSql = BiDashboardColumnSpec.sql();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" FROM bi_dashboard_registry d WHERE 1=1");
        appendTitle(where, params, request.title());
        appendTags(where, params, request.tags());
        appendStatus(where, params, request.status());
        where.append(filterSql.whereClause(request.filters(), params));

        ResultSetExtractor<Long> countExtractor = rs -> rs.next() ? rs.getLong(1) : 0L;
        long total = ListQuerySupport.requireCount(
                ListQuerySupport.query(jdbcTemplate, "SELECT COUNT(*)" + where, params, countExtractor),
                LIST_KEY);


        PageIds pageIds = loadPageIds(filterSql, where.toString(), params, request);
        List<DashboardRegistryResponse> rows = toRows(pageIds.ids());
        ListQuerySupport.logIfSlow(log, LIST_KEY, request.page(), request.size(), total, started);
        return new AdminListPage<>(BiDashboardColumnSpec.columns(), rows,
                request.page(), request.size(), total);
    }

    private PageIds loadPageIds(ListFilterSql filterSql, String where, List<Object> params,
                                BiDashboardListQueryRequest request) {
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

    private List<DashboardRegistryResponse> toRows(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, BiDashboardRegistry> byId = registryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(BiDashboardRegistry::getId, Function.identity()));
        List<BiDashboardRegistry> ordered = new ArrayList<>(ids.size());
        for (String id : ids) {
            BiDashboardRegistry entity = byId.get(id);
            if (entity == null) {
                throw new IllegalStateException("bi-dashboard page referenced missing dashboard " + id);
            }
            ordered.add(entity);
        }
        return responseAssembler.toResponses(ordered);
    }



    private static void appendTitle(StringBuilder where, List<Object> params, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        where.append(" AND d.dashboard_title ILIKE ?");
        params.add("%" + ListFilterSql.escapeLike(title.trim()) + "%");
    }

    private static void appendTags(StringBuilder where, List<Object> params, String tags) {
        if (tags == null || tags.isBlank()) {
            return;
        }
        where.append(" AND d.tags ILIKE ?");
        params.add("%" + ListFilterSql.escapeLike(tags.trim()) + "%");
    }

    private static void appendStatus(StringBuilder where, List<Object> params, String status) {
        if (status == null || status.isBlank()) {
            return;
        }
        where.append(" AND d.status = ?");
        params.add(DashboardStatus.valueOf(status.trim()).name());
    }


    private record PageIds(List<String> ids) {
    }
}
