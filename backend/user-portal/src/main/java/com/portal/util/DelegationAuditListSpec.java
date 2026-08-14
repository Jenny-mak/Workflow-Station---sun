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
 *
 * <p>{@link #COLUMNS} is the single declaration the whitelists, the filter predicates and
 * the column-meta endpoint all derive from. operationType / operationResult are free text
 * because they are written by several call sites and are not a closed code list.
 */
public final class DelegationAuditListSpec {

    public static final List<PortalListColumnMeta> COLUMNS = List.of(
            PortalListColumnMeta.text("operationType"),
            PortalListColumnMeta.user("delegatorId"),
            PortalListColumnMeta.user("delegateId"),
            PortalListColumnMeta.text("operationResult"),
            PortalListColumnMeta.datetime("createdAt"));

    public static final Set<String> SORT_FIELDS = PortalListColumnMeta.sortFields(COLUMNS);

    public static final Set<String> GROUP_FIELDS = PortalListColumnMeta.groupFields(COLUMNS);

    public static final Set<String> FILTER_FIELDS = PortalListColumnMeta.filterFields(COLUMNS);

    private DelegationAuditListSpec() {
    }

    public static String sanitizeGroupBy(String groupBy) {
        return PortalColumnFilterSupport.sanitizeGroupBy(groupBy, GROUP_FIELDS);
    }

    public static List<PortalColumnFilterSupport.ColumnFilter> parseFilters(Map<String, Map<String, Object>> raw) {
        return PortalColumnFilterSupport.parseFilters(raw, COLUMNS);
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
