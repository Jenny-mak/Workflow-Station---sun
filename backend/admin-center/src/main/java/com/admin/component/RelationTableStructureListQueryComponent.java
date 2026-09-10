package com.admin.component;

import com.admin.dto.list.RelationTableFuGroup;
import com.admin.dto.list.RelationTableStructureListPage;
import com.admin.dto.request.RelationTableStructureListQueryRequest;
import com.admin.dto.response.RelationTableResponse;
import com.admin.entity.FunctionUnit;
import com.admin.entity.RelationTableDefinition;
import com.admin.entity.RelationTableFunctionUnit;

import com.admin.list.FunctionUnitColumnSpec;
import com.admin.list.ListQuerySupport;
import com.admin.list.RelationTableStructureColumnSpec;
import com.admin.repository.RelationTableDefinitionRepository;
import com.platform.common.enums.RelationTableStatus;
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
 * Relation Table Structure list: COUNT(*) and the page share the left-rail Function Unit
 * filter plus column filters. Sidebar groups are counted over the whole catalog so a
 * table linked to two units appears under both.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelationTableStructureListQueryComponent {

    static final String LIST_KEY = "admin-relation-table-structures";

    private static final String FROM = " FROM rt_table_definitions t WHERE 1=1 ";

    private final JdbcTemplate jdbcTemplate;
    private final RelationTableDefinitionRepository tableDefinitionRepository;
    private final RelationTableFunctionUnitResolver relationTableFunctionUnitResolver;

    @Transactional(readOnly = true)
    public RelationTableStructureListPage query(RelationTableStructureListQueryRequest request) {
        long started = System.nanoTime();
        ListFilterSql filterSql = RelationTableStructureColumnSpec.sql();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(FROM);
        appendFunctionUnit(where, params, request.functionUnitCode());
        where.append(filterSql.whereClause(request.filters(), params));

        ResultSetExtractor<Long> countExtractor = rs -> rs.next() ? rs.getLong(1) : 0L;
        long total = ListQuerySupport.requireCount(
                ListQuerySupport.query(jdbcTemplate, "SELECT COUNT(*)" + where, params, countExtractor),
                LIST_KEY);


        PageIds pageIds = loadPageIds(filterSql, where.toString(), params, request);
        List<RelationTableResponse> rows = toRows(pageIds.ids());
        List<RelationTableFuGroup> fuGroups = loadFunctionUnitGroups();
        ListQuerySupport.logIfSlow(log, LIST_KEY, request.page(), request.size(), total, started);
        return new RelationTableStructureListPage(RelationTableStructureColumnSpec.columns(), rows,
                request.page(), request.size(), total, fuGroups);
    }

    private PageIds loadPageIds(ListFilterSql filterSql, String where, List<Object> params,
                                RelationTableStructureListQueryRequest request) {
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(request.size());
        pageParams.add(request.page() * request.size());
        String orderBy = filterSql.orderBy(request.sortField(), request.sortDirection());
        String sql = "SELECT t.id" + where + orderBy + " LIMIT ? OFFSET ?";
        ResultSetExtractor<PageIds> extractor = rs -> {
            List<Long> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
            return new PageIds(ids);
        };
        return ListQuerySupport.query(jdbcTemplate, sql, pageParams, extractor);
    }

    private List<RelationTableResponse> toRows(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, RelationTableDefinition> byId = tableDefinitionRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(RelationTableDefinition::getId, Function.identity()));
        Map<Long, List<RelationTableFunctionUnit>> linksByTable =
                relationTableFunctionUnitResolver.loadLinksByTable(ids);
        Map<String, FunctionUnit> functionUnitsById =
                relationTableFunctionUnitResolver.loadFunctionUnitsById(linksByTable);
        List<RelationTableResponse> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            RelationTableDefinition entity = byId.get(id);
            if (entity == null) {
                throw new IllegalStateException("relation-table structure page referenced missing table " + id);
            }
            RelationTableResponse row = RelationTableResponse.fromEntity(entity);
            row.applyFunctionUnits(relationTableFunctionUnitResolver.resolve(linksByTable.get(id), functionUnitsById));
            ordered.add(row);
        }
        return ordered;
    }

    private List<RelationTableFuGroup> loadFunctionUnitGroups() {
        String commonSql = """
                SELECT COUNT(*) FROM rt_table_definitions t
                WHERE NOT EXISTS (
                    SELECT 1 FROM rt_table_function_units l WHERE l.relation_table_id = t.id)
                """;
        ResultSetExtractor<Long> countExtractor = rs -> rs.next() ? rs.getLong(1) : 0L;
        long commonCount = ListQuerySupport.requireCount(
                ListQuerySupport.query(jdbcTemplate, commonSql, List.of(), countExtractor), LIST_KEY);
        // Grouped by code, not by l.function_unit_id: every publish/import writes another
        // sys_function_units row for the same unit, so an id-keyed rail repeated the same unit once
        // per version. The label comes from the newest version of the code (same DISTINCT ON rule as
        // the Function Unit catalog list) and the count is DISTINCT so a table linked to two versions
        // of one unit is still one table.
        String fuSql = """
                SELECT fu.code AS fu_code, newest.name AS fu_name,
                       COUNT(DISTINCT l.relation_table_id) AS group_count
                FROM rt_table_function_units l
                JOIN sys_function_units fu ON fu.id = l.function_unit_id
                JOIN (
                    SELECT DISTINCT ON (fu.code) fu.code, fu.name
                    FROM sys_function_units fu
                    ORDER BY fu.code, %s, fu.updated_at DESC NULLS LAST
                ) newest ON newest.code = fu.code
                GROUP BY fu.code, newest.name
                ORDER BY newest.name ASC NULLS LAST, fu.code ASC
                """.formatted(FunctionUnitColumnSpec.VERSION_ORDER_SQL);
        ResultSetExtractor<List<RelationTableFuGroup>> extractor = rs -> {
            List<RelationTableFuGroup> groups = new ArrayList<>();
            if (commonCount > 0) {
                groups.add(new RelationTableFuGroup(
                        RelationTableStructureListQueryRequest.COMMON_KEY, null, commonCount));
            }
            while (rs.next()) {
                String name = rs.getString("fu_name");
                String code = rs.getString("fu_code");
                String label = (name == null || name.isBlank()) ? code : name;
                groups.add(new RelationTableFuGroup(code, label, rs.getLong("group_count")));
            }
            return groups;
        };
        return ListQuerySupport.query(jdbcTemplate, fuSql, List.of(), extractor);
    }


    private static void appendFunctionUnit(StringBuilder where, List<Object> params, String functionUnitCode) {
        if (functionUnitCode == null || functionUnitCode.isBlank()) {
            return;
        }
        if (RelationTableStructureListQueryRequest.COMMON_KEY.equals(functionUnitCode)) {
            where.append(" AND NOT EXISTS (SELECT 1 FROM rt_table_function_units l")
                    .append(" WHERE l.relation_table_id = t.id)");
            return;
        }
        // Match on code so the rail keeps listing tables that were linked under an earlier version.
        where.append(" AND EXISTS (SELECT 1 FROM rt_table_function_units l")
                .append(" JOIN sys_function_units fu ON fu.id = l.function_unit_id")
                .append(" WHERE l.relation_table_id = t.id AND fu.code = ?)");
        params.add(functionUnitCode);
    }


    private record PageIds(List<Long> ids) {
    }
}
