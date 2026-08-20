package com.portal.util;

import com.portal.dto.PortalListGroup;
import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared mechanics for list queries that page in SQL: parameter binding, group counts over the
 * whole predicate, and the slow-query log required by the shared-list spec (&gt;1s WARN, no row
 * content, no filter values).
 */
public final class ListQuerySupport {

    private static final long SLOW_MS = 1_000L;

    private ListQuerySupport() {
    }

    public static <T> T query(JdbcTemplate jdbcTemplate, String sql, List<Object> params,
                              ResultSetExtractor<T> extractor) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            int index = 1;
            for (Object param : params) {
                statement.setObject(index++, param);
            }
            return statement;
        };
        return jdbcTemplate.query(creator, extractor);
    }

    public static long requireCount(Long total, String listKey) {
        if (total == null) {
            throw new IllegalStateException("COUNT(*) returned null for " + listKey);
        }
        return total;
    }

    public static List<PortalListGroup> groupsOf(JdbcTemplate jdbcTemplate, String groupExpression,
                                                 String fromAndWhere, List<Object> params) {
        String sql = "SELECT " + groupExpression + " AS group_label, COUNT(*) AS group_count"
                + fromAndWhere
                + " GROUP BY " + groupExpression
                + " ORDER BY " + groupExpression + " ASC NULLS LAST";
        return query(jdbcTemplate, sql, params, rs -> {
            List<PortalListGroup> groups = new ArrayList<>();
            while (rs.next()) {
                groups.add(new PortalListGroup(rs.getString("group_label"), rs.getLong("group_count")));
            }
            return groups;
        });
    }

    public static void logIfSlow(Logger log, String listKey, int page, int size, long total,
                                 long startedNanos) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (elapsedMs > SLOW_MS) {
            log.warn("Slow list query listKey={} viewId={} page={} size={} total={} elapsedMs={}",
                    listKey, listKey, page, size, total, elapsedMs);
        }
    }

    /**
     * Conventional list SLA is 500ms ({@code code-quality-standards} 常规业务).
     * Phase times have no row content and no filter values.
     */
    public static void logIfOverSla(Logger log, String listKey, int page, int size, long total,
                                    long elapsedMs, long sqlMs, long hydrateMs) {
        if (elapsedMs > 500L) {
            log.warn("List query over 500ms SLA listKey={} page={} size={} total={} elapsedMs={} sqlMs={} hydrateMs={}",
                    listKey, page, size, total, elapsedMs, sqlMs, hydrateMs);
        }
    }
}
