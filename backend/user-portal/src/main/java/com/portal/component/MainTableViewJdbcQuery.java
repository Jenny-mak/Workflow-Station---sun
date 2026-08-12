package com.portal.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewColumnFilter;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewGroupCount;
import com.portal.util.MainTableViewSqlQueryCompiler;
import com.portal.util.MainTableViewSqlQueryCompiler.FieldMeta;
import com.portal.util.MainTableViewSqlQueryCompiler.RowSource;
import com.portal.util.MainTableViewSqlQueryCompiler.SqlFragment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Database-authoritative Main Table View queries (COUNT / page / groupCounts).
 * No in-memory 5000-row cap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MainTableViewJdbcQuery {

    public record QuerySpec(
            String functionUnitCode,
            List<FieldMeta> fields,
            Set<String> visibleFieldNames,
            Map<String, Object> designerFilter,
            List<Map<String, Object>> viewSortConfig,
            List<MainTableViewColumnFilter> columnFilters,
            String search,
            String sortField,
            String sortDirection,
            String groupBy,
            boolean restrictToInvolvedUsers,
            String userId,
            List<String> subBindingKeys,
            boolean skipInvolvementForAdmin) {}

    public record MainPageResult(
            long total,
            List<MainInstanceRow> rows,
            List<MainTableViewGroupCount> groupCounts) {}

    public record MainInstanceRow(
            String id,
            String status,
            LocalDateTime startTime,
            String startUserId,
            String startUserName,
            String currentNode,
            Map<String, Object> variables) {}

    public record SubPageResult(
            long total,
            List<SubRow> rows,
            List<MainTableViewGroupCount> groupCounts) {}

    public record SubRow(
            String processInstanceId,
            String status,
            LocalDateTime startTime,
            String startUserId,
            String startUserName,
            String currentNode,
            Map<String, Object> mainVariables,
            Map<String, Object> subElem) {}

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MainPageResult queryMain(QuerySpec spec, int page, int size) {
        MainTableViewSqlQueryCompiler compiler =
                new MainTableViewSqlQueryCompiler(RowSource.MAIN, spec.fields());
        SqlFragment where = buildWhere(compiler, spec, RowSource.MAIN);
        SqlFragment orderBy = compiler.compileOrderBy(
                spec.groupBy(), spec.sortField(), spec.sortDirection(), spec.viewSortConfig());

        long total = countMain(where);
        List<MainTableViewGroupCount> groupCounts = List.of();
        if (spec.groupBy() != null && !spec.groupBy().isBlank()) {
            groupCounts = groupCountsMain(compiler, where, spec.groupBy());
        }
        List<MainInstanceRow> rows = selectMainPage(where, orderBy, page, size);
        return new MainPageResult(total, rows, groupCounts);
    }

    public SubPageResult querySub(QuerySpec spec, int page, int size) {
        MainTableViewSqlQueryCompiler compiler =
                new MainTableViewSqlQueryCompiler(RowSource.SUB, spec.fields());
        String fromSql = buildSubFromSql(spec.subBindingKeys());
        SqlFragment where = buildWhere(compiler, spec, RowSource.SUB);
        SqlFragment orderBy = compiler.compileOrderBy(
                spec.groupBy(), spec.sortField(), spec.sortDirection(), spec.viewSortConfig());

        long total = countSub(fromSql, where);
        List<MainTableViewGroupCount> groupCounts = List.of();
        if (spec.groupBy() != null && !spec.groupBy().isBlank()) {
            groupCounts = groupCountsSub(compiler, fromSql, where, spec.groupBy());
        }
        List<SubRow> rows = selectSubPage(fromSql, where, orderBy, page, size);
        return new SubPageResult(total, rows, groupCounts);
    }

    /** Export helper: MAIN rows up to {@code limit} with same filters/sort (no groupBy required). */
    public List<MainInstanceRow> queryMainExport(QuerySpec spec, int limit) {
        MainTableViewSqlQueryCompiler compiler =
                new MainTableViewSqlQueryCompiler(RowSource.MAIN, spec.fields());
        SqlFragment where = buildWhere(compiler, spec, RowSource.MAIN);
        SqlFragment orderBy = compiler.compileOrderBy(
                null, spec.sortField(), spec.sortDirection(), spec.viewSortConfig());
        return selectMainPage(where, orderBy, 0, Math.max(1, limit));
    }

    public List<SubRow> querySubExport(QuerySpec spec, int limit) {
        MainTableViewSqlQueryCompiler compiler =
                new MainTableViewSqlQueryCompiler(RowSource.SUB, spec.fields());
        String fromSql = buildSubFromSql(spec.subBindingKeys());
        SqlFragment where = buildWhere(compiler, spec, RowSource.SUB);
        SqlFragment orderBy = compiler.compileOrderBy(
                null, spec.sortField(), spec.sortDirection(), spec.viewSortConfig());
        return selectSubPage(fromSql, where, orderBy, 0, Math.max(1, limit));
    }

    private SqlFragment buildWhere(
            MainTableViewSqlQueryCompiler compiler, QuerySpec spec, RowSource source) {
        SqlFragment where = new SqlFragment("pi.function_unit_code = ?", List.of(spec.functionUnitCode()));
        where = where.and(compiler.compileDesignerFilter(spec.designerFilter()));
        where = where.and(compiler.compileColumnFilters(spec.columnFilters()));
        where = where.and(compiler.compileSearch(spec.search(), spec.visibleFieldNames()));
        if (spec.restrictToInvolvedUsers() && !spec.skipInvolvementForAdmin()) {
            where = where.and(compiler.compileInvolvement(spec.userId()));
        }
        return where;
    }

    private long countMain(SqlFragment where) {
        String sql = "SELECT COUNT(*) FROM up_process_instance pi WHERE " + where.sql();
        Long count = jdbcTemplate.queryForObject(sql, Long.class, where.params().toArray());
        return count != null ? count : 0L;
    }

    private List<MainTableViewGroupCount> groupCountsMain(
            MainTableViewSqlQueryCompiler compiler, SqlFragment where, String groupBy) {
        String labelExpr = compiler.groupLabelExpr(groupBy);
        if (labelExpr == null) {
            return List.of();
        }
        String sql = "SELECT " + labelExpr + " AS grp_label, COUNT(*) AS cnt "
                + "FROM up_process_instance pi WHERE " + where.sql()
                + " GROUP BY 1 ORDER BY 1";
        return jdbcTemplate.query(sql, (rs, i) -> MainTableViewGroupCount.builder()
                        .label(rs.getString("grp_label"))
                        .count(rs.getLong("cnt"))
                        .build(),
                where.params().toArray());
    }

    private List<MainInstanceRow> selectMainPage(
            SqlFragment where, SqlFragment orderBy, int page, int size) {
        String sql = """
                SELECT pi.id, pi.status, pi.start_time, pi.start_user_id, pi.start_user_name,
                       pi.current_node, pi.variables
                FROM up_process_instance pi
                WHERE %s
                ORDER BY %s
                LIMIT ? OFFSET ?
                """.formatted(where.sql(), orderBy.sql());
        List<Object> params = new ArrayList<>(where.params());
        params.addAll(orderBy.params());
        params.add(size);
        params.add(Math.max(page, 0) * (long) size);
        return jdbcTemplate.query(sql, (rs, i) -> mapMainRow(rs), params.toArray());
    }

    private long countSub(String fromSql, SqlFragment where) {
        String sql = "SELECT COUNT(*) FROM " + fromSql + " WHERE " + where.sql();
        Long count = jdbcTemplate.queryForObject(sql, Long.class, where.params().toArray());
        return count != null ? count : 0L;
    }

    private List<MainTableViewGroupCount> groupCountsSub(
            MainTableViewSqlQueryCompiler compiler,
            String fromSql,
            SqlFragment where,
            String groupBy) {
        String labelExpr = compiler.groupLabelExpr(groupBy);
        if (labelExpr == null) {
            return List.of();
        }
        String sql = "SELECT " + labelExpr + " AS grp_label, COUNT(*) AS cnt FROM "
                + fromSql + " WHERE " + where.sql() + " GROUP BY 1 ORDER BY 1";
        return jdbcTemplate.query(sql, (rs, i) -> MainTableViewGroupCount.builder()
                        .label(rs.getString("grp_label"))
                        .count(rs.getLong("cnt"))
                        .build(),
                where.params().toArray());
    }

    private List<SubRow> selectSubPage(
            String fromSql, SqlFragment where, SqlFragment orderBy, int page, int size) {
        String sql = """
                SELECT pi.id AS process_instance_id, pi.status, pi.start_time, pi.start_user_id,
                       pi.start_user_name, pi.current_node, pi.variables, pi.sub_elem
                FROM %s
                WHERE %s
                ORDER BY %s
                LIMIT ? OFFSET ?
                """.formatted(fromSql, where.sql(), orderBy.sql());
        List<Object> params = new ArrayList<>(where.params());
        params.addAll(orderBy.params());
        params.add(size);
        params.add(Math.max(page, 0) * (long) size);
        return jdbcTemplate.query(sql, (rs, i) -> mapSubRow(rs), params.toArray());
    }

    /**
     * Expand all binding keys, dedupe by (process id, row id) matching portal Java semantics.
     * Result alias {@code pi} exposes process columns plus {@code sub_elem}.
     */
    private String buildSubFromSql(List<String> bindingKeys) {
        List<String> safeKeys = (bindingKeys == null ? List.<String>of() : bindingKeys).stream()
                .filter(k -> k != null && k.matches("[0-9]{1,20}"))
                .distinct()
                .toList();
        if (safeKeys.isEmpty()) {
            return """
                    (
                      SELECT pi.*, NULL::jsonb AS sub_elem
                      FROM up_process_instance pi
                      WHERE FALSE
                    ) pi
                    """;
        }
        String union = safeKeys.stream()
                .map(k -> "SELECT jsonb_array_elements(COALESCE(pi.variables->'__subTables__'->'"
                        + k + "', '[]'::jsonb)) AS elem")
                .collect(Collectors.joining(" UNION ALL "));
        return """
                (
                  SELECT DISTINCT ON (
                           pi.id,
                           COALESCE(elem->>'id', elem->>'id_idw', md5(elem::text))
                         )
                         pi.*,
                         elem AS sub_elem
                  FROM up_process_instance pi
                  CROSS JOIN LATERAL (
                    %s
                  ) expanded(elem)
                  ORDER BY pi.id,
                           COALESCE(elem->>'id', elem->>'id_idw', md5(elem::text)),
                           pi.start_time DESC NULLS LAST
                ) pi
                """.formatted(union);
    }

    private MainInstanceRow mapMainRow(ResultSet rs) throws SQLException {
        return new MainInstanceRow(
                rs.getString("id"),
                rs.getString("status"),
                toLocalDateTime(rs.getTimestamp("start_time")),
                rs.getString("start_user_id"),
                rs.getString("start_user_name"),
                rs.getString("current_node"),
                parseJsonMap(rs.getString("variables")));
    }

    private SubRow mapSubRow(ResultSet rs) throws SQLException {
        return new SubRow(
                rs.getString("process_instance_id"),
                rs.getString("status"),
                toLocalDateTime(rs.getTimestamp("start_time")),
                rs.getString("start_user_id"),
                rs.getString("start_user_name"),
                rs.getString("current_node"),
                parseJsonMap(rs.getString("variables")),
                parseJsonMap(rs.getString("sub_elem")));
    }

    private Map<String, Object> parseJsonMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JSONB payload: {}", e.getMessage());
            return Map.of();
        }
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }
}
