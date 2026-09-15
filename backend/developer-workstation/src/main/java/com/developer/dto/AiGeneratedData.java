package com.developer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI 生成的结构化数据 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiGeneratedData {

    private List<Map<String, Object>> tableDefinitions;

    private List<Map<String, Object>> formDefinitions;

    private List<Map<String, Object>> actionDefinitions;

    private List<Map<String, Object>> decisionDefinitions;

    private List<Map<String, Object>> tableRelations;

    private Map<String, Object> processDefinition;

    /**
     * AI Studio 邮件三阶段的提案切片（EMAIL_TEMPLATES / CONNECTIONS / EMAIL_MONITORS）。
     * 与其余切片不同：写入是按业务名 upsert，从不删除未提及的对象；连接切片不含任何凭证/主机字段，
     * 监控切片只表示"监控模板"（起始事件绑定在 Process Design 里另建）。
     */
    private List<Map<String, Object>> emailTemplates;

    private List<Map<String, Object>> emailConnections;

    private List<Map<String, Object>> emailMonitorRules;

    /**
     * VIEW_DESIGN 的主表视图提案切片：按 (mainTableName, viewName) upsert，从不删除、不动 isDefault。
     * 视图与访问规则的 id 均来自上下文里的 orgCatalog / 表名，不出现数据库 id。
     */
    private List<Map<String, Object>> mainTableViews;

    /**
     * AUTOMATION 的 service task 绑定切片：{@code [{ serviceTaskId, flowKey }]}，Apply 时对现有 BPMN 做定点补丁
     * （写 ap:flowKey、补 serviceType=ap、清 legacy ap:* 键），不重出流程、不解绑。
     */
    private List<Map<String, Object>> serviceTaskBindings;

    private Map<String, Object> icon;

    private String name;

    private String description;

    private Map<String, String> explanations;
}
