package com.developer.service.impl;

import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiValidationResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 生成数据的引用完整性与唯一性校验协作类
 * <p>
 * 负责跨实体的引用完整性（外键、表绑定、表关系指向的表是否存在）与各类名称的唯一性校验。
 * 由 {@link AiValidationServiceImpl} 门面委托调用，校验规则逻辑与原实现逐字保持一致。
 */
@Component
public class AiReferenceValidator {

    void validateReferenceIntegrity(AiGeneratedData generatedData, AiValidationResult result) {
        validateReferenceIntegrity(generatedData, result, Map.of());
    }

    /**
     * @param existingTableFields 功能单元里已存在的表（表名 → 字段名），作为 scoped 提案的引用兜底目录：
     *                            FORMS / TABLE_RELATIONS 提案不含 tableDefinitions，表单绑定与关系引用的
     *                            却是库里的表；提案自带的同名表定义优先
     */
    @SuppressWarnings("unchecked")
    void validateReferenceIntegrity(AiGeneratedData generatedData, AiValidationResult result,
                                    Map<String, Set<String>> existingTableFields) {
        List<Map<String, Object>> tables = generatedData.getTableDefinitions();
        if (tables == null) tables = List.of();

        // Build lookup: tableName -> table map, and tableName -> set of fieldNames
        Map<String, Map<String, Object>> tableMap = new HashMap<>();
        Map<String, Set<String>> tableFieldMap = new HashMap<>();
        if (existingTableFields != null) {
            for (Map.Entry<String, Set<String>> e : existingTableFields.entrySet()) {
                if (e.getKey() == null) continue;
                tableMap.put(e.getKey(), Map.of("tableName", e.getKey()));
                tableFieldMap.put(e.getKey(), e.getValue() != null ? new HashSet<>(e.getValue()) : new HashSet<>());
            }
        }
        for (Map<String, Object> table : tables) {
            String tableName = (String) table.get("tableName");
            if (tableName != null) {
                tableMap.put(tableName, table);
                Set<String> fieldNames = new HashSet<>();
                List<Map<String, Object>> fields = (List<Map<String, Object>>) table.get("fieldDefinitions");
                if (fields == null) {
                    fields = (List<Map<String, Object>>) table.get("fields");
                }
                if (fields != null) {
                    for (Map<String, Object> field : fields) {
                        String fn = (String) field.get("fieldName");
                        if (fn != null) fieldNames.add(fn);
                    }
                }
                tableFieldMap.put(tableName, fieldNames);
            }
        }

        // Validate ForeignKey references
        for (int i = 0; i < tables.size(); i++) {
            Map<String, Object> table = tables.get(i);
            List<Map<String, Object>> foreignKeys = (List<Map<String, Object>>) table.get("foreignKeys");
            if (foreignKeys == null) continue;
            for (int j = 0; j < foreignKeys.size(); j++) {
                Map<String, Object> fk = foreignKeys.get(j);
                String refTableName = (String) fk.get("refTableName");
                String refFieldName = (String) fk.get("refFieldName");
                String fkPath = "tableDefinitions[" + i + "].foreignKeys[" + j + "]";

                if (refTableName != null && !tableMap.containsKey(refTableName)) {
                    result.addError("REFERENCE_INTEGRITY", fkPath + ".refTableName",
                        "Referenced table '" + refTableName + "' does not exist");
                }
                if (refTableName != null && refFieldName != null && tableFieldMap.containsKey(refTableName)) {
                    if (!tableFieldMap.get(refTableName).contains(refFieldName)) {
                        result.addError("REFERENCE_INTEGRITY", fkPath + ".refFieldName",
                            "Referenced field '" + refFieldName + "' does not exist in table '" + refTableName + "'");
                    }
                }
            }
        }

        // Validate FormTableBinding references
        List<Map<String, Object>> forms = generatedData.getFormDefinitions();
        if (forms != null) {
            for (int i = 0; i < forms.size(); i++) {
                List<Map<String, Object>> bindings = (List<Map<String, Object>>) forms.get(i).get("tableBindings");
                if (bindings != null) {
                    for (int j = 0; j < bindings.size(); j++) {
                        String tableName = (String) bindings.get(j).get("tableName");
                        if (tableName != null && !tableMap.containsKey(tableName)) {
                            result.addError("REFERENCE_INTEGRITY",
                                "formDefinitions[" + i + "].tableBindings[" + j + "].tableName",
                                "Referenced table '" + tableName + "' does not exist");
                        }
                    }
                } else {
                    // Fallback: check "bindingTableId" at form level
                    String bindingTableId = (String) forms.get(i).get("bindingTableId");
                    if (bindingTableId != null && !tableMap.containsKey(bindingTableId)) {
                        result.addError("REFERENCE_INTEGRITY",
                            "formDefinitions[" + i + "].bindingTableId",
                            "Referenced table '" + bindingTableId + "' does not exist");
                    }
                }
            }
        }

        // Validate TableRelation references
        List<Map<String, Object>> tableRelations = generatedData.getTableRelations();
        if (tableRelations != null) {
            for (int i = 0; i < tableRelations.size(); i++) {
                Map<String, Object> relation = tableRelations.get(i);
                String sourceTableName = (String) relation.get("sourceTableName");
                String targetTableName = (String) relation.get("targetTableName");
                String relPath = "tableRelations[" + i + "]";

                if (sourceTableName != null && !tableMap.containsKey(sourceTableName)) {
                    result.addError("REFERENCE_INTEGRITY", relPath + ".sourceTableName",
                            "Referenced table '" + sourceTableName + "' does not exist");
                }
                if (targetTableName != null && !tableMap.containsKey(targetTableName)) {
                    result.addError("REFERENCE_INTEGRITY", relPath + ".targetTableName",
                            "Referenced table '" + targetTableName + "' does not exist");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    void validateUniqueness(AiGeneratedData generatedData, AiValidationResult result) {
        // tableName uniqueness
        checkUniqueness(generatedData.getTableDefinitions(), "tableName", "tableDefinitions", result);
        // formName uniqueness
        checkUniqueness(generatedData.getFormDefinitions(), "formName", "formDefinitions", result);
        // actionName uniqueness
        checkUniqueness(generatedData.getActionDefinitions(), "actionName", "actionDefinitions", result);
        // decisionKey uniqueness
        checkUniqueness(generatedData.getDecisionDefinitions(), "decisionKey", "decisionDefinitions", result);

        // fieldName uniqueness per table
        List<Map<String, Object>> tables = generatedData.getTableDefinitions();
        if (tables != null) {
            for (int i = 0; i < tables.size(); i++) {
                List<Map<String, Object>> fields = (List<Map<String, Object>>) tables.get(i).get("fieldDefinitions");
                if (fields == null) {
                    fields = (List<Map<String, Object>>) tables.get(i).get("fields");
                }
                if (fields != null) {
                    Set<String> seen = new HashSet<>();
                    for (int j = 0; j < fields.size(); j++) {
                        String fieldName = (String) fields.get(j).get("fieldName");
                        if (fieldName != null && !seen.add(fieldName)) {
                            result.addError("UNIQUENESS", "tableDefinitions[" + i + "].fieldDefinitions[" + j + "].fieldName",
                                "Duplicate field name: " + fieldName);
                        }
                    }
                }
            }
        }
    }

    private void checkUniqueness(List<Map<String, Object>> items, String nameField, String arrayPath, AiValidationResult result) {
        if (items == null) return;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String name = (String) items.get(i).get(nameField);
            if (name != null && !seen.add(name)) {
                result.addError("UNIQUENESS", arrayPath + "[" + i + "]." + nameField, "Duplicate name: " + name);
            }
        }
    }
}
