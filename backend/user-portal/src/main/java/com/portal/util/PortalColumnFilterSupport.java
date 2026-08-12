package com.portal.util;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
/**
 * Shared helpers for Portal list column filters / sort / groupBy (MTV-shaped JSON).
 *
 * <p>Filter JSON: {@code {"field":{"operator":"contains","value":"x"},...}}
 */
public final class PortalColumnFilterSupport {

    private PortalColumnFilterSupport() {
    }

    public record ColumnFilter(String field, String operator, String value) {
    }

    /**
     * Sanitize groupBy against whitelist; blank / unknown → null.
     */
    public static String sanitizeGroupBy(String groupBy, Set<String> allowed) {
        if (groupBy == null || groupBy.isBlank() || allowed == null) {
            return null;
        }
        String field = groupBy.trim();
        return allowed.contains(field) ? field : null;
    }

    /**
     * Parse map-shaped filters with whitelist only (identity field mapping).
     * Callers that need FE aliases should map before/inside their own parseFilters.
     */
    public static List<ColumnFilter> parseFilters(Map<String, Map<String, Object>> raw, Set<String> whitelist) {
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
            if (whitelist == null || !whitelist.contains(feField)) {
                continue;
            }
            out.add(new ColumnFilter(feField, operator, value));
        }
        return out;
    }

    public static Pageable withSort(
            Pageable pageable,
            String sortField,
            String sortDirection,
            String groupBy,
            Set<String> sortFields,
            String defaultSortField,
            Sort.Direction defaultDirection) {
        int page = pageable != null ? pageable.getPageNumber() : 0;
        int size = pageable != null ? pageable.getPageSize() : 20;
        return PageRequest.of(
                Math.max(0, page),
                Math.max(1, size),
                resolveSort(sortField, sortDirection, groupBy, sortFields, defaultSortField, defaultDirection));
    }

    public static Sort resolveSort(
            String sortField,
            String sortDirection,
            String groupBy,
            Set<String> sortFields,
            String defaultSortField,
            Sort.Direction defaultDirection) {
        Sort runtime = resolveRuntimeSort(sortField, sortDirection, sortFields, defaultSortField, defaultDirection);
        String safeGroup = sanitizeGroupBy(groupBy, sortFields);
        if (safeGroup == null) {
            return runtime;
        }
        return Sort.by(Sort.Direction.ASC, safeGroup).and(runtime);
    }

    private static Sort resolveRuntimeSort(
            String sortField,
            String sortDirection,
            Set<String> sortFields,
            String defaultSortField,
            Sort.Direction defaultDirection) {
        String field = sortField != null ? sortField.trim() : "";
        if (field.isEmpty() || sortFields == null || !sortFields.contains(field)) {
            return Sort.by(defaultDirection != null ? defaultDirection : Sort.Direction.DESC, defaultSortField);
        }
        Sort.Direction dir = "ASC".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        if (sortDirection == null || sortDirection.isBlank()) {
            dir = defaultSortField.equals(field)
                    ? (defaultDirection != null ? defaultDirection : Sort.Direction.DESC)
                    : Sort.Direction.ASC;
        }
        return Sort.by(dir, field);
    }

    /**
     * Text operators on a string-like JPA path (coalesce + lower).
     */
    public static Predicate textOperator(
            Root<?> root, CriteriaBuilder cb, String field, String op, String value) {
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

    /**
     * Date/time path: only isNull / isNotNull (value compares skipped — same as applications).
     */
    public static Predicate dateNullOperator(Root<?> root, CriteriaBuilder cb, String field, String op) {
        Path<Object> path = root.get(field);
        if ("isNull".equals(op)) {
            return cb.isNull(path);
        }
        if ("isNotNull".equals(op)) {
            return cb.isNotNull(path);
        }
        return null;
    }

    public static Expression<String> lowerCoalesce(Root<?> root, CriteriaBuilder cb, String field) {
        return cb.lower(cb.coalesce(root.get(field).as(String.class), cb.literal("")));
    }

    public static Predicate isBlank(Root<?> root, CriteriaBuilder cb, String field) {
        Path<?> path = root.get(field);
        Expression<String> asText = path.as(String.class);
        return cb.or(cb.isNull(path), cb.equal(cb.trim(asText), ""));
    }

    public static Predicate isNotBlank(Root<?> root, CriteriaBuilder cb, String field) {
        Path<?> path = root.get(field);
        Expression<String> asText = path.as(String.class);
        return cb.and(cb.isNotNull(path), cb.notEqual(cb.trim(asText), ""));
    }

    public static String escapeLike(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Coerce loosely typed filter map values into {@code Map<field, Map<operator|value, Object>>}.
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

    /**
     * Parse filters JSON string (map or array shape) — same as ProcessController applications.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, Object>> parseFiltersJson(String filtersJson, Object objectMapper) {
        if (filtersJson == null || filtersJson.isBlank() || objectMapper == null) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    (com.fasterxml.jackson.databind.ObjectMapper) objectMapper;
            Object parsed = mapper.readValue(filtersJson, Object.class);
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) {
                        continue;
                    }
                    Object field = map.containsKey("fieldName") ? map.get("fieldName") : map.get("field");
                    if (field == null) {
                        continue;
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    if (map.get("operator") != null) {
                        body.put("operator", map.get("operator"));
                    }
                    if (map.containsKey("value")) {
                        body.put("value", map.get("value"));
                    }
                    out.put(String.valueOf(field), body);
                }
                return out;
            }
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> loose = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null) {
                        loose.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                return coerceFilterMap(loose);
            }
            return Map.of();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid filters JSON: " + ex.getMessage(), ex);
        }
    }

    /**
     * COUNT grouped on whitelist field for the same filtered Specification (full set, not page).
     */
    public static <T> Map<String, Long> computeGroupCounts(
            EntityManager entityManager, Class<T> entityClass, Specification<T> spec, String groupBy) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<T> root = cq.from(entityClass);
        Expression<?> groupExpr = root.get(groupBy);
        cq.multiselect(groupExpr, cb.count(root));
        Predicate predicate = spec.toPredicate(root, cq, cb);
        if (predicate != null) {
            cq.where(predicate);
        }
        cq.groupBy(groupExpr);
        cq.orderBy(cb.asc(groupExpr));
        List<Object[]> rows = entityManager.createQuery(cq).getResultList();
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String label = groupLabel(row[0]);
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            out.merge(label, count, Long::sum);
        }
        return out;
    }

    public static String groupLabel(Object value) {
        if (value == null) {
            return "—";
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "—" : text;
    }

    /**
     * In-memory text match for fallback list filtering (approvals when Spec is impractical).
     */
    public static boolean matchesText(String raw, String op, String value) {
        String left = raw != null ? raw : "";
        String right = value != null ? value : "";
        return switch (op) {
            case "isNull" -> left.isBlank();
            case "isNotNull" -> !left.isBlank();
            case "eq" -> left.equalsIgnoreCase(right);
            case "ne" -> !left.equalsIgnoreCase(right);
            case "contains" -> left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
            case "notContains" -> !left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
            case "startsWith" -> left.toLowerCase(Locale.ROOT).startsWith(right.toLowerCase(Locale.ROOT));
            case "endsWith" -> left.toLowerCase(Locale.ROOT).endsWith(right.toLowerCase(Locale.ROOT));
            default -> false; // unknown operator: do not match
        };
    }
}
