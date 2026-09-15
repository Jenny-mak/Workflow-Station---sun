package com.developer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 功能单元上下文序列化 DTO（发送给 AI webhook）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionUnitContextDTO {

    private Long functionUnitId;

    private String name;

    private String description;

    private List<Map<String, Object>> tableDefinitions;

    private List<Map<String, Object>> formDefinitions;

    private List<Map<String, Object>> actionDefinitions;

    private List<Map<String, Object>> decisionDefinitions;

    private List<Map<String, Object>> tableRelations;

    private Map<String, Object> processDefinition;

    private Map<String, Object> icon;

    /** 邮件模板（name / subject / bodyHtml / enabled） */
    private List<Map<String, Object>> emailTemplates;

    /** 邮件连接的安全视图：只含名称/类型/方向/显示名/邮箱与 hasCredentials 标记，绝不含凭证 */
    private List<Map<String, Object>> emailConnections;

    /** 邮件监控模板（不含起始事件绑定副本），connectionUid 已换成 connectionName */
    private List<Map<String, Object>> emailMonitorRules;

    /** 主表视图快照（mainTableId → mainTableName、detailFormId → detailFormName，去 id） */
    private List<Map<String, Object>> mainTableViews;

    /** 组织目录：{ businessUnits: [{ id, code, name, roles: [{ id, code, name }] }], truncated } —— 访问规则只能引用这里的 id */
    private Map<String, Object> orgCatalog;

    /** BPMN 里的 service task 及其当前绑定：[{ id, name, serviceType, flowKey, legacyFlowId }] */
    private List<Map<String, Object>> serviceTasks;

    /** 本 FU 可引用的 Automation flow：[{ flowKey, displayName, published, workspace }]，只含带业务键的 flow */
    private List<Map<String, Object>> automationFlows;
}
