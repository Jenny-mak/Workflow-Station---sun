package com.portal.util;

import com.portal.entity.DelegationRule;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JPA Specification + whitelist Sort for Portal "my delegation rules".
 */
public final class DelegationRuleListSpec {

    public static final Set<String> SORT_FIELDS = Set.of(
            "delegateId", "delegationType", "status", "startTime", "endTime", "reason", "createdAt", "updatedAt");

    public static final Set<String> GROUP_FIELDS = Set.of(
            "delegateId", "delegationType", "status", "startTime", "endTime", "reason");

    public static final Set<String> FILTER_FIELDS = Set.of(
            "delegateId", "delegationType", "status", "startTime", "endTime", "reason");

    private static final Set<String> DATE_FIELDS = Set.of("startTime", "endTime");

    private DelegationRuleListSpec() {
    }

    public static String sanitizeGroupBy(String groupBy) {
        return PortalColumnFilterSupport.sanitizeGroupBy(groupBy, GROUP_FIELDS);
    }

    public static List<PortalColumnFilterSupport.ColumnFilter> parseFilters(Map<String, Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> cleaned = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String field = e.getKey().trim();
            if (DATE_FIELDS.contains(field)) {
                Object op = e.getValue().get("operator");
                String operator = op != null ? String.valueOf(op).trim() : "";
                if (!"isNull".equals(operator) && !"isNotNull".equals(operator)) {
                    continue;
                }
            }
            cleaned.put(field, e.getValue());
        }
        return PortalColumnFilterSupport.parseFilters(cleaned, FILTER_FIELDS);
    }

    public static Pageable withSort(Pageable pageable, String sortField, String sortDirection, String groupBy) {
        return PortalColumnFilterSupport.withSort(
                pageable, sortField, sortDirection, sanitizeGroupBy(groupBy),
                SORT_FIELDS, "createdAt", Sort.Direction.DESC);
    }

    public static Specification<DelegationRule> build(
            String delegatorId, List<PortalColumnFilterSupport.ColumnFilter> filters) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("delegatorId"), delegatorId));
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
            jakarta.persistence.criteria.Root<DelegationRule> root,
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
        // Enums stored as STRING — cast path to String for text ops
        return PortalColumnFilterSupport.textOperator(root, cb, filter.field(), op, value);
    }
}
