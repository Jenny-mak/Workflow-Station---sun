package com.developer.service.impl;

import com.developer.dto.AiGeneratedData;
import com.developer.dto.MainTableViewDtos.CreateMainTableViewRequest;
import com.developer.dto.MainTableViewDtos.MainTableViewAccessRuleDTO;
import com.developer.dto.MainTableViewDtos.MainTableViewDTO;
import com.developer.dto.MainTableViewDtos.MainTableViewFieldDTO;
import com.developer.dto.MainTableViewDtos.UpdateMainTableViewRequest;
import com.developer.entity.FormDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.entity.MainTableViewConfig;
import com.developer.entity.TableDefinition;
import com.developer.exception.AiGenerationException;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.MainTableViewConfigRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.service.MainTableViewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Studio VIEW_DESIGN 提案的写入：主表视图按 (mainTableName, viewName) upsert。
 *
 * <p>与邮件三阶段同一套纪律：经 {@link MainTableViewService} 落库（DRAFT 置位、BU+Role 成对校验、
 * MAIN 表拒绝详情表单、字段实体构造都由它负责），从不删除未提及的视图，也不碰 isDefault。
 * 不复用 {@code MainTableViewPortability#importAll}——它先整体删除再重建，是导入语义。</p>
 *
 * <p>提案里没给的键（sortConfig / filterConfig / fields / accessRules / restrictToInvolvedUsers）
 * 传 null 让服务跳过，已有视图的对应配置保持不变；detailFormName 只有键存在时才生效。</p>
 */
@Slf4j
@Component
public class AiViewProposalWriter {

    private final MainTableViewService mainTableViewService;
    private final MainTableViewConfigRepository mainTableViewConfigRepository;
    private final TableDefinitionRepository tableDefinitionRepository;
    private final FormDefinitionRepository formDefinitionRepository;

    public AiViewProposalWriter(MainTableViewService mainTableViewService,
                                MainTableViewConfigRepository mainTableViewConfigRepository,
                                TableDefinitionRepository tableDefinitionRepository,
                                FormDefinitionRepository formDefinitionRepository) {
        this.mainTableViewService = mainTableViewService;
        this.mainTableViewConfigRepository = mainTableViewConfigRepository;
        this.tableDefinitionRepository = tableDefinitionRepository;
        this.formDefinitionRepository = formDefinitionRepository;
    }

    public static boolean hasViewSlice(AiGeneratedData data) {
        return data != null && data.getMainTableViews() != null && !data.getMainTableViews().isEmpty();
    }

    public void write(FunctionUnit functionUnit, AiGeneratedData data) {
        if (!hasViewSlice(data)) return;
        Long functionUnitId = functionUnit.getId();

        Map<String, TableDefinition> tablesByName = new HashMap<>();
        for (TableDefinition t : tableDefinitionRepository.findByFunctionUnitIdWithFields(functionUnitId)) {
            tablesByName.put(t.getTableName(), t);
        }
        Map<String, Long> formIdByName = new HashMap<>();
        for (FormDefinition f : formDefinitionRepository.findByFunctionUnitId(functionUnitId)) {
            formIdByName.put(f.getFormName(), f.getId());
        }
        List<MainTableViewConfig> existing = mainTableViewConfigRepository.findByFunctionUnitIdWithFields(functionUnitId);

        int created = 0;
        int updated = 0;
        for (Map<String, Object> v : data.getMainTableViews()) {
            String mainTableName = str(v.get("mainTableName"));
            String viewName = str(v.get("viewName"));
            TableDefinition table = tablesByName.get(mainTableName);
            if (table == null) {
                // 引用校验已经挡过；这里是最后一道，宁可失败也不写出悬空视图
                throw new AiGenerationException("AI_WRITE_VIEW_TABLE_NOT_FOUND",
                        "View '" + viewName + "' references unknown table: " + mainTableName);
            }
            MainTableViewConfig current = existing.stream()
                    .filter(c -> table.getId().equals(c.getMainTableId()) && viewName.equals(c.getViewName()))
                    .findFirst()
                    .orElse(null);

            Long viewId;
            if (current == null) {
                MainTableViewDTO createdView = mainTableViewService.createView(functionUnitId,
                        new CreateMainTableViewRequest(viewName, table.getId()));
                viewId = createdView.id();
                created++;
            } else {
                viewId = current.getId();
                updated++;
            }

            Long detailFormId = current != null ? current.getDetailFormId() : null;
            if (v.containsKey("detailFormName")) {
                String formName = str(v.get("detailFormName"));
                detailFormId = formName == null ? null : formIdByName.get(formName);
                if (formName != null && detailFormId == null) {
                    throw new AiGenerationException("AI_WRITE_VIEW_FORM_NOT_FOUND",
                            "View '" + viewName + "' references unknown detail form: " + formName);
                }
            }

            mainTableViewService.updateView(functionUnitId, viewId, new UpdateMainTableViewRequest(
                    viewName,
                    v.get("restrictToInvolvedUsers") instanceof Boolean b ? b : null,
                    detailFormId,
                    v.containsKey("accessRules") ? toAccessRules(v.get("accessRules")) : null,
                    v.containsKey("sortConfig") ? toMapList(v.get("sortConfig")) : null,
                    v.containsKey("filterConfig") ? toMap(v.get("filterConfig")) : null,
                    v.containsKey("fields") ? toFields(v.get("fields")) : null));
        }
        log.info("AI view proposal applied: functionUnitId={}, created={}, updated={}", functionUnitId, created, updated);
    }

    private static List<MainTableViewAccessRuleDTO> toAccessRules(Object o) {
        List<MainTableViewAccessRuleDTO> out = new ArrayList<>();
        if (!(o instanceof List<?> list)) return out;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(MainTableViewAccessRuleDTO.builder()
                        .targetType(str(m.get("targetType")))
                        .targetId(str(m.get("targetId")))
                        .build());
            }
        }
        return out;
    }

    private static List<MainTableViewFieldDTO> toFields(Object o) {
        List<MainTableViewFieldDTO> out = new ArrayList<>();
        if (!(o instanceof List<?> list)) return out;
        int order = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            out.add(new MainTableViewFieldDTO(
                    str(m.get("fieldName")),
                    str(m.get("displayLabel")),
                    m.get("columnWidth") instanceof Number n ? n.intValue() : null,
                    m.get("sortOrder") instanceof Number n ? n.intValue() : order,
                    m.get("visible") instanceof Boolean b ? b : Boolean.TRUE,
                    m.get("systemField") instanceof Boolean b ? b : Boolean.FALSE,
                    null, null, null, null,
                    "field", // 结构校验只放行 field；lookup/fk 列留后续
                    null, null));
            order++;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toMapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(o instanceof List<?> list)) return out;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) out.add(new LinkedHashMap<>((Map<String, Object>) m));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object o) {
        if (o instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
        // 键存在但为 null/非对象：清空为设计器的空过滤形态
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("conditions", List.of());
        return empty;
    }

    private static String str(Object o) {
        if (!(o instanceof String s)) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
