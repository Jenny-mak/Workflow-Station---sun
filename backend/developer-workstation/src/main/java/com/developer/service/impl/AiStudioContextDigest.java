package com.developer.service.impl;

import com.developer.dto.FunctionUnitContextDTO;
import com.developer.enums.AiStudioPhase;
import com.developer.util.BpmnNodeSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把功能单元上下文压成一段"当前设计现状"的纯文本，供 AI Studio 的顾问式对话使用。
 *
 * <p>顾问轮不产出结构化数据，不需要 {@code AiGeneratedData} 那套 JSON 契约；把整份上下文
 * （BPMN 全文 + 每个表单的 configJson）塞进对话 prompt 只会占满窗口并拖慢每一轮。这里按
 * <b>当前阶段</b>挑切片，逐行渲染名称级别的信息：流程只给节点摘要，决策不给 DMN，模板不给正文，
 * 表单不给 configJson。</p>
 *
 * <p>整体有 {@link #DIGEST_CHAR_BUDGET} 字符预算，超出的小节按行截断并标注，保证 prompt 可控。</p>
 */
@Component
public class AiStudioContextDigest {

    /** 摘要的字符预算：够描述几十张表的名称级信息，又不至于把对话窗口挤满。 */
    static final int DIGEST_CHAR_BUDGET = 6000;

    private static final String NONE = "(none yet)";

    /**
     * 为两种"被裁掉了"的标记预留的字符：小节内的 {@code …(truncated, N more)} 与结尾的
     * {@code …(N section(s) omitted…)}。预留后两种标记都还落在预算内。
     */
    private static final int TRUNCATION_RESERVE = 80;

    private record Section(String title, List<String> lines) {}

    /**
     * @param phase   当前阶段（{@code AiStudioChatRequest.phase}）
     * @param context 功能单元上下文；null 时返回空串（调用方不渲染设计段落）
     * @return 纯文本摘要；无任何可讲的内容时返回空串
     */
    public String digest(String phase, FunctionUnitContextDTO context) {
        if (context == null) {
            return "";
        }
        List<Section> sections = sectionsFor(phase, context);
        if (sections.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int skipped = 0;
        for (Section section : sections) {
            List<String> lines = section.lines().isEmpty() ? List.of(NONE) : section.lines();
            String head = "### " + section.title() + "\n";
            // 整节（标题 + 至少一行 + 截断标记的余量）放不下就整节跳过，避免只剩一个空标题
            if (sb.length() + head.length() + lines.get(0).length() + 1 + TRUNCATION_RESERVE > DIGEST_CHAR_BUDGET) {
                skipped++;
                continue;
            }
            sb.append(head);
            int rendered = 0;
            for (String line : lines) {
                if (sb.length() + line.length() + 1 + TRUNCATION_RESERVE > DIGEST_CHAR_BUDGET && rendered > 0) {
                    sb.append("…(truncated, ").append(lines.size() - rendered).append(" more)\n");
                    break;
                }
                sb.append(line).append('\n');
                rendered++;
            }
            sb.append('\n');
        }
        if (skipped > 0) {
            sb.append("…(").append(skipped).append(" section(s) omitted for budget)\n");
        }
        return sb.toString().trim();
    }

    private List<Section> sectionsFor(String phase, FunctionUnitContextDTO c) {
        AiStudioPhase known = AiStudioPhase.fromKey(phase).orElse(null);
        if (known == null) {
            return List.of(new Section("Design overview", overview(c)));
        }
        // switch 表达式对枚举要求穷举：新增阶段不补 case 会编译失败
        return switch (known) {
            case PROCESS_DESIGN -> List.of(
                    new Section("Process nodes", processNodes(c)),
                    new Section("Forms bound to nodes", formStageBindings(c)),
                    new Section("Tables", tableNames(c)));
            case TABLE_DESIGN -> List.of(
                    new Section("Tables and fields", tablesWithFields(c)),
                    new Section("Table relations", tableRelations(c)));
            case FORM_DESIGN -> List.of(
                    new Section("Forms", forms(c)),
                    new Section("Tables and fields", tablesWithFields(c)));
            case VIEW_DESIGN -> List.of(
                    new Section("Main table views", views(c)),
                    new Section("Tables and fields", tablesWithFields(c)),
                    new Section("Business units and roles", orgCatalog(c)));
            case ACTION_DESIGN -> List.of(
                    new Section("Actions", actions(c)),
                    new Section("Process nodes", processNodes(c)));
            case AUTOMATION -> List.of(
                    new Section("Service tasks", serviceTasks(c)),
                    new Section("Available automation flows", automationFlows(c)));
            case CONNECTIONS -> List.of(new Section("Email connections", connections(c)));
            case EMAIL_TEMPLATES -> List.of(
                    new Section("Email templates", emailTemplates(c)),
                    new Section("Main table fields (usable as ${field} variables)", mainTableFields(c)));
            case EMAIL_MONITORS -> List.of(
                    new Section("Email monitor templates", emailMonitors(c)),
                    new Section("Email connections", connections(c)),
                    new Section("Main table fields (extraction targets)", mainTableFields(c)));
            case DECISION_DESIGN -> List.of(
                    new Section("Decision tables", decisions(c)),
                    new Section("Tables", tableNames(c)));
            case VALIDATION -> List.of(new Section("Design overview", overview(c)));
        };
    }

    // ---- 各切片的行渲染 ----

    private static List<String> processNodes(FunctionUnitContextDTO c) {
        Object xml = c.getProcessDefinition() != null ? c.getProcessDefinition().get("bpmnXml") : null;
        return xml instanceof String s ? BpmnNodeSummary.summarize(s) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> tablesWithFields(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> t : nullSafe(c.getTableDefinitions())) {
            StringBuilder sb = new StringBuilder(str(t.get("tableName")));
            String type = str(t.get("tableType"));
            if (!type.isEmpty()) sb.append(" (").append(type).append(')');
            String display = str(t.get("tableDisplayName"));
            if (!display.isEmpty()) sb.append(" \"").append(display).append('"');
            sb.append(": ");
            List<String> fields = new ArrayList<>();
            Map<String, String> fkByField = new java.util.HashMap<>();
            if (t.get("foreignKeys") instanceof List<?> fks) {
                for (Object o : fks) {
                    if (o instanceof Map<?, ?> fk) {
                        fkByField.put(str(fk.get("fieldName")), str(fk.get("refTableName")) + "." + str(fk.get("refFieldName")));
                    }
                }
            }
            if (t.get("fieldDefinitions") instanceof List<?> fs) {
                for (Object o : fs) {
                    if (!(o instanceof Map<?, ?> raw)) continue;
                    Map<String, Object> f = (Map<String, Object>) raw;
                    StringBuilder fb = new StringBuilder(str(f.get("fieldName")));
                    String dataType = str(f.get("dataType"));
                    if (!dataType.isEmpty()) fb.append(':').append(dataType);
                    if (Boolean.TRUE.equals(f.get("isPrimaryKey"))) fb.append(" [PK]");
                    String ref = fkByField.get(str(f.get("fieldName")));
                    if (ref != null) fb.append(" -> ").append(ref);
                    fields.add(fb.toString());
                }
            }
            sb.append(fields.isEmpty() ? "(no fields)" : String.join(", ", fields));
            lines.add(sb.toString());
        }
        return lines;
    }

    private static List<String> tableNames(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> t : nullSafe(c.getTableDefinitions())) {
            String type = str(t.get("tableType"));
            lines.add(str(t.get("tableName")) + (type.isEmpty() ? "" : " (" + type + ")"));
        }
        return lines;
    }

    /** 主表字段名：邮件模板变量与抽取目标只能用这些。 */
    private static List<String> mainTableFields(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> t : nullSafe(c.getTableDefinitions())) {
            if (!"MAIN".equals(str(t.get("tableType")))) continue;
            Set<String> names = new LinkedHashSet<>();
            if (t.get("fieldDefinitions") instanceof List<?> fs) {
                for (Object o : fs) {
                    if (o instanceof Map<?, ?> f) names.add(str(f.get("fieldName")));
                }
            }
            lines.add(str(t.get("tableName")) + ": " + (names.isEmpty() ? "(no fields)" : String.join(", ", names)));
        }
        return lines;
    }

    private static List<String> tableRelations(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> r : nullSafe(c.getTableRelations())) {
            lines.add(str(r.get("sourceTableName")) + "." + str(r.get("sourceFieldName"))
                    + " --" + str(r.get("relationType")) + "--> "
                    + str(r.get("targetTableName")) + "." + str(r.get("targetFieldName")));
        }
        return lines;
    }

    private static List<String> forms(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> f : nullSafe(c.getFormDefinitions())) {
            StringBuilder sb = new StringBuilder(str(f.get("formName")));
            String type = str(f.get("formType"));
            if (!type.isEmpty()) sb.append(" (").append(type).append(')');
            List<String> bindings = new ArrayList<>();
            if (f.get("tableBindings") instanceof List<?> bs) {
                for (Object o : bs) {
                    if (o instanceof Map<?, ?> b) {
                        bindings.add(str(b.get("tableName")) + "/" + str(b.get("bindingType")));
                    }
                }
            }
            if (!bindings.isEmpty()) sb.append(" tables: ").append(String.join(", ", bindings));
            List<String> stages = stageIdsOf(f);
            if (!stages.isEmpty()) sb.append(" stages: ").append(String.join(", ", stages));
            lines.add(sb.toString());
        }
        return lines;
    }

    private static List<String> formStageBindings(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> f : nullSafe(c.getFormDefinitions())) {
            List<String> stages = stageIdsOf(f);
            if (!stages.isEmpty()) {
                lines.add(String.join(", ", stages) + " -> " + str(f.get("formName")));
            }
        }
        return lines;
    }

    private static List<String> stageIdsOf(Map<String, Object> form) {
        List<String> stages = new ArrayList<>();
        if (form.get("stageBindings") instanceof List<?> sb) {
            for (Object o : sb) {
                if (o instanceof Map<?, ?> s) {
                    String id = str(s.get("stageId"));
                    if (!id.isEmpty()) stages.add(id);
                }
            }
        }
        return stages;
    }

    private static List<String> actions(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> a : nullSafe(c.getActionDefinitions())) {
            StringBuilder sb = new StringBuilder(str(a.get("actionName")));
            String type = str(a.get("actionType"));
            if (!type.isEmpty()) sb.append(" (").append(type).append(')');
            if (Boolean.TRUE.equals(a.get("isDefault"))) sb.append(" [default]");
            lines.add(sb.toString());
        }
        return lines;
    }

    private static List<String> decisions(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> d : nullSafe(c.getDecisionDefinitions())) {
            StringBuilder sb = new StringBuilder(str(d.get("decisionKey")));
            String name = str(d.get("decisionName"));
            if (!name.isEmpty()) sb.append(" \"").append(name).append('"');
            String hit = str(d.get("hitPolicy"));
            if (!hit.isEmpty()) sb.append(" hitPolicy=").append(hit);
            lines.add(sb.toString());
        }
        return lines;
    }

    private static List<String> views(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> v : nullSafe(c.getMainTableViews())) {
            StringBuilder sb = new StringBuilder(str(v.get("mainTableName")) + " / " + str(v.get("viewName")));
            if (Boolean.TRUE.equals(v.get("isDefault"))) sb.append(" [default]");
            String status = str(v.get("status"));
            if (!status.isEmpty()) sb.append(' ').append(status);
            sb.append(" columns=").append(sizeOf(v.get("fields")));
            sb.append(" accessRules=").append(sizeOf(v.get("accessRules")));
            if (Boolean.TRUE.equals(v.get("restrictToInvolvedUsers"))) sb.append(" [involved users only]");
            lines.add(sb.toString());
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static List<String> orgCatalog(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        Map<String, Object> catalog = c.getOrgCatalog();
        if (catalog == null || !(catalog.get("businessUnits") instanceof List<?> bus)) return lines;
        for (Object o : bus) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> bu = (Map<String, Object>) raw;
            List<String> roles = new ArrayList<>();
            if (bu.get("roles") instanceof List<?> rs) {
                for (Object r : rs) {
                    if (r instanceof Map<?, ?> role) roles.add(str(role.get("name")) + " (" + str(role.get("id")) + ")");
                }
            }
            lines.add(str(bu.get("name")) + " (" + str(bu.get("id")) + "): "
                    + (roles.isEmpty() ? "(no eligible roles)" : String.join(", ", roles)));
        }
        return lines;
    }

    private static List<String> serviceTasks(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> t : nullSafe(c.getServiceTasks())) {
            StringBuilder sb = new StringBuilder(str(t.get("id")));
            String name = str(t.get("name"));
            if (!name.isEmpty()) sb.append(" \"").append(name).append('"');
            String key = str(t.get("flowKey"));
            String legacy = str(t.get("legacyFlowId"));
            sb.append(!key.isEmpty() ? " -> flowKey=" + key
                    : !legacy.isEmpty() ? " -> legacy flowId=" + legacy : " (not bound)");
            lines.add(sb.toString());
        }
        return lines;
    }

    private static List<String> automationFlows(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> f : nullSafe(c.getAutomationFlows())) {
            lines.add(str(f.get("flowKey")) + " \"" + str(f.get("displayName")) + "\""
                    + (Boolean.TRUE.equals(f.get("published")) ? " [published]" : " [draft]")
                    + " workspace=" + str(f.get("workspace")));
        }
        return lines;
    }

    private static List<String> connections(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> conn : nullSafe(c.getEmailConnections())) {
            lines.add(str(conn.get("name")) + " (" + str(conn.get("connectionType")) + ", " + str(conn.get("direction")) + ")"
                    + (Boolean.TRUE.equals(conn.get("enabled")) ? " enabled" : " disabled")
                    + (Boolean.TRUE.equals(conn.get("hasCredentials")) ? ", credentials set" : ", no credentials yet"));
        }
        return lines;
    }

    private static List<String> emailTemplates(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> t : nullSafe(c.getEmailTemplates())) {
            lines.add(str(t.get("name")) + " subject=\"" + str(t.get("subject")) + "\""
                    + (Boolean.TRUE.equals(t.get("enabled")) ? "" : " [disabled]"));
        }
        return lines;
    }

    private static List<String> emailMonitors(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> m : nullSafe(c.getEmailMonitorRules())) {
            lines.add(str(m.get("name")) + " connection=" + str(m.get("connectionName"))
                    + " action=" + str(m.get("actionType"))
                    + (Boolean.TRUE.equals(m.get("enabled")) ? "" : " [disabled]"));
        }
        return lines;
    }

    private static List<String> overview(FunctionUnitContextDTO c) {
        List<String> lines = new ArrayList<>();
        lines.add("tables=" + sizeOf(c.getTableDefinitions())
                + ", relations=" + sizeOf(c.getTableRelations())
                + ", forms=" + sizeOf(c.getFormDefinitions())
                + ", actions=" + sizeOf(c.getActionDefinitions())
                + ", decisions=" + sizeOf(c.getDecisionDefinitions()));
        lines.add("views=" + sizeOf(c.getMainTableViews())
                + ", serviceTasks=" + sizeOf(c.getServiceTasks())
                + ", connections=" + sizeOf(c.getEmailConnections())
                + ", emailTemplates=" + sizeOf(c.getEmailTemplates())
                + ", emailMonitors=" + sizeOf(c.getEmailMonitorRules()));
        boolean hasProcess = c.getProcessDefinition() != null
                && c.getProcessDefinition().get("bpmnXml") instanceof String s && !s.isBlank();
        lines.add("process=" + (hasProcess ? "defined (" + processNodes(c).size() + " nodes)" : "not defined"));
        return lines;
    }

    private static List<Map<String, Object>> nullSafe(List<Map<String, Object>> list) {
        return list != null ? list : List.of();
    }

    private static int sizeOf(Object o) {
        return o instanceof List<?> l ? l.size() : 0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
