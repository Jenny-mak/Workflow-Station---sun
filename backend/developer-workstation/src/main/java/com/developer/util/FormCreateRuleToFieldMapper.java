package com.developer.util;

import com.developer.dto.FieldDefinitionRequest;
import com.developer.enums.DataType;
import com.platform.common.audit.SystemAuditFields;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps form-create rule nodes to {@link FieldDefinitionRequest} for Table Design provisioning.
 * Inverse of AI/table→rule helpers; layout / subTable / lookup widgets are skipped.
 */
public final class FormCreateRuleToFieldMapper {

    private static final Set<String> SKIP_TYPES = Set.of(
            "subTable", "inlineSubForm", "linkForm", "elCard", "el-card", "card",
            "elRow", "el-row", "row", "elCol", "el-col", "col",
            "group", "tableForm", "tableFormColumn", "divider", "html", "text", "button"
    );

    private FormCreateRuleToFieldMapper() {
    }

    /**
     * Collect unique field definitions from a rule tree (document order).
     */
    public static List<FieldDefinitionRequest> fromRules(Object ruleNode) {
        Map<String, FieldDefinitionRequest> byName = new LinkedHashMap<>();
        walk(ruleNode, byName);
        return new ArrayList<>(byName.values());
    }

    /**
     * Build VARCHAR fields from bare field-name sets (e.g. subListViews columns without rule types).
     */
    public static List<FieldDefinitionRequest> fromFieldNames(Iterable<String> fieldNames) {
        Map<String, FieldDefinitionRequest> byName = new LinkedHashMap<>();
        int sort = 0;
        for (String name : fieldNames) {
            if (!isProvisionableFieldName(name) || byName.containsKey(name)) {
                continue;
            }
            byName.put(name, FieldDefinitionRequest.builder()
                    .fieldName(name)
                    .dataType(DataType.VARCHAR)
                    .length(255)
                    .nullable(true)
                    .displayName(name)
                    .sortOrder(sort++)
                    .build());
        }
        return new ArrayList<>(byName.values());
    }

    public static boolean isProvisionableFieldName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.startsWith("linkForm:") || trimmed.startsWith("__")) {
            return false;
        }
        return !SystemAuditFields.isAuditField(trimmed);
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object ruleNode, Map<String, FieldDefinitionRequest> byName) {
        if (ruleNode instanceof List<?> list) {
            list.forEach(n -> walk(n, byName));
            return;
        }
        if (!(ruleNode instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> node = (Map<String, Object>) raw;
        String type = node.get("type") == null ? "" : String.valueOf(node.get("type"));
        if (!SKIP_TYPES.contains(type) && node.get("field") instanceof String field
                && isProvisionableFieldName(field) && !byName.containsKey(field)) {
            byName.put(field, toFieldRequest(node, field, type, byName.size()));
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            list.forEach(c -> walk(c, byName));
        }
        if (node.get("props") instanceof Map<?, ?> props) {
            for (String key : List.of("children", "list", "items", "fields")) {
                Object nested = ((Map<String, Object>) props).get(key);
                if (nested instanceof List<?> list) {
                    list.forEach(c -> walk(c, byName));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static FieldDefinitionRequest toFieldRequest(
            Map<String, Object> node, String field, String type, int sortOrder) {
        Map<String, Object> props = node.get("props") instanceof Map<?, ?> p
                ? (Map<String, Object>) p
                : Map.of();
        DataType dataType = mapDataType(type, props);
        Integer length = dataType == DataType.VARCHAR ? resolveLength(props) : null;
        String title = node.get("title") instanceof String t && !t.isBlank() ? t : field;
        boolean required = isRequired(node);
        return FieldDefinitionRequest.builder()
                .fieldName(field)
                .dataType(dataType)
                .length(length)
                .nullable(!required)
                .displayName(title)
                .sortOrder(sortOrder)
                .build();
    }

    private static DataType mapDataType(String type, Map<String, Object> props) {
        String t = type == null ? "" : type;
        String propsType = props.get("type") == null ? "" : String.valueOf(props.get("type"));
        return switch (t) {
            case "inputNumber" -> DataType.DECIMAL;
            case "switch" -> DataType.BOOLEAN;
            case "datePicker" -> "datetime".equalsIgnoreCase(propsType) ? DataType.TIMESTAMP
                    : "time".equalsIgnoreCase(propsType) ? DataType.TIME : DataType.DATE;
            case "timePicker" -> DataType.TIME;
            case "upload", "advancedUpload" -> DataType.FILE;
            case "input" -> "textarea".equalsIgnoreCase(propsType) || "password".equalsIgnoreCase(propsType)
                    ? ("textarea".equalsIgnoreCase(propsType) ? DataType.TEXT : DataType.VARCHAR)
                    : DataType.VARCHAR;
            case "editor" -> DataType.TEXT;
            case "select", "radio", "checkbox", "cascader", "treeSelect", "elTreeSelect",
                 "userSelect", "user", "departmentSelect", "department", "lookup", "owner",
                 "colorPicker", "rate", "slider", "signature", "transfer", "treeselect" -> DataType.VARCHAR;
            default -> DataType.VARCHAR;
        };
    }

    private static Integer resolveLength(Map<String, Object> props) {
        Object max = props.get("maxlength");
        if (max instanceof Number n && n.intValue() > 0) {
            return Math.min(n.intValue(), 4000);
        }
        return 255;
    }

    @SuppressWarnings("unchecked")
    private static boolean isRequired(Map<String, Object> node) {
        Object validate = node.get("validate");
        if (!(validate instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> rule = (Map<String, Object>) raw;
            if (Boolean.TRUE.equals(rule.get("required"))) {
                return true;
            }
            Object mode = rule.get("mode");
            if (mode != null && "required".equalsIgnoreCase(String.valueOf(mode))) {
                return true;
            }
        }
        return false;
    }

    /** Safe SQL-ish identifier fragment for generated table names. */
    public static String sanitizeTableNamePart(String raw) {
        if (raw == null || raw.isBlank()) {
            return "form";
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (cleaned.isBlank()) {
            return "form";
        }
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40);
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "t_" + cleaned;
        }
        return cleaned;
    }
}
