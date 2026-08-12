package com.portal.util;

import com.portal.entity.ProcessDraft;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JPA Specification + whitelist Sort for Portal process drafts.
 */
public final class ProcessDraftListSpec {

    public static final Set<String> SORT_FIELDS = Set.of(
            "processDefinitionKey", "updatedAt", "createdAt");

    public static final Set<String> GROUP_FIELDS = SORT_FIELDS;

    /** SQL/entity filter fields (processDefinitionName is FE-only alias → processDefinitionKey). */
    public static final Set<String> FILTER_FIELDS = Set.of(
            "processDefinitionKey", "updatedAt", "createdAt");

    private static final Set<String> DATE_FIELDS = Set.of("updatedAt", "createdAt");

    private ProcessDraftListSpec() {
    }

    public static String sanitizeGroupBy(String groupBy) {
        return PortalColumnFilterSupport.sanitizeGroupBy(groupBy, GROUP_FIELDS);
    }

    public static List<PortalColumnFilterSupport.ColumnFilter> parseFilters(Map<String, Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String fe = e.getKey().trim();
            // processDefinitionName is resolved after load — handled in ProcessDraftComponent, not Spec.
            if ("processDefinitionName".equals(fe)) {
                continue;
            } else if (DATE_FIELDS.contains(fe)) {
                Object op = e.getValue() != null ? e.getValue().get("operator") : null;
                String operator = op != null ? String.valueOf(op).trim() : "";
                if (!"isNull".equals(operator) && !"isNotNull".equals(operator)) {
                    continue;
                }
                mapped.put(fe, e.getValue());
            } else {
                mapped.put(fe, e.getValue());
            }
        }
        return PortalColumnFilterSupport.parseFilters(mapped, FILTER_FIELDS);
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
        return PortalColumnFilterSupport.withSort(
                pageable, field, sortDirection, groupBy, SORT_FIELDS, "updatedAt", Sort.Direction.DESC);
    }

    public static Specification<ProcessDraft> build(String userId, List<PortalColumnFilterSupport.ColumnFilter> filters) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (filters != null) {
                for (PortalColumnFilterSupport.ColumnFilter filter : filters) {
                    Predicate p = buildFilterPredicate(root, cb, filter);
                    if (p != null) {
                        predicates.add(p);
                    }
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate buildFilterPredicate(
            jakarta.persistence.criteria.Root<ProcessDraft> root,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            PortalColumnFilterSupport.ColumnFilter filter) {
        if (filter == null || filter.field() == null || filter.operator() == null) {
            return null;
        }
        String op = filter.operator().trim();
        String value = filter.value() != null ? filter.value() : "";
        if (DATE_FIELDS.contains(filter.field())) {
            return PortalColumnFilterSupport.dateNullOperator(root, cb, filter.field(), op);
        }
        return PortalColumnFilterSupport.textOperator(root, cb, filter.field(), op, value);
    }
}
