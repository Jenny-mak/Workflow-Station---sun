package com.portal.util;

import com.portal.entity.DelegationAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JPA Specification + whitelist Sort for Portal delegation audit records.
 * Visibility: delegatorId OR delegateId = current user.
 */
public final class DelegationAuditListSpec {

    public static final Set<String> SORT_FIELDS = Set.of(
            "operationType", "delegatorId", "delegateId", "operationResult", "createdAt");

    public static final Set<String> GROUP_FIELDS = SORT_FIELDS;

    public static final Set<String> FILTER_FIELDS = Set.of(
            "operationType", "delegatorId", "delegateId", "operationResult", "createdAt");

    private DelegationAuditListSpec() {
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
            if ("createdAt".equals(field)) {
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

    public static Specification<DelegationAudit> build(
            String userId, List<PortalColumnFilterSupport.ColumnFilter> filters) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.or(
                    cb.equal(root.get("delegatorId"), userId),
                    cb.equal(root.get("delegateId"), userId)));
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
            jakarta.persistence.criteria.Root<DelegationAudit> root,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            PortalColumnFilterSupport.ColumnFilter filter) {
        if (filter == null || filter.field() == null || filter.operator() == null) {
            return null;
        }
        String op = filter.operator().trim();
        String value = filter.value() != null ? filter.value() : "";
        if ("createdAt".equals(filter.field())) {
            return PortalColumnFilterSupport.dateNullOperator(root, cb, filter.field(), op);
        }
        return PortalColumnFilterSupport.textOperator(root, cb, filter.field(), op, value);
    }
}
