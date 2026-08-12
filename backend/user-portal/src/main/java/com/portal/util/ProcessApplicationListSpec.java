package com.portal.util;

import com.portal.entity.ProcessInstance;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds JPA {@link Specification} + whitelist {@link Sort} for Portal "My Applications".
 *
 * <p>Filter JSON shape (same as MTV / portal list grid):
 * {@code {"field":{"operator":"contains","value":"x"},...}}
 */
public final class ProcessApplicationListSpec {

    public static final Set<String> SORT_FIELDS = Set.of(
            "startTime", "status", "businessKey", "currentNode", "currentAssignee", "processDefinitionName");

    /** Same whitelist as {@link #SORT_FIELDS} — used for groupBy primary ordering + groupCounts. */
    public static final Set<String> GROUP_FIELDS = SORT_FIELDS;

    public static final Set<String> FILTER_FIELDS = Set.of(
            "businessKey", "processDefinitionName", "currentNode", "currentAssignee", "status", "title", "id");

    private static final Set<String> KEYWORD_FIELDS = Set.of(
            "businessKey", "processDefinitionName", "currentNode", "currentAssignee", "title", "id");

    private ProcessApplicationListSpec() {
    }

    /**
     * Sanitize groupBy against whitelist; blank / unknown → null.
     */
    public static String sanitizeGroupBy(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            return null;
        }
        String field = groupBy.trim();
        return GROUP_FIELDS.contains(field) ? field : null;
    }

    /**
     * Column filter after FE alias mapping ({@code requestId}, {@code currentStepName}).
     */
    public record ColumnFilter(String field, String operator, String value) {
    }

    /**
     * Parse map-shaped filters JSON object into whitelist filters (aliases applied).
     * Unknown fields / empty value (except isNull/isNotNull) are skipped.
     */
    public static List<ColumnFilter> parseFilters(Map<String, Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ColumnFilter> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String feField = e.getKey().trim();
            Map<String, Object> body = e.getValue();
            Object opObj = body.get("operator");
            String operator = opObj != null ? String.valueOf(opObj).trim() : "";
            if (operator.isEmpty()) {
                continue;
            }
            Object valObj = body.get("value");
            String value = valObj != null ? String.valueOf(valObj) : "";
            if (!"isNull".equals(operator) && !"isNotNull".equals(operator) && value.isBlank()) {
                continue;
            }
            ColumnFilter mapped = mapFeField(feField, operator, value);
            if (mapped != null) {
                out.add(mapped);
            }
        }
        return out;
    }

    /**
     * Map FE column ids → entity fields. {@code requestId} → best-effort id/businessKey contains;
     * {@code currentStepName} → {@code currentNode}; {@code startTime} only isNull/isNotNull.
     */
    static ColumnFilter mapFeField(String feField, String operator, String value) {
        if ("requestId".equals(feField)) {
            // Best-effort: treat as contains across id + businessKey regardless of requested op
            // except isNull / isNotNull which still apply to both.
            return new ColumnFilter("requestId", operator, value);
        }
        if ("currentStepName".equals(feField)) {
            return new ColumnFilter("currentNode", operator, value);
        }
        if ("startTime".equals(feField)) {
            if ("isNull".equals(operator) || "isNotNull".equals(operator)) {
                return new ColumnFilter("startTime", operator, value);
            }
            // Value compare on timestamps is hard / ambiguous from text grid filters — skip.
            return null;
        }
        if (!FILTER_FIELDS.contains(feField)) {
            return null;
        }
        return new ColumnFilter(feField, operator, value);
    }

    public static Pageable withSort(Pageable pageable, String sortField, String sortDirection) {
        return withSort(pageable, sortField, sortDirection, null);
    }

    /**
     * When {@code groupBy} is whitelisted, sort by that field first (ASC), then runtime sort.
     */
    public static Pageable withSort(Pageable pageable, String sortField, String sortDirection, String groupBy) {
        int page = pageable != null ? pageable.getPageNumber() : 0;
        int size = pageable != null ? pageable.getPageSize() : 20;
        return PageRequest.of(Math.max(0, page), Math.max(1, size), resolveSort(sortField, sortDirection, groupBy));
    }

    public static Sort resolveSort(String sortField, String sortDirection) {
        return resolveSort(sortField, sortDirection, null);
    }

    public static Sort resolveSort(String sortField, String sortDirection, String groupBy) {
        Sort runtime = resolveRuntimeSort(sortField, sortDirection);
        String safeGroup = sanitizeGroupBy(groupBy);
        if (safeGroup == null) {
            return runtime;
        }
        // Group primary ASC (parity with MTV / portal grid); then existing runtime sort.
        return Sort.by(Sort.Direction.ASC, safeGroup).and(runtime);
    }

    private static Sort resolveRuntimeSort(String sortField, String sortDirection) {
        String field = sortField != null ? sortField.trim() : "";
        if (field.isEmpty() || !SORT_FIELDS.contains(field)) {
            return Sort.by(Sort.Direction.DESC, "startTime");
        }
        Sort.Direction dir = "ASC".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        // Default direction for startTime when blank direction remains DESC (handled above when field blank).
        if (sortDirection == null || sortDirection.isBlank()) {
            dir = "startTime".equals(field) ? Sort.Direction.DESC : Sort.Direction.ASC;
        }
        return Sort.by(dir, field);
    }

    public static Specification<ProcessInstance> build(
            String userId, String status, String keyword, List<ColumnFilter> filters) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("startUserId"), userId));

            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status.trim()));
            }

            if (keyword != null && !keyword.isBlank()) {
                predicates.add(buildKeywordPredicate(root, cb, keyword.trim()));
            }

            if (filters != null) {
                for (ColumnFilter filter : filters) {
                    Predicate p = buildFilterPredicate(root, cb, filter);
                    if (p != null) {
                        predicates.add(p);
                    }
                }
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate buildKeywordPredicate(Root<ProcessInstance> root, CriteriaBuilder cb, String keyword) {
        String pattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
        List<Predicate> ors = new ArrayList<>();
        for (String field : KEYWORD_FIELDS) {
            ors.add(cb.like(lowerCoalesce(root, cb, field), pattern, '\\'));
        }
        return cb.or(ors.toArray(Predicate[]::new));
    }

    private static Predicate buildFilterPredicate(
            Root<ProcessInstance> root, CriteriaBuilder cb, ColumnFilter filter) {
        if (filter == null || filter.field() == null || filter.operator() == null) {
            return null;
        }
        String op = filter.operator().trim();
        String value = filter.value() != null ? filter.value() : "";

        if ("requestId".equals(filter.field())) {
            // Best-effort: apply operator across id OR businessKey (contains-family default).
            Predicate onId = textOperator(root, cb, "id", normalizeRequestIdOp(op), value);
            Predicate onBk = textOperator(root, cb, "businessKey", normalizeRequestIdOp(op), value);
            if (onId == null || onBk == null) {
                return null;
            }
            if ("isNull".equals(op)) {
                return cb.and(onId, onBk);
            }
            if ("ne".equals(op) || "notContains".equals(op)) {
                return cb.and(onId, onBk);
            }
            return cb.or(onId, onBk);
        }

        if ("startTime".equals(filter.field())) {
            Path<Object> path = root.get("startTime");
            if ("isNull".equals(op)) {
                return cb.isNull(path);
            }
            if ("isNotNull".equals(op)) {
                return cb.isNotNull(path);
            }
            return null;
        }

        return textOperator(root, cb, filter.field(), op, value);
    }

    private static Predicate textOperator(
            Root<ProcessInstance> root, CriteriaBuilder cb, String field, String op, String value) {
        return switch (op) {
            case "isNull" -> isBlank(root, cb, field);
            case "isNotNull" -> isNotBlank(root, cb, field);
            case "eq" -> cb.equal(lowerCoalesce(root, cb, field), value.toLowerCase(Locale.ROOT));
            case "ne" -> cb.notEqual(lowerCoalesce(root, cb, field), value.toLowerCase(Locale.ROOT));
            case "contains" -> cb.like(
                    lowerCoalesce(root, cb, field),
                    "%" + escapeLike(value.toLowerCase(Locale.ROOT)) + "%",
                    '\\');
            case "notContains" -> cb.notLike(
                    lowerCoalesce(root, cb, field),
                    "%" + escapeLike(value.toLowerCase(Locale.ROOT)) + "%",
                    '\\');
            case "startsWith" -> cb.like(
                    lowerCoalesce(root, cb, field),
                    escapeLike(value.toLowerCase(Locale.ROOT)) + "%",
                    '\\');
            case "endsWith" -> cb.like(
                    lowerCoalesce(root, cb, field),
                    "%" + escapeLike(value.toLowerCase(Locale.ROOT)),
                    '\\');
            default -> null;
        };
    }

    /** Unknown requestId ops fall back to contains (grid search UX). */
    private static String normalizeRequestIdOp(String op) {
        return switch (op) {
            case "eq", "ne", "contains", "notContains", "startsWith", "endsWith", "isNull", "isNotNull" -> op;
            default -> "contains";
        };
    }

    private static Expression<String> lowerCoalesce(
            Root<ProcessInstance> root, CriteriaBuilder cb, String field) {
        return cb.lower(cb.coalesce(root.get(field).as(String.class), cb.literal("")));
    }

    private static Predicate isBlank(Root<ProcessInstance> root, CriteriaBuilder cb, String field) {
        Path<String> path = root.get(field);
        return cb.or(cb.isNull(path), cb.equal(cb.trim(path), ""));
    }

    private static Predicate isNotBlank(Root<ProcessInstance> root, CriteriaBuilder cb, String field) {
        Path<String> path = root.get(field);
        return cb.and(cb.isNotNull(path), cb.notEqual(cb.trim(path), ""));
    }

    static String escapeLike(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Convenience for controllers: coerce loosely typed filter map values into
     * {@code Map<field, Map<operator|value, Object>>}.
     */
    public static Map<String, Map<String, Object>> coerceFilterMap(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : raw.entrySet()) {
            if (e.getKey() == null || !(e.getValue() instanceof Map<?, ?> body)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> be : body.entrySet()) {
                if (be.getKey() != null) {
                    copy.put(String.valueOf(be.getKey()), be.getValue());
                }
            }
            out.put(e.getKey(), copy);
        }
        return out;
    }
}
