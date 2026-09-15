package com.developer.service.impl;

import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiValidationResult;
import com.developer.enums.*;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 生成数据的结构校验协作类
 * <p>
 * 负责枚举值、字段约束、表/表单/动作/决策定义、表关系、引用完整性、唯一性等结构性校验。
 * DMN XML 安全校验委托 {@link AiSecurityValidator}。
 * 由 {@link AiValidationServiceImpl} 门面委托调用，校验规则逻辑与原实现逐字保持一致。
 */
@Component
public class AiStructureValidator {

    private final AiSecurityValidator securityValidator;

    public AiStructureValidator(AiSecurityValidator securityValidator) {
        this.securityValidator = securityValidator;
    }

    @SuppressWarnings("unchecked")
    void validateTableDefinitions(List<Map<String, Object>> tableDefinitions, AiValidationResult result) {
        if (tableDefinitions == null) return;
        for (int i = 0; i < tableDefinitions.size(); i++) {
            Map<String, Object> table = tableDefinitions.get(i);
            validateEnumValue(table.get("tableType"), TableType.class,
                    "tableDefinitions[" + i + "].tableType", result);

            // Support both "fieldDefinitions" and "fields" key names
            List<Map<String, Object>> fields = (List<Map<String, Object>>) table.get("fieldDefinitions");
            if (fields == null) {
                fields = (List<Map<String, Object>>) table.get("fields");
            }
            if (fields != null) {
                boolean hasPrimaryKey = false;
                for (int j = 0; j < fields.size(); j++) {
                    Map<String, Object> field = fields.get(j);
                    String fieldPath = "tableDefinitions[" + i + "].fieldDefinitions[" + j + "]";

                    validateEnumValue(field.get("dataType"), DataType.class,
                            fieldPath + ".dataType", result);

                    String dataType = field.get("dataType") != null ? field.get("dataType").toString() : null;

                    if ("DECIMAL".equals(dataType)) {
                        if (field.get("precision") == null || toInt(field.get("precision")) <= 0) {
                            result.addError("FIELD_CONSTRAINT", fieldPath + ".precision",
                                    "DECIMAL type requires precision > 0");
                        }
                        if (field.get("scale") == null || toInt(field.get("scale")) < 0) {
                            result.addError("FIELD_CONSTRAINT", fieldPath + ".scale",
                                    "DECIMAL type requires scale >= 0");
                        }
                    }

                    if ("VARCHAR".equals(dataType)) {
                        if (field.get("length") == null || toInt(field.get("length")) <= 0) {
                            result.addError("FIELD_CONSTRAINT", fieldPath + ".length",
                                    "VARCHAR type requires length > 0");
                        }
                    }

                    Boolean isPrimaryKey = (Boolean) field.get("isPrimaryKey");
                    if (isPrimaryKey == null) {
                        isPrimaryKey = (Boolean) field.get("primaryKey");
                    }
                    if (Boolean.TRUE.equals(isPrimaryKey)) {
                        hasPrimaryKey = true;
                    }
                }
                if (!hasPrimaryKey) {
                    result.addError("FIELD_CONSTRAINT", "tableDefinitions[" + i + "]",
                            "Table definition must contain at least one primary key field");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    void validateFormDefinitions(List<Map<String, Object>> formDefinitions, AiValidationResult result) {
        if (formDefinitions == null) return;
        for (int i = 0; i < formDefinitions.size(); i++) {
            Map<String, Object> form = formDefinitions.get(i);
            String formPath = "formDefinitions[" + i + "]";

            // Task 4.6: 旧版 FormType 校验兼容（产生警告而非错误）
            String formTypeStr = (String) form.get("formType");
            if (formTypeStr != null) {
                try {
                    FormType.valueOf(formTypeStr);
                } catch (IllegalArgumentException e) {
                    if ("MAIN".equals(formTypeStr) || "SUB".equals(formTypeStr)) {
                        String mapped = "MAIN".equals(formTypeStr) ? "PROCESS" : "TASK";
                        result.addWarning("DEPRECATED_ENUM", formPath + ".formType",
                                "Deprecated form type '" + formTypeStr + "', will be auto-mapped to '" + mapped + "'");
                    } else {
                        result.addError("INVALID_ENUM", formPath + ".formType",
                                "Invalid enum value: " + formTypeStr);
                    }
                }
            }

            List<Map<String, Object>> bindings = (List<Map<String, Object>>) form.get("tableBindings");
            // Skip binding validation if LLM used legacy format (bindingTableId)
            if (bindings != null) {
                for (int j = 0; j < bindings.size(); j++) {
                    Map<String, Object> binding = bindings.get(j);
                    String bindingPath = formPath + ".tableBindings[" + j + "]";
                    validateEnumValue(binding.get("bindingType"), BindingType.class,
                            bindingPath + ".bindingType", result);
                    validateEnumValue(binding.get("bindingMode"), BindingMode.class,
                            bindingPath + ".bindingMode", result);
                }
            }

            // Task 4.2: configJson 扩展字段校验
            Map<String, Object> configJson = (Map<String, Object>) form.get("configJson");
            validateConfigJsonExtensions(configJson, formPath, result);

            // Task 4.3: fieldPermissions 值校验
            Map<String, String> fieldPermissions = (Map<String, String>) form.get("fieldPermissions");
            if (fieldPermissions != null) {
                Set<String> validPermissions = Set.of("READONLY", "EDITABLE");
                for (Map.Entry<String, String> entry : fieldPermissions.entrySet()) {
                    if (!validPermissions.contains(entry.getValue())) {
                        result.addError("INVALID_ENUM",
                                formPath + ".fieldPermissions." + entry.getKey(),
                                "Invalid permission value: " + entry.getValue() + ", must be READONLY or EDITABLE");
                    }
                }
            }

            // Task 4.3: showLiveValues 类型校验
            Object showLiveValues = form.get("showLiveValues");
            if (showLiveValues != null && !(showLiveValues instanceof Boolean)) {
                result.addError("FIELD_CONSTRAINT", formPath + ".showLiveValues",
                        "showLiveValues must be a Boolean");
            }

            // Task 4.3: Task Form 缺少 fieldPermissions 警告
            if ("TASK".equals(formTypeStr) && (fieldPermissions == null || fieldPermissions.isEmpty())) {
                result.addWarning("BEST_PRACTICE", formPath + ".fieldPermissions",
                        "Task Form typically requires fieldPermissions configuration");
            }
        }
    }

    void validateActionDefinitions(List<Map<String, Object>> actionDefinitions, AiValidationResult result) {
        if (actionDefinitions == null) return;
        for (int i = 0; i < actionDefinitions.size(); i++) {
            Map<String, Object> action = actionDefinitions.get(i);
            validateEnumValue(action.get("actionType"), ActionType.class,
                    "actionDefinitions[" + i + "].actionType", result);

            // Task 4.4: visibilityCondition 格式校验
            @SuppressWarnings("unchecked")
            Map<String, Object> actionConfig = (Map<String, Object>) action.get("configJson");
            if (actionConfig != null) {
                Object visibilityCondition = actionConfig.get("visibilityCondition");
                if (visibilityCondition != null) {
                    if (visibilityCondition instanceof String) {
                        result.addError("FORMAT_MISMATCH",
                                "actionDefinitions[" + i + "].configJson.visibilityCondition",
                                "visibilityCondition must be a ConditionExpression object {field, operator, value}, not a string");
                    } else if (visibilityCondition instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> condition = (Map<String, Object>) visibilityCondition;
                        Set<String> validOperators = Set.of("equals", "not-equals", "contains",
                                "greater-than", "less-than", "is-empty", "is-not-empty");
                        String operator = (String) condition.get("operator");
                        if (operator != null && !validOperators.contains(operator)) {
                            result.addError("INVALID_ENUM",
                                    "actionDefinitions[" + i + "].configJson.visibilityCondition.operator",
                                    "Invalid operator: " + operator);
                        }
                    }
                }
            }
        }
    }

    /**
     * 校验 configJson 业务逻辑扩展字段
     * 检查 formulas/linkages/crossFieldRules/summaryRules 结构
     */
    @SuppressWarnings("unchecked")
    void validateConfigJsonExtensions(Map<String, Object> configJson, String formPath, AiValidationResult result) {
        if (configJson == null) return;

        // formulas 校验
        List<Map<String, Object>> formulas = (List<Map<String, Object>>) configJson.get("formulas");
        if (formulas != null) {
            for (int i = 0; i < formulas.size(); i++) {
                Map<String, Object> formula = formulas.get(i);
                String path = formPath + ".configJson.formulas[" + i + "]";
                String targetField = (String) formula.get("targetField");
                if (targetField == null || targetField.isBlank()) {
                    result.addError("FIELD_CONSTRAINT", path + ".targetField", "Formula targetField must not be empty");
                }
                String expression = (String) formula.get("expression");
                if (expression == null || expression.isBlank()) {
                    result.addError("FIELD_CONSTRAINT", path + ".expression", "Formula expression must not be empty");
                }
                Object dependsOn = formula.get("dependsOn");
                if (dependsOn == null || !(dependsOn instanceof List) || ((List<?>) dependsOn).isEmpty()) {
                    result.addError("FIELD_CONSTRAINT", path + ".dependsOn", "Formula dependsOn must be a non-empty array");
                }
            }
        }

        // linkages 校验
        Set<String> validLinkageTypes = Set.of("option-filtering", "value-auto-fill", "field-state-change");
        List<Map<String, Object>> linkages = (List<Map<String, Object>>) configJson.get("linkages");
        if (linkages != null) {
            for (int i = 0; i < linkages.size(); i++) {
                Map<String, Object> linkage = linkages.get(i);
                String path = formPath + ".configJson.linkages[" + i + "]";
                String sourceField = (String) linkage.get("sourceField");
                if (sourceField == null || sourceField.isBlank()) {
                    result.addError("FIELD_CONSTRAINT", path + ".sourceField", "Linkage sourceField must not be empty");
                }
                String targetField = (String) linkage.get("targetField");
                if (targetField == null || targetField.isBlank()) {
                    result.addError("FIELD_CONSTRAINT", path + ".targetField", "Linkage targetField must not be empty");
                }
                String linkageType = (String) linkage.get("linkageType");
                if (linkageType != null && !validLinkageTypes.contains(linkageType)) {
                    result.addError("INVALID_ENUM", path + ".linkageType", "Invalid linkage type: " + linkageType);
                }
            }
        }

        // crossFieldRules 校验
        List<Map<String, Object>> crossFieldRules = (List<Map<String, Object>>) configJson.get("crossFieldRules");
        if (crossFieldRules != null) {
            for (int i = 0; i < crossFieldRules.size(); i++) {
                Map<String, Object> rule = crossFieldRules.get(i);
                String path = formPath + ".configJson.crossFieldRules[" + i + "]";
                Object fields = rule.get("fields");
                if (fields == null || !(fields instanceof List) || ((List<?>) fields).isEmpty()) {
                    result.addError("FIELD_CONSTRAINT", path + ".fields", "CrossFieldRule fields must be a non-empty array");
                }
                String message = (String) rule.get("message");
                if (message == null || message.isBlank()) {
                    result.addError("FIELD_CONSTRAINT", path + ".message", "CrossFieldRule message must not be empty");
                }
                String targetField = (String) rule.get("targetField");
                if (targetField == null || targetField.isBlank()) {
                    result.addError("FIELD_CONSTRAINT", path + ".targetField", "CrossFieldRule targetField must not be empty");
                }
            }
        }

        // summaryRules 校验
        Set<String> validAggregations = Set.of("SUM", "AVG", "COUNT", "MIN", "MAX");
        List<Map<String, Object>> summaryRules = (List<Map<String, Object>>) configJson.get("summaryRules");
        if (summaryRules != null) {
            for (int i = 0; i < summaryRules.size(); i++) {
                Map<String, Object> rule = summaryRules.get(i);
                String path = formPath + ".configJson.summaryRules[" + i + "]";
                String sourceColumn = (String) rule.get("sourceColumn");
                if (sourceColumn == null || sourceColumn.isBlank()) {
                    result.addError("FIELD_CONSTRAINT", path + ".sourceColumn", "SummaryRule sourceColumn must not be empty");
                }
                String targetField = (String) rule.get("targetField");
                if (targetField == null || targetField.isBlank()) {
                    result.addError("FIELD_CONSTRAINT", path + ".targetField", "SummaryRule targetField must not be empty");
                }
                String aggregation = (String) rule.get("aggregation");
                if (aggregation != null && !validAggregations.contains(aggregation)) {
                    result.addError("INVALID_ENUM", path + ".aggregation", "Invalid aggregation: " + aggregation);
                }
            }
        }
    }

    /**
     * 校验决策定义数据
     * 检查 decisionKey 非空/长度、hitPolicy 合法值、dmnXml 安全性
     */
    void validateDecisionDefinitions(List<Map<String, Object>> decisionDefinitions, AiValidationResult result) {
        if (decisionDefinitions == null) return;

        Set<String> validHitPolicies = Set.of("FIRST", "UNIQUE", "PRIORITY", "ANY", "COLLECT", "RULE_ORDER", "OUTPUT_ORDER");

        for (int i = 0; i < decisionDefinitions.size(); i++) {
            Map<String, Object> decision = decisionDefinitions.get(i);
            String path = "decisionDefinitions[" + i + "]";

            // decisionKey 非空且长度限制
            String decisionKey = (String) decision.get("decisionKey");
            if (decisionKey == null || decisionKey.isBlank()) {
                result.addError("FIELD_CONSTRAINT", path + ".decisionKey", "decisionKey must not be empty");
            } else if (decisionKey.length() > 100) {
                result.addError("FIELD_CONSTRAINT", path + ".decisionKey", "decisionKey must not exceed 100 characters");
            }

            // hitPolicy 合法值
            String hitPolicy = (String) decision.get("hitPolicy");
            if (hitPolicy != null && !validHitPolicies.contains(hitPolicy)) {
                result.addError("INVALID_ENUM", path + ".hitPolicy", "Invalid hit policy: " + hitPolicy);
            }

            // dmnXml 安全校验
            String dmnXml = (String) decision.get("dmnXml");
            securityValidator.validateDmnXml(dmnXml, path, result);
        }
    }

    /**
     * 校验表关系数据
     * 检查 relationType 合法值、sourceFieldName/targetFieldName 非空、引用完整性
     */
    void validateTableRelations(AiGeneratedData generatedData, AiValidationResult result) {
        List<Map<String, Object>> tableRelations = generatedData.getTableRelations();
        if (tableRelations == null) return;

        Set<String> validRelationTypes = Set.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_MANY");

        // 构建表名集合用于引用完整性校验
        Set<String> tableNames = new HashSet<>();
        if (generatedData.getTableDefinitions() != null) {
            for (Map<String, Object> table : generatedData.getTableDefinitions()) {
                String name = (String) table.get("tableName");
                if (name != null) tableNames.add(name);
            }
        }

        for (int i = 0; i < tableRelations.size(); i++) {
            Map<String, Object> relation = tableRelations.get(i);
            String path = "tableRelations[" + i + "]";

            // relationType 合法值
            String relationType = (String) relation.get("relationType");
            if (relationType != null && !validRelationTypes.contains(relationType)) {
                result.addError("INVALID_ENUM", path + ".relationType", "Invalid relation type: " + relationType);
            }

            // sourceFieldName / targetFieldName 非空
            String sourceFieldName = (String) relation.get("sourceFieldName");
            if (sourceFieldName == null || sourceFieldName.isBlank()) {
                result.addError("FIELD_CONSTRAINT", path + ".sourceFieldName", "sourceFieldName must not be empty");
            }
            String targetFieldName = (String) relation.get("targetFieldName");
            if (targetFieldName == null || targetFieldName.isBlank()) {
                result.addError("FIELD_CONSTRAINT", path + ".targetFieldName", "targetFieldName must not be empty");
            }

            // 引用完整性：sourceTableName / targetTableName 必须存在于 tableDefinitions
            String sourceTableName = (String) relation.get("sourceTableName");
            if (sourceTableName != null && !tableNames.contains(sourceTableName)) {
                result.addError("REFERENCE_INTEGRITY", path + ".sourceTableName",
                        "Referenced table '" + sourceTableName + "' does not exist in tableDefinitions");
            }
            String targetTableName = (String) relation.get("targetTableName");
            if (targetTableName != null && !tableNames.contains(targetTableName)) {
                result.addError("REFERENCE_INTEGRITY", path + ".targetTableName",
                        "Referenced table '" + targetTableName + "' does not exist in tableDefinitions");
            }
        }
    }

    /** 连接切片里不由提案决定的键：凭证类是安全红线，主机/端口由 Admin Center 系统配置解析。 */
    private static final Set<String> CONNECTION_FORBIDDEN_KEYS = Set.of(
            "username", "password", "credential", "credentialencrypted", "oauthprovider",
            "oauthrefreshtokenencrypted", "oauthaccesstokenencrypted", "oauthscopes", "tokenexpiresat",
            "host", "port", "usetls", "imaphost", "imapport", "imapusessl", "connectionuid");

    /** 监控模板不允许携带的键（起始事件绑定与过滤条件在 Process Design 里另建副本）。 */
    private static final Set<String> MONITOR_TEMPLATE_FORBIDDEN_KEYS = Set.of(
            "starteventid", "filterfrom", "filtersubject", "processdefinitionkey", "sourceruleid", "ruleuid");

    private static final Set<String> EXTRACTION_SOURCES = Set.of(
            "SUBJECT", "FROM", "TO", "CC", "REPLY_TO", "DATE", "MESSAGE_ID", "TEXT", "HTML",
            "TEXT_AND_HTML", "ATTACHMENTS", "RAW_EML", "HEADER", "CONST");

    private static final Set<String> EXTRACTION_TYPES = Set.of(
            "DIRECT", "CONST", "LABEL", "BETWEEN", "REGEX", "HEADER");

    private static final java.util.regex.Pattern EMAIL_ADDRESS = java.util.regex.Pattern.compile(
            "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    void validateEmailTemplates(List<Map<String, Object>> templates, AiValidationResult result) {
        if (templates == null) return;
        for (int i = 0; i < templates.size(); i++) {
            Map<String, Object> t = templates.get(i);
            String path = "emailTemplates[" + i + "]";
            String name = t.get("name") instanceof String s ? s.trim() : null;
            if (name == null || name.isEmpty()) {
                result.addError("FIELD_CONSTRAINT", path + ".name", "name must not be empty");
            } else if (name.length() > 100) {
                result.addError("FIELD_CONSTRAINT", path + ".name", "name must not exceed 100 characters");
            }
            if (t.get("subject") instanceof String subject && subject.length() > 500) {
                result.addError("FIELD_CONSTRAINT", path + ".subject", "subject must not exceed 500 characters");
            }
            if (t.get("bodyHtml") != null && !(t.get("bodyHtml") instanceof String)) {
                result.addError("FIELD_CONSTRAINT", path + ".bodyHtml", "bodyHtml must be a string");
            } else {
                securityValidator.validateHtmlBody((String) t.get("bodyHtml"), path + ".bodyHtml", result);
            }
        }
    }

    void validateEmailConnections(List<Map<String, Object>> connections, AiValidationResult result) {
        if (connections == null) return;
        for (int i = 0; i < connections.size(); i++) {
            Map<String, Object> c = connections.get(i);
            String path = "emailConnections[" + i + "]";
            for (String key : c.keySet()) {
                if (CONNECTION_FORBIDDEN_KEYS.contains(key.toLowerCase())) {
                    // fail-closed：凭证与主机从不经由 AI 提案进入系统
                    result.addError("FORBIDDEN_FIELD", path + "." + key,
                            "Connection proposals must not carry credential or endpoint fields: " + key);
                }
            }
            String name = c.get("name") instanceof String s ? s.trim() : null;
            if (name == null || name.isEmpty()) {
                result.addError("FIELD_CONSTRAINT", path + ".name", "name (sender email address) must not be empty");
            } else if (!EMAIL_ADDRESS.matcher(name).matches()) {
                result.addError("FIELD_CONSTRAINT", path + ".name", "name must be a valid email address: " + name);
            }
            validateEnumValue(c.get("connectionType"), ConnectionType.class, path + ".connectionType", result);
            validateEnumValue(c.get("direction"), EmailConnectionDirection.class, path + ".direction", result);
            if ("BOTH".equals(c.get("direction"))) {
                result.addError("INVALID_ENUM", path + ".direction", "direction BOTH is no longer supported; use OUTBOUND or INBOUND");
            }
            if (c.get("fromName") instanceof String fromName && fromName.length() > 100) {
                result.addError("FIELD_CONSTRAINT", path + ".fromName", "fromName must not exceed 100 characters");
            }
        }
    }

    @SuppressWarnings("unchecked")
    void validateEmailMonitorRules(List<Map<String, Object>> rules, AiValidationResult result) {
        if (rules == null) return;
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> r = rules.get(i);
            String path = "emailMonitorRules[" + i + "]";
            for (String key : r.keySet()) {
                if (MONITOR_TEMPLATE_FORBIDDEN_KEYS.contains(key.toLowerCase())) {
                    result.addError("FORBIDDEN_FIELD", path + "." + key,
                            "Monitor proposals describe templates only; start-event binding and filters are not allowed: " + key);
                }
            }
            String name = r.get("name") instanceof String s ? s.trim() : null;
            if (name == null || name.isEmpty()) {
                result.addError("FIELD_CONSTRAINT", path + ".name", "name must not be empty");
            } else if (name.length() > 100) {
                result.addError("FIELD_CONSTRAINT", path + ".name", "name must not exceed 100 characters");
            }
            if (!(r.get("connectionName") instanceof String cn) || cn.isBlank()) {
                result.addError("FIELD_CONSTRAINT", path + ".connectionName", "connectionName must not be empty");
            }
            validateEnumValue(r.get("actionType"), EmailMonitorActionType.class, path + ".actionType", result);
            Object rulesObj = r.get("extractionRules");
            if (rulesObj != null && !(rulesObj instanceof Map)) {
                result.addError("FIELD_CONSTRAINT", path + ".extractionRules", "extractionRules must be an object");
            } else if (rulesObj instanceof Map<?, ?> er && er.get("fields") != null) {
                if (!(er.get("fields") instanceof List<?> fields)) {
                    result.addError("FIELD_CONSTRAINT", path + ".extractionRules.fields", "fields must be an array");
                } else {
                    for (int j = 0; j < fields.size(); j++) {
                        String fp = path + ".extractionRules.fields[" + j + "]";
                        if (!(fields.get(j) instanceof Map<?, ?> f)) {
                            result.addError("FIELD_CONSTRAINT", fp, "field rule must be an object");
                            continue;
                        }
                        if (!(f.get("target") instanceof String target) || target.isBlank()) {
                            result.addError("FIELD_CONSTRAINT", fp + ".target", "target must not be empty");
                        }
                        if (f.get("source") != null && !EXTRACTION_SOURCES.contains(String.valueOf(f.get("source")))) {
                            result.addError("INVALID_ENUM", fp + ".source", "Invalid extraction source: " + f.get("source"));
                        }
                        if (f.get("type") != null && !EXTRACTION_TYPES.contains(String.valueOf(f.get("type")))) {
                            result.addError("INVALID_ENUM", fp + ".type", "Invalid extraction type: " + f.get("type"));
                        }
                    }
                }
            }
            if (r.get("pollIntervalSeconds") != null && toInt(r.get("pollIntervalSeconds")) <= 0) {
                result.addError("FIELD_CONSTRAINT", path + ".pollIntervalSeconds", "pollIntervalSeconds must be > 0");
            }
        }
    }

    void validateIcon(Map<String, Object> icon, AiValidationResult result) {
        if (icon == null) return;
        validateEnumValue(icon.get("category"), IconCategory.class, "icon.category", result);
    }

    /** 视图切片里由平台决定、提案不得携带的键。 */
    private static final Set<String> VIEW_FORBIDDEN_KEYS = Set.of(
            "id", "maintableid", "detailformid", "isdefault", "status", "functionunitid");

    /** 与设计器 useMainTableViewDesigner 的 opMap 一致。 */
    private static final Set<String> VIEW_FILTER_OPERATORS = Set.of(
            "eq", "ne", "contains", "notContains", "notStartsWith", "endsWith", "notEndsWith",
            "gt", "lt", "isNull", "isNotNull");

    @SuppressWarnings("unchecked")
    void validateMainTableViews(List<Map<String, Object>> views, AiValidationResult result) {
        if (views == null) return;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < views.size(); i++) {
            Map<String, Object> v = views.get(i);
            String path = "mainTableViews[" + i + "]";
            for (String key : v.keySet()) {
                if (VIEW_FORBIDDEN_KEYS.contains(key.toLowerCase())) {
                    result.addError("FORBIDDEN_FIELD", path + "." + key,
                            "View proposals must not carry platform-assigned fields: " + key);
                }
            }
            String mainTableName = v.get("mainTableName") instanceof String s ? s.trim() : "";
            String viewName = v.get("viewName") instanceof String s ? s.trim() : "";
            if (mainTableName.isEmpty()) {
                result.addError("FIELD_CONSTRAINT", path + ".mainTableName", "mainTableName must not be empty");
            }
            if (viewName.isEmpty()) {
                result.addError("FIELD_CONSTRAINT", path + ".viewName", "viewName must not be empty");
            } else if (viewName.length() > 200) {
                result.addError("FIELD_CONSTRAINT", path + ".viewName", "viewName must not exceed 200 characters");
            }
            if (!mainTableName.isEmpty() && !viewName.isEmpty() && !seen.add(mainTableName + "\u0000" + viewName)) {
                result.addError("DUPLICATE", path + ".viewName",
                        "Duplicate view '" + viewName + "' on table '" + mainTableName + "' within the proposal");
            }
            if (v.get("restrictToInvolvedUsers") != null && !(v.get("restrictToInvolvedUsers") instanceof Boolean)) {
                result.addError("FIELD_CONSTRAINT", path + ".restrictToInvolvedUsers", "restrictToInvolvedUsers must be a boolean");
            }
            validateViewFields(v.get("fields"), path + ".fields", result);
            validateViewSort(v.get("sortConfig"), path + ".sortConfig", result);
            validateViewFilter(v.get("filterConfig"), path + ".filterConfig", result, 0);
            validateViewAccessRules(v.get("accessRules"), path + ".accessRules", result);
        }
    }

    private void validateViewFields(Object fieldsObj, String path, AiValidationResult result) {
        if (fieldsObj == null) return;
        if (!(fieldsObj instanceof List<?> fields)) {
            result.addError("FIELD_CONSTRAINT", path, "fields must be an array");
            return;
        }
        for (int j = 0; j < fields.size(); j++) {
            String fp = path + "[" + j + "]";
            if (!(fields.get(j) instanceof Map<?, ?> f)) {
                result.addError("FIELD_CONSTRAINT", fp, "field must be an object");
                continue;
            }
            if (!(f.get("fieldName") instanceof String fn) || fn.isBlank()) {
                result.addError("FIELD_CONSTRAINT", fp + ".fieldName", "fieldName must not be empty");
            }
            if (f.get("displayLabel") instanceof String label && label.length() > 200) {
                result.addError("FIELD_CONSTRAINT", fp + ".displayLabel", "displayLabel must not exceed 200 characters");
            }
            if (f.get("columnType") instanceof String ct && !ct.isBlank() && !"field".equalsIgnoreCase(ct.trim())) {
                result.addError("INVALID_ENUM", fp + ".columnType",
                        "columnType '" + ct + "' is not supported in proposals; only 'field' columns can be proposed");
            }
            if (f.get("columnWidth") != null && !(f.get("columnWidth") instanceof Number)) {
                result.addError("FIELD_CONSTRAINT", fp + ".columnWidth", "columnWidth must be a number");
            }
            if (f.get("visible") != null && !(f.get("visible") instanceof Boolean)) {
                result.addError("FIELD_CONSTRAINT", fp + ".visible", "visible must be a boolean");
            }
            if (f.get("systemField") != null && !(f.get("systemField") instanceof Boolean)) {
                result.addError("FIELD_CONSTRAINT", fp + ".systemField", "systemField must be a boolean");
            }
        }
    }

    private void validateViewSort(Object sortObj, String path, AiValidationResult result) {
        if (sortObj == null) return;
        if (!(sortObj instanceof List<?> sorts)) {
            result.addError("FIELD_CONSTRAINT", path, "sortConfig must be an array");
            return;
        }
        for (int j = 0; j < sorts.size(); j++) {
            String sp = path + "[" + j + "]";
            if (!(sorts.get(j) instanceof Map<?, ?> s)) {
                result.addError("FIELD_CONSTRAINT", sp, "sort entry must be an object");
                continue;
            }
            if (!(s.get("fieldName") instanceof String fn) || fn.isBlank()) {
                result.addError("FIELD_CONSTRAINT", sp + ".fieldName", "fieldName must not be empty");
            }
            Object dir = s.get("direction");
            if (!(dir instanceof String d) || !("ASC".equalsIgnoreCase(d) || "DESC".equalsIgnoreCase(d))) {
                result.addError("INVALID_ENUM", sp + ".direction", "direction must be ASC or DESC");
            }
        }
    }

    private void validateViewFilter(Object filterObj, String path, AiValidationResult result, int depth) {
        if (filterObj == null) return;
        if (!(filterObj instanceof Map<?, ?> filter)) {
            result.addError("FIELD_CONSTRAINT", path, "filterConfig must be an object");
            return;
        }
        if (filter.get("logic") instanceof String logic
                && !("and".equalsIgnoreCase(logic) || "or".equalsIgnoreCase(logic))) {
            result.addError("INVALID_ENUM", path + ".logic", "logic must be 'and' or 'or'");
        }
        Object conds = filter.get("conditions");
        if (conds != null) {
            if (!(conds instanceof List<?> list)) {
                result.addError("FIELD_CONSTRAINT", path + ".conditions", "conditions must be an array");
            } else {
                for (int j = 0; j < list.size(); j++) {
                    String cp = path + ".conditions[" + j + "]";
                    if (!(list.get(j) instanceof Map<?, ?> c)) {
                        result.addError("FIELD_CONSTRAINT", cp, "condition must be an object");
                        continue;
                    }
                    if (!(c.get("fieldName") instanceof String fn) || fn.isBlank()) {
                        result.addError("FIELD_CONSTRAINT", cp + ".fieldName", "fieldName must not be empty");
                    }
                    if (!(c.get("operator") instanceof String op) || !VIEW_FILTER_OPERATORS.contains(op)) {
                        result.addError("INVALID_ENUM", cp + ".operator",
                                "operator must be one of " + VIEW_FILTER_OPERATORS + ": " + c.get("operator"));
                    }
                }
            }
        }
        Object groups = filter.get("groups");
        if (groups != null) {
            if (!(groups instanceof List<?> list)) {
                result.addError("FIELD_CONSTRAINT", path + ".groups", "groups must be an array");
            } else if (depth >= 3) {
                result.addError("FIELD_CONSTRAINT", path + ".groups", "filter groups nest too deeply (max 3)");
            } else {
                for (int j = 0; j < list.size(); j++) {
                    validateViewFilter(list.get(j), path + ".groups[" + j + "]", result, depth + 1);
                }
            }
        }
    }

    private void validateViewAccessRules(Object rulesObj, String path, AiValidationResult result) {
        if (rulesObj == null) return;
        if (!(rulesObj instanceof List<?> rules)) {
            result.addError("FIELD_CONSTRAINT", path, "accessRules must be an array");
            return;
        }
        List<com.developer.dto.MainTableViewDtos.MainTableViewAccessRuleDTO> dtos = new java.util.ArrayList<>();
        for (int j = 0; j < rules.size(); j++) {
            String rp = path + "[" + j + "]";
            if (!(rules.get(j) instanceof Map<?, ?> r)) {
                result.addError("FIELD_CONSTRAINT", rp, "access rule must be an object");
                continue;
            }
            validateEnumValue(r.get("targetType"), MainTableViewAccessTargetType.class, rp + ".targetType", result);
            if (!(r.get("targetId") instanceof String id) || id.isBlank()) {
                result.addError("FIELD_CONSTRAINT", rp + ".targetId", "targetId must not be empty");
            }
            dtos.add(com.developer.dto.MainTableViewDtos.MainTableViewAccessRuleDTO.builder()
                    .targetType(r.get("targetType") instanceof String t ? t : null)
                    .targetId(r.get("targetId") instanceof String id ? id : null)
                    .build());
        }
        try {
            // 与设计器 Save / 导入同一条成对规则
            com.developer.util.MainTableViewAccessRulesValidator.validatePairedOrEmpty(dtos);
        } catch (com.developer.exception.DeveloperBusinessException ex) {
            result.addError(com.developer.util.MainTableViewAccessRulesValidator.PAIR_ERROR_CODE, path,
                    com.developer.util.MainTableViewAccessRulesValidator.PAIR_ERROR_MESSAGE);
        }
    }

    /** 绑定切片里不由提案决定的键（去掉 ap: 前缀、小写后比对）：legacy 配置与类型标记都由补丁器负责。 */
    private static final Set<String> BINDING_FORBIDDEN_KEYS = Set.of(
            "flowid", "webhookurl", "inputmapping", "outputmapping", "timeoutseconds", "retrycount", "servicetype");

    void validateServiceTaskBindings(List<Map<String, Object>> bindings, AiValidationResult result) {
        if (bindings == null) return;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < bindings.size(); i++) {
            Map<String, Object> b = bindings.get(i);
            String path = "serviceTaskBindings[" + i + "]";
            for (String key : b.keySet()) {
                String normalized = key.toLowerCase().startsWith("ap:") ? key.substring(3).toLowerCase() : key.toLowerCase();
                if (BINDING_FORBIDDEN_KEYS.contains(normalized)) {
                    result.addError("FORBIDDEN_FIELD", path + "." + key,
                            "Service task binding proposals carry only serviceTaskId and flowKey: " + key);
                }
            }
            String taskId = b.get("serviceTaskId") instanceof String s ? s.trim() : "";
            String flowKey = b.get("flowKey") instanceof String s ? s.trim() : "";
            if (taskId.isEmpty()) {
                result.addError("FIELD_CONSTRAINT", path + ".serviceTaskId", "serviceTaskId must not be empty");
            } else if (!seen.add(taskId)) {
                result.addError("DUPLICATE", path + ".serviceTaskId",
                        "Service task '" + taskId + "' is bound more than once in the proposal");
            }
            if (flowKey.isEmpty()) {
                result.addError("FIELD_CONSTRAINT", path + ".flowKey", "flowKey must not be empty");
            } else if (flowKey.length() > 255) {
                result.addError("FIELD_CONSTRAINT", path + ".flowKey", "flowKey must not exceed 255 characters");
            }
        }
    }

    private <E extends Enum<E>> void validateEnumValue(Object value, Class<E> enumClass,
                                                        String fieldPath, AiValidationResult result) {
        if (value == null) return;
        String strValue = value.toString();
        try {
            Enum.valueOf(enumClass, strValue);
        } catch (IllegalArgumentException e) {
            result.addError("INVALID_ENUM", fieldPath, "Invalid enum value: " + strValue);
        }
    }

    private int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return 0;
        }
    }
}
