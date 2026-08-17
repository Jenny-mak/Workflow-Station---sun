package com.portal.util;

import com.portal.entity.ProcessDraft;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JPA Specification + whitelist Sort for Portal process drafts.
 *
 * <p>{@link #COLUMNS} is the single declaration the header dialog, the filter predicates and
 * the column-meta endpoint all derive from. {@code processDefinitionName} is resolved after
 * load (not a stored column); it is still declared so the dialog can offer text operators,
 * then stripped from the JPA filter list.
 */
public final class ProcessDraftListSpec {

    public static final List<PortalListColumnMeta> COLUMNS = List.of(
            PortalListColumnMeta.text("processDefinitionName"),
            PortalListColumnMeta.datetime("updatedAt"),
            PortalListColumnMeta.sortOnly("createdAt", PortalListColumnMeta.Kind.DATETIME),
            PortalListColumnMeta.of("processDefinitionKey", PortalListColumnMeta.Kind.TEXT, false, true, true));

    public static final Set<String> SORT_FIELDS = PortalListColumnMeta.sortFields(COLUMNS);

    public static final Set<String> GROUP_FIELDS = PortalListColumnMeta.groupFields(COLUMNS);

    public static final Set<String> FILTER_FIELDS = PortalListColumnMeta.filterFields(COLUMNS);

    private ProcessDraftListSpec() {
    }

    public static String sanitizeGroupBy(String groupBy) {
        if ("processDefinitionName".equals(groupBy != null ? groupBy.trim() : "")) {
            groupBy = "processDefinitionKey";
        }
        return PortalColumnFilterSupport.sanitizeGroupBy(groupBy, GROUP_FIELDS);
    }

    public static List<PortalColumnFilterSupport.ColumnFilter> parseFilters(Map<String, Map<String, Object>> raw) {
        List<PortalColumnFilterSupport.ColumnFilter> parsed = PortalColumnFilterSupport.parseFilters(raw, COLUMNS);
        List<PortalColumnFilterSupport.ColumnFilter> entity = new ArrayList<>();
        for (PortalColumnFilterSupport.ColumnFilter filter : parsed) {
            if (!"processDefinitionName".equals(filter.field())) {
                entity.add(filter);
            }
        }
        return entity;
    }

    /** True when FE asked to filter by resolved display name (not stored on entity). */
    public static boolean hasProcessDefinitionNameFilter(Map<String, Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        return raw.keySet().stream().anyMatch(k -> k != null && "processDefinitionName".equals(k.trim()));
    }

    public static PortalColumnFilterSupport.ColumnFilter processDefinitionNameFilter(
            Map<String, Map<String, Object>> raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> cfg = raw.get("processDefinitionName");
        if (cfg == null) {
            for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
                if (e.getKey() != null && "processDefinitionName".equals(e.getKey().trim())) {
                    cfg = e.getValue();
                    break;
                }
            }
        }
        if (cfg == null) {
            return null;
        }
        Object op = cfg.get("operator");
        Object val = cfg.get("value");
        String operator = op != null ? String.valueOf(op).trim() : "contains";
        String value = val != null ? String.valueOf(val) : "";
        return new PortalColumnFilterSupport.ColumnFilter("processDefinitionName", operator, value);
    }

    public static Pageable withSort(Pageable pageable, String sortField, String sortDirection, String groupBy) {
        String field = sortField;
        if ("processDefinitionName".equals(field != null ? field.trim() : "")) {
            field = "processDefinitionKey";
        }
        String group = groupBy;
        if ("processDefinitionName".equals(group != null ? group.trim() : "")) {
            group = "processDefinitionKey";
        }
        return PortalColumnFilterSupport.withSort(
                pageable, field, sortDirection, group, SORT_FIELDS, "updatedAt", Sort.Direction.DESC);
    }

    public static Specification<ProcessDraft> build(String userId, List<PortalColumnFilterSupport.ColumnFilter> filters) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (filters != null) {
                for (PortalColumnFilterSupport.ColumnFilter filter : filters) {
                    Predicate p = PortalColumnFilterSupport.buildPredicate(root, cb, COLUMNS, filter);
                    if (p != null) {
                        predicates.add(p);
                    }
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
