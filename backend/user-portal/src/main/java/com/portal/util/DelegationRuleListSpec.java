package com.portal.util;

import com.portal.entity.DelegationRule;
import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
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
 *
 * <p>{@link #COLUMNS} is the single declaration the whitelists, the filter predicates and
 * the column-meta endpoint all derive from.
 */
public final class DelegationRuleListSpec {

    public static final List<PortalListColumnMeta> COLUMNS = List.of(
            PortalListColumnMeta.user("delegateId"),
            PortalListColumnMeta.enumOf("delegationType", DelegationType.class),
            PortalListColumnMeta.datetime("startTime"),
            PortalListColumnMeta.datetime("endTime"),
            PortalListColumnMeta.enumOf("status", DelegationStatus.class),
            PortalListColumnMeta.text("reason"),
            PortalListColumnMeta.sortOnly("createdAt", PortalListColumnMeta.Kind.DATETIME),
            PortalListColumnMeta.sortOnly("updatedAt", PortalListColumnMeta.Kind.DATETIME));

    public static final Set<String> SORT_FIELDS = PortalListColumnMeta.sortFields(COLUMNS);

    public static final Set<String> GROUP_FIELDS = PortalListColumnMeta.groupFields(COLUMNS);

    public static final Set<String> FILTER_FIELDS = PortalListColumnMeta.filterFields(COLUMNS);

    private DelegationRuleListSpec() {
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

    public static Specification<DelegationRule> build(
            String delegatorId, List<PortalColumnFilterSupport.ColumnFilter> filters) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("delegatorId"), delegatorId));
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
