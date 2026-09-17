package com.developer.service.impl;

import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiStudioProposalPreview;
import com.developer.dto.AiStudioProposalPreview.Action;
import com.developer.dto.AiStudioProposalPreview.Issue;
import com.developer.dto.AiStudioProposalPreview.Item;
import com.developer.dto.AiStudioProposalPreview.Severity;
import com.developer.dto.AiStudioProposalPreview.SliceReplacement;
import com.developer.dto.AiValidationError;
import com.developer.dto.AiValidationResult;
import com.developer.entity.EmailConnection;
import com.developer.entity.EmailMonitorRule;
import com.developer.entity.EmailTemplate;
import com.developer.entity.FieldDefinition;
import com.developer.entity.MainTableViewConfig;
import com.developer.entity.ProcessDefinition;
import com.developer.entity.TableDefinition;
import com.developer.enums.EmailConnectionDirection;
import com.developer.repository.ActionDefinitionRepository;
import com.developer.repository.DecisionDefinitionRepository;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.MainTableViewConfigRepository;
import com.developer.repository.ProcessDefinitionRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.service.AiValidationService;
import com.developer.util.BpmnServiceTaskScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 提案卡预览：提案生成完成时就算出"会新增/更新/替换什么"和"Apply 会不会被拒"。
 *
 * <p>比对键与各写入器一致（模板按 name、连接按 name+direction、监控模板按 name、视图按 表名+视图名、
 * 绑定按 serviceTaskId）；校验与 {@code AiStudioChatComponentImpl#applyProposal} 完全相同的两道
 * （结构校验带 FU 已有表目录 + FU 感知引用校验），所以预览里的 ERROR 就是 Apply 会撞的错。</p>
 *
 * <p>在提案作业的后台线程里运行，用只读事务保证 JPA 懒加载可用。预览自身的异常只记 warn 并把
 * {@code checked=false}——预览是辅助信息，不能让一轮几分钟的生成因此作废。</p>
 */
@Slf4j
@Component
public class AiStudioProposalPreviewer {

    private final AiValidationService aiValidationService;
    private final AiStudioProposalReferenceValidator referenceValidator;
    private final TableDefinitionRepository tableDefinitionRepository;
    private final FormDefinitionRepository formDefinitionRepository;
    private final ActionDefinitionRepository actionDefinitionRepository;
    private final DecisionDefinitionRepository decisionDefinitionRepository;
    private final TableRelationRepository tableRelationRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final EmailTemplateRepository emailTemplateRepository;
    private final EmailConnectionRepository emailConnectionRepository;
    private final EmailMonitorRuleRepository emailMonitorRuleRepository;
    private final MainTableViewConfigRepository mainTableViewConfigRepository;

    public AiStudioProposalPreviewer(AiValidationService aiValidationService,
                                     AiStudioProposalReferenceValidator referenceValidator,
                                     TableDefinitionRepository tableDefinitionRepository,
                                     FormDefinitionRepository formDefinitionRepository,
                                     ActionDefinitionRepository actionDefinitionRepository,
                                     DecisionDefinitionRepository decisionDefinitionRepository,
                                     TableRelationRepository tableRelationRepository,
                                     ProcessDefinitionRepository processDefinitionRepository,
                                     EmailTemplateRepository emailTemplateRepository,
                                     EmailConnectionRepository emailConnectionRepository,
                                     EmailMonitorRuleRepository emailMonitorRuleRepository,
                                     MainTableViewConfigRepository mainTableViewConfigRepository) {
        this.aiValidationService = aiValidationService;
        this.referenceValidator = referenceValidator;
        this.tableDefinitionRepository = tableDefinitionRepository;
        this.formDefinitionRepository = formDefinitionRepository;
        this.actionDefinitionRepository = actionDefinitionRepository;
        this.decisionDefinitionRepository = decisionDefinitionRepository;
        this.tableRelationRepository = tableRelationRepository;
        this.processDefinitionRepository = processDefinitionRepository;
        this.emailTemplateRepository = emailTemplateRepository;
        this.emailConnectionRepository = emailConnectionRepository;
        this.emailMonitorRuleRepository = emailMonitorRuleRepository;
        this.mainTableViewConfigRepository = mainTableViewConfigRepository;
    }

    /** 本 FU 的表名 → 字段名；scoped 提案不含表定义时作为校验的兜底目录。提案自带表定义时返回空表（保持原严格语义）。 */
    @Transactional(readOnly = true)
    public Map<String, Set<String>> existingTableFieldsFor(Long functionUnitId, AiGeneratedData data) {
        if (data.getTableDefinitions() != null && !data.getTableDefinitions().isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (TableDefinition t : tableDefinitionRepository.findByFunctionUnitIdWithFields(functionUnitId)) {
            Set<String> fields = new LinkedHashSet<>();
            if (t.getFieldDefinitions() != null) {
                for (FieldDefinition f : t.getFieldDefinitions()) {
                    if (f.getFieldName() != null) fields.add(f.getFieldName());
                }
            }
            out.put(t.getTableName(), fields);
        }
        return out;
    }

    /**
     * 永不抛：预览失败返回 {@code checked=false} 的空预览。
     *
     * @param data 已经过 Component 层归一化（与 Apply 同款 convert + normalize）的提案数据
     */
    @Transactional(readOnly = true)
    public AiStudioProposalPreview preview(Long functionUnitId, String scope, AiGeneratedData data) {
        AiStudioProposalPreview preview = AiStudioProposalPreview.builder()
                .undoable(AiStudioUndoService.isUndoable(scope))
                .build();
        if (data == null) {
            preview.setChecked(true);
            return preview;
        }
        try {
            summarizeItems(functionUnitId, data, preview);

            AiValidationResult structure = aiValidationService.validate(data, existingTableFieldsFor(functionUnitId, data));
            addIssues(preview, structure);
            AiValidationResult reference = referenceValidator.validate(functionUnitId, data);
            addIssues(preview, reference);
            preview.setChecked(true);
        } catch (RuntimeException e) {
            log.warn("AI Studio proposal preview failed for functionUnitId={} scope={}: {}", functionUnitId, scope, e.getMessage(), e);
            preview.setChecked(false);
        }
        return preview;
    }

    private static void addIssues(AiStudioProposalPreview preview, AiValidationResult result) {
        for (AiValidationError e : result.getErrors()) {
            preview.getIssues().add(Issue.builder().severity(Severity.ERROR).errorType(e.getErrorType())
                    .fieldPath(e.getFieldPath()).description(e.getDescription()).build());
        }
        for (AiValidationError w : result.getWarnings()) {
            preview.getIssues().add(Issue.builder().severity(Severity.WARNING).errorType(w.getErrorType())
                    .fieldPath(w.getFieldPath()).description(w.getDescription()).build());
        }
    }

    private void summarizeItems(Long functionUnitId, AiGeneratedData data, AiStudioProposalPreview preview) {
        // ---- 清空再写的切片：列名称 + 现有对象数 ----
        replaceSlice(preview, "tableDefinitions", data.getTableDefinitions(), "tableName",
                () -> tableDefinitionRepository.findByFunctionUnitId(functionUnitId).size());
        replaceSlice(preview, "tableRelations", data.getTableRelations(),
                r -> str(r.get("sourceTableName")) + " → " + str(r.get("targetTableName")),
                () -> tableRelationRepository.findByFunctionUnitId(functionUnitId).size());
        replaceSlice(preview, "formDefinitions", data.getFormDefinitions(), "formName",
                () -> formDefinitionRepository.findByFunctionUnitId(functionUnitId).size());
        replaceSlice(preview, "actionDefinitions", data.getActionDefinitions(), "actionName",
                () -> actionDefinitionRepository.findByFunctionUnitId(functionUnitId).size());
        replaceSlice(preview, "decisionDefinitions", data.getDecisionDefinitions(), "decisionKey",
                () -> decisionDefinitionRepository.findByFunctionUnitId(functionUnitId).size());
        if (data.getProcessDefinition() != null && data.getProcessDefinition().get("bpmnXml") instanceof String x && !x.isBlank()) {
            preview.getItems().add(Item.builder().slice("processDefinition").name("processDefinition").action(Action.REPLACE).build());
            preview.getReplacements().add(SliceReplacement.builder().slice("processDefinition")
                    .replacesExisting(processDefinitionRepository.findByFunctionUnitId(functionUnitId).isPresent() ? 1 : 0).build());
        }

        // ---- upsert 切片：与库里现状比对 ----
        if (data.getEmailTemplates() != null && !data.getEmailTemplates().isEmpty()) {
            Set<String> existing = new HashSet<>();
            for (EmailTemplate t : emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId)) existing.add(t.getName());
            upsertSlice(preview, "emailTemplates", data.getEmailTemplates(), m -> str(m.get("name")), m -> existing.contains(str(m.get("name"))));
        }
        if (data.getEmailConnections() != null && !data.getEmailConnections().isEmpty()) {
            Set<String> existing = new HashSet<>();
            for (EmailConnection c : emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId)) {
                existing.add(c.getName() + "|" + (c.getDirection() != null ? c.getDirection() : EmailConnectionDirection.OUTBOUND));
            }
            upsertSlice(preview, "emailConnections", data.getEmailConnections(), m -> str(m.get("name")),
                    m -> existing.contains(str(m.get("name")) + "|" + (m.get("direction") instanceof String d && !d.isBlank() ? d : "OUTBOUND")));
        }
        if (data.getEmailMonitorRules() != null && !data.getEmailMonitorRules().isEmpty()) {
            Set<String> existing = new HashSet<>();
            for (EmailMonitorRule r : emailMonitorRuleRepository
                    .findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(functionUnitId)) {
                existing.add(r.getName());
            }
            upsertSlice(preview, "emailMonitorRules", data.getEmailMonitorRules(), m -> str(m.get("name")), m -> existing.contains(str(m.get("name"))));
        }
        if (data.getMainTableViews() != null && !data.getMainTableViews().isEmpty()) {
            Map<Long, String> tableNameById = new HashMap<>();
            for (TableDefinition t : tableDefinitionRepository.findByFunctionUnitId(functionUnitId)) tableNameById.put(t.getId(), t.getTableName());
            Set<String> existing = new HashSet<>();
            for (MainTableViewConfig v : mainTableViewConfigRepository.findByFunctionUnitIdOrderByIsDefaultDescViewNameAsc(functionUnitId)) {
                existing.add(tableNameById.get(v.getMainTableId()) + "|" + v.getViewName());
            }
            upsertSlice(preview, "mainTableViews", data.getMainTableViews(), m -> str(m.get("viewName")),
                    m -> existing.contains(str(m.get("mainTableName")) + "|" + str(m.get("viewName"))));
        }
        if (data.getServiceTaskBindings() != null && !data.getServiceTaskBindings().isEmpty()) {
            Set<String> bound = new HashSet<>();
            ProcessDefinition pd = processDefinitionRepository.findByFunctionUnitId(functionUnitId).orElse(null);
            if (pd != null && pd.getBpmnXml() != null) {
                try {
                    for (BpmnServiceTaskScanner.ServiceTaskInfo t : BpmnServiceTaskScanner.scan(pd.getBpmnXml())) {
                        if (t.flowKey() != null || t.legacyFlowId() != null) bound.add(t.id());
                    }
                } catch (RuntimeException e) {
                    log.warn("Preview could not scan existing BPMN for bindings: {}", e.getMessage());
                }
            }
            for (Map<String, Object> b : data.getServiceTaskBindings()) {
                String id = str(b.get("serviceTaskId"));
                preview.getItems().add(Item.builder().slice("serviceTaskBindings").name(id)
                        .action(bound.contains(id) ? Action.REBIND : Action.BIND).build());
            }
        }
    }

    private static void replaceSlice(AiStudioProposalPreview preview, String slice, List<Map<String, Object>> entries,
                                     String nameKey, java.util.function.IntSupplier existingCount) {
        replaceSlice(preview, slice, entries, m -> str(m.get(nameKey)), existingCount);
    }

    private static void replaceSlice(AiStudioProposalPreview preview, String slice, List<Map<String, Object>> entries,
                                     Function<Map<String, Object>, String> name, java.util.function.IntSupplier existingCount) {
        if (entries == null || entries.isEmpty()) return;
        for (Map<String, Object> m : entries) {
            preview.getItems().add(Item.builder().slice(slice).name(name.apply(m)).action(Action.REPLACE).build());
        }
        preview.getReplacements().add(SliceReplacement.builder().slice(slice).replacesExisting(existingCount.getAsInt()).build());
    }

    private static void upsertSlice(AiStudioProposalPreview preview, String slice, List<Map<String, Object>> entries,
                                    Function<Map<String, Object>, String> name, java.util.function.Predicate<Map<String, Object>> exists) {
        for (Map<String, Object> m : entries) {
            preview.getItems().add(Item.builder().slice(slice).name(name.apply(m))
                    .action(exists.test(m) ? Action.UPDATE : Action.NEW).build());
        }
    }

    private static String str(Object o) {
        return o instanceof String s ? s.trim() : (o == null ? "" : String.valueOf(o));
    }
}
