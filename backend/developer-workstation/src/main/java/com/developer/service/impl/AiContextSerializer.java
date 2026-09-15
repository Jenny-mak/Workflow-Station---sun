package com.developer.service.impl;

import com.developer.dto.FunctionUnitContextDTO;
import com.developer.entity.ActionDefinition;
import com.developer.entity.DecisionDefinition;
import com.developer.entity.EmailConnection;
import com.developer.entity.EmailMonitorRule;
import com.developer.entity.EmailTemplate;
import com.developer.entity.FieldDefinition;
import com.developer.entity.ForeignKey;
import com.developer.entity.FormDefinition;
import com.developer.entity.FormStageBinding;
import com.developer.entity.FormTableBinding;
import com.developer.entity.FunctionUnit;
import com.developer.entity.Icon;
import com.developer.entity.ProcessDefinition;
import com.developer.entity.TableDefinition;
import com.developer.entity.TableRelation;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 功能单元上下文序列化协作类。
 * 负责将 {@link FunctionUnit} 及其关联实体转换为发送给 AI Agent (Activepieces) 的 {@link FunctionUnitContextDTO}。
 * 纯结构转换，无副作用、无外部依赖。
 */
@Component
class AiContextSerializer {

    FunctionUnitContextDTO buildContextDTO(FunctionUnit fu) {
        return buildContextDTO(fu, List.of(), List.of());
    }

    FunctionUnitContextDTO buildContextDTO(FunctionUnit fu,
                                           List<EmailTemplate> emailTemplates,
                                           List<EmailMonitorRule> emailMonitorRules) {
        return buildContextDTO(fu, emailTemplates, emailMonitorRules, List.of(), null, List.of());
    }

    FunctionUnitContextDTO buildContextDTO(FunctionUnit fu,
                                           List<EmailTemplate> emailTemplates,
                                           List<EmailMonitorRule> emailMonitorRules,
                                           List<Map<String, Object>> viewSnapshots,
                                           Map<String, Object> orgCatalog) {
        return buildContextDTO(fu, emailTemplates, emailMonitorRules, viewSnapshots, orgCatalog, List.of());
    }

    /**
     * 带邮件三阶段切片的上下文。模板与监控不挂在 {@link FunctionUnit} 上，由调用方按 FU 查出传入；
     * 连接走实体关联。连接只输出安全视图（见 {@link #serializeEmailConnections}）。
     */
    /**
     * @param viewSnapshots {@code MainTableViewService#snapshotViewsForFunctionUnit} 的输出（带库 id），
     *                      这里换成表名/表单名引用后放进上下文
     * @param orgCatalog    {@link AiOrgCatalogReader#readContext()}；null 表示不可用（上下文里省略）
     * @param automationFlows {@link AiAutomationFlowCatalogReader#toContext}；service task 绑定只能引用其中的 flowKey
     */
    FunctionUnitContextDTO buildContextDTO(FunctionUnit fu,
                                           List<EmailTemplate> emailTemplates,
                                           List<EmailMonitorRule> emailMonitorRules,
                                           List<Map<String, Object>> viewSnapshots,
                                           Map<String, Object> orgCatalog,
                                           List<Map<String, Object>> automationFlows) {
        // Explicitly trigger lazy loading (ensure all associations are loaded within @Transactional)
        List<TableDefinition> tables = fu.getTableDefinitions();
        if (tables != null) tables.size();
        List<FormDefinition> forms = fu.getFormDefinitions();
        if (forms != null) forms.size();
        List<ActionDefinition> actions = fu.getActionDefinitions();
        if (actions != null) actions.size();
        List<DecisionDefinition> decisions = fu.getDecisionDefinitions();
        if (decisions != null) decisions.size();
        List<TableRelation> relations = fu.getTableRelations();
        if (relations != null) relations.size();
        ProcessDefinition pd = fu.getProcessDefinition();
        Icon icon = fu.getIcon();
        List<EmailConnection> connections = fu.getEmailConnections();
        if (connections != null) connections.size();

        return FunctionUnitContextDTO.builder()
                .functionUnitId(fu.getId())
                .name(fu.getName())
                .description(fu.getDisplayName())
                .tableDefinitions(serializeTableDefinitions(tables))
                .formDefinitions(serializeFormDefinitions(forms))
                .actionDefinitions(serializeActionDefinitions(actions))
                .decisionDefinitions(serializeDecisionDefinitions(decisions))
                .tableRelations(serializeTableRelations(relations, tables))
                .processDefinition(serializeProcessDefinition(pd))
                .icon(serializeIcon(icon))
                .emailTemplates(serializeEmailTemplates(emailTemplates))
                .emailConnections(serializeEmailConnections(connections))
                .emailMonitorRules(serializeEmailMonitorRules(emailMonitorRules, connections))
                .mainTableViews(serializeMainTableViews(viewSnapshots, tables, forms))
                .orgCatalog(orgCatalog)
                .serviceTasks(serializeServiceTasks(pd))
                .automationFlows(automationFlows != null ? automationFlows : List.of())
                .build();
    }

    /** BPMN 里的 service task 及其当前 ap 绑定；存量 BPMN 解析失败只记 warn（上下文是顾问材料，Apply 前另有校验）。 */
    private List<Map<String, Object>> serializeServiceTasks(ProcessDefinition pd) {
        if (pd == null || pd.getBpmnXml() == null || pd.getBpmnXml().isBlank()) return List.of();
        try {
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (com.developer.util.BpmnServiceTaskScanner.ServiceTaskInfo t
                    : com.developer.util.BpmnServiceTaskScanner.scan(pd.getBpmnXml())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id());
                m.put("name", t.name());
                m.put("serviceType", t.serviceType());
                m.put("flowKey", t.flowKey());
                m.put("legacyFlowId", t.legacyFlowId());
                out.add(m);
            }
            return out;
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(AiContextSerializer.class)
                    .warn("Existing BPMN could not be scanned for service tasks; context omits them: {}", e.getMessage());
            return List.of();
        }
    }

    /** 视图快照去 id：mainTableId → mainTableName，detailFormId → detailFormName；找不到对应表的视图跳过。 */
    private List<Map<String, Object>> serializeMainTableViews(List<Map<String, Object>> snapshots,
                                                              List<TableDefinition> tables,
                                                              List<FormDefinition> forms) {
        if (snapshots == null || snapshots.isEmpty()) return List.of();
        Map<Long, String> tableNameById = new HashMap<>();
        if (tables != null) {
            for (TableDefinition t : tables) {
                if (t.getId() != null) tableNameById.put(t.getId(), t.getTableName());
            }
        }
        Map<Long, String> formNameById = new HashMap<>();
        if (forms != null) {
            for (FormDefinition f : forms) {
                if (f.getId() != null) formNameById.put(f.getId(), f.getFormName());
            }
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> snap : snapshots) {
            Long mainTableId = snap.get("mainTableId") instanceof Number n ? n.longValue() : null;
            String mainTableName = mainTableId != null ? tableNameById.get(mainTableId) : null;
            if (mainTableName == null) continue;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("mainTableName", mainTableName);
            v.put("viewName", snap.get("viewName"));
            v.put("isDefault", snap.get("isDefault"));
            v.put("status", snap.get("status"));
            v.put("restrictToInvolvedUsers", snap.get("restrictToInvolvedUsers"));
            Long detailFormId = snap.get("detailFormId") instanceof Number n ? n.longValue() : null;
            v.put("detailFormName", detailFormId != null ? formNameById.get(detailFormId) : null);
            v.put("accessRules", snap.get("accessRules"));
            v.put("sortConfig", snap.get("sortConfig"));
            v.put("filterConfig", snap.get("filterConfig"));
            v.put("fields", snap.get("fields"));
            out.add(v);
        }
        return out;
    }

    private List<Map<String, Object>> serializeEmailTemplates(List<EmailTemplate> templates) {
        if (templates == null) return List.of();
        return templates.stream().map(t -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", t.getName());
            map.put("subject", t.getSubject());
            map.put("bodyHtml", t.getBodyHtml());
            map.put("enabled", t.getEnabled());
            return map;
        }).collect(Collectors.toList());
    }

    /**
     * 连接的安全视图：白名单输出，绝不带 username / credentialEncrypted / oauth* / token*。
     * {@code hasCredentials} 让模型知道哪些入站连接能被监控模板引用。
     */
    private List<Map<String, Object>> serializeEmailConnections(List<EmailConnection> connections) {
        if (connections == null) return List.of();
        return connections.stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", c.getName());
            map.put("connectionType", c.getConnectionType() != null ? c.getConnectionType().name() : null);
            map.put("direction", c.getDirection() != null ? c.getDirection().name() : null);
            map.put("fromName", c.getFromName());
            map.put("mailboxAddress", c.getMailboxAddress());
            map.put("enabled", c.getEnabled());
            map.put("hasCredentials", c.getCredentialEncrypted() != null && !c.getCredentialEncrypted().isBlank());
            return map;
        }).collect(Collectors.toList());
    }

    /** 监控模板（无起始事件绑定的规则）；connectionUid 换成模型可引用的 connectionName。 */
    private List<Map<String, Object>> serializeEmailMonitorRules(List<EmailMonitorRule> rules,
                                                                 List<EmailConnection> connections) {
        if (rules == null) return List.of();
        Map<String, String> nameByUid = new HashMap<>();
        if (connections != null) {
            for (EmailConnection c : connections) {
                if (c.getConnectionUid() != null) nameByUid.put(c.getConnectionUid(), c.getName());
            }
        }
        return rules.stream()
                .filter(r -> r.getSourceRuleId() == null
                        && (r.getStartEventId() == null || r.getStartEventId().isBlank()))
                .map(r -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", r.getName());
                    map.put("enabled", r.getEnabled());
                    map.put("connectionName", nameByUid.get(r.getConnectionUid()));
                    map.put("folderLabel", r.getFolderLabel());
                    map.put("actionType", r.getActionType() != null ? r.getActionType().name() : null);
                    map.put("extractionRules", r.getExtractionRules());
                    map.put("correlation", r.getCorrelation());
                    map.put("pollIntervalSeconds", r.getPollIntervalSeconds());
                    map.put("reviewOnMissing", r.getReviewOnMissing());
                    return map;
                }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> serializeTableDefinitions(List<TableDefinition> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        return tables.stream().map(t -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tableName", t.getTableName());
            map.put("tableType", t.getTableType() != null ? t.getTableType().name() : null);
            map.put("tableDisplayName", t.getTableDisplayName());
            map.put("description", t.getDisplayName());
            map.put("fieldDefinitions", serializeFieldDefinitions(t.getFieldDefinitions()));
            map.put("foreignKeys", serializeForeignKeys(t.getForeignKeys()));
            return map;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> serializeFieldDefinitions(List<FieldDefinition> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        return fields.stream().map(f -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("fieldName", f.getFieldName());
            map.put("dataType", f.getDataType() != null ? f.getDataType().name() : null);
            map.put("length", f.getLength());
            map.put("precision", f.getPrecision());
            map.put("scale", f.getScale());
            map.put("nullable", f.getNullable());
            map.put("defaultValue", f.getDefaultValue());
            map.put("isPrimaryKey", f.getIsPrimaryKey());
            map.put("isUnique", f.getIsUnique());
            map.put("description", f.getDisplayName());
            map.put("sortOrder", f.getSortOrder());
            return map;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> serializeForeignKeys(List<ForeignKey> foreignKeys) {
        if (foreignKeys == null || foreignKeys.isEmpty()) {
            return List.of();
        }
        return foreignKeys.stream().map(fk -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("fieldName", fk.getFieldDefinition() != null ? fk.getFieldDefinition().getFieldName() : null);
            map.put("refTableName", fk.getRefTableDefinition() != null ? fk.getRefTableDefinition().getTableName() : null);
            map.put("refFieldName", fk.getRefFieldDefinition() != null ? fk.getRefFieldDefinition().getFieldName() : null);
            map.put("onDelete", fk.getOnDelete());
            map.put("onUpdate", fk.getOnUpdate());
            return map;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> serializeFormDefinitions(List<FormDefinition> forms) {
        if (forms == null || forms.isEmpty()) {
            return List.of();
        }
        return forms.stream().map(f -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("formName", f.getFormName());
            map.put("formType", f.getFormType() != null ? f.getFormType().name() : null);
            map.put("configJson", f.getConfigJson());
            map.put("description", f.getDisplayName());
            map.put("tableBindings", serializeTableBindings(f.getTableBindings()));
            map.put("fieldPermissions", f.getFieldPermissions() != null ? f.getFieldPermissions() : Map.of());
            map.put("showLiveValues", f.getShowLiveValues());
            map.put("stageBindings", serializeStageBindings(f.getStageBindings()));
            return map;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> serializeStageBindings(List<FormStageBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        return bindings.stream().map(b -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("stageId", b.getStageId());
            map.put("stageName", b.getStageName());
            return map;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> serializeTableBindings(List<FormTableBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        return bindings.stream().map(b -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tableName", b.getTableName());
            map.put("bindingType", b.getBindingType() != null ? b.getBindingType().name() : null);
            map.put("bindingMode", b.getBindingMode() != null ? b.getBindingMode().name() : null);
            map.put("foreignKeyField", b.getForeignKeyField());
            map.put("sortOrder", b.getSortOrder());
            return map;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> serializeActionDefinitions(List<ActionDefinition> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream().map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("actionName", a.getActionName());
            map.put("actionType", a.getActionType() != null ? a.getActionType().name() : null);
            map.put("configJson", a.getConfigJson());
            map.put("icon", a.getIcon());
            map.put("buttonColor", a.getButtonColor());
            map.put("description", a.getDisplayName());
            map.put("isDefault", a.getIsDefault());
            return map;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> serializeDecisionDefinitions(List<DecisionDefinition> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return List.of();
        }
        return decisions.stream().map(d -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("decisionKey", d.getDecisionKey());
            map.put("decisionName", d.getDecisionName());
            map.put("dmnXml", d.getDmnXml());
            map.put("hitPolicy", d.getHitPolicy());
            map.put("description", d.getDescription());
            return map;
        }).collect(Collectors.toList());
    }

    /**
     * Serialize table relations, resolving sourceTableId/targetTableId to corresponding tableName
     * (AI does not know internal IDs).
     */
    private List<Map<String, Object>> serializeTableRelations(
            List<TableRelation> relations, List<TableDefinition> tables) {
        if (relations == null || relations.isEmpty()) {
            return List.of();
        }

        // Build ID → tableName lookup table
        Map<Long, String> idToName = new HashMap<>();
        if (tables != null) {
            for (TableDefinition t : tables) {
                if (t.getId() != null) {
                    idToName.put(t.getId(), t.getTableName());
                }
            }
        }

        return relations.stream().map(r -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sourceTableName", idToName.getOrDefault(r.getSourceTableId(), "unknown_" + r.getSourceTableId()));
            map.put("sourceFieldName", r.getSourceFieldName());
            map.put("relationType", r.getRelationType());
            map.put("targetTableName", idToName.getOrDefault(r.getTargetTableId(), "unknown_" + r.getTargetTableId()));
            map.put("targetFieldName", r.getTargetFieldName());
            return map;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> serializeProcessDefinition(ProcessDefinition pd) {
        if (pd == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bpmnXml", pd.getBpmnXml());
        return map;
    }

    private Map<String, Object> serializeIcon(Icon icon) {
        if (icon == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", icon.getName());
        map.put("category", icon.getCategory() != null ? icon.getCategory().name() : null);
        map.put("svgContent", icon.getSvgContent());
        map.put("description", icon.getDescription());
        return map;
    }
}
