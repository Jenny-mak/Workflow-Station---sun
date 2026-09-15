package com.developer.service.impl;

import com.developer.dto.AiGeneratedData;
import com.developer.entity.FunctionUnit;
import com.developer.entity.ProcessDefinition;
import com.developer.exception.AiGenerationException;
import com.developer.util.BpmnServiceTaskBindingPatcher;
import com.developer.util.XmlEncodingUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Studio AUTOMATION 提案的写入：把 {@code serviceTaskBindings} 打成现有流程定义上的定点补丁。
 *
 * <p>读取时 {@link XmlEncodingUtil#smartDecode}（兼容 AI Generate 落的明文与设计器落的 Base64），
 * 写回时与设计器 {@code ProcessDesignComponentImpl} 同款 {@link XmlEncodingUtil#encode}。
 * 只改属性，不重跑流程拓扑校验；从不解绑、从不触碰未提及的任务。</p>
 */
@Slf4j
@Component
public class AiServiceTaskBindingWriter {

    public static boolean hasBindingSlice(AiGeneratedData data) {
        return data != null && data.getServiceTaskBindings() != null && !data.getServiceTaskBindings().isEmpty();
    }

    public void write(FunctionUnit functionUnit, AiGeneratedData data) {
        if (!hasBindingSlice(data)) return;
        ProcessDefinition pd = functionUnit.getProcessDefinition();
        if (pd == null || pd.getBpmnXml() == null || pd.getBpmnXml().isBlank()) {
            throw new AiGenerationException("AI_WRITE_PROCESS_MISSING",
                    "The function unit has no process definition; design the process before binding service tasks");
        }
        Map<String, String> flowKeyByTaskId = toBindingMap(data.getServiceTaskBindings());
        String patched = BpmnServiceTaskBindingPatcher.bind(XmlEncodingUtil.smartDecode(pd.getBpmnXml()), flowKeyByTaskId);
        pd.setBpmnXml(XmlEncodingUtil.encode(patched));
        log.info("AI service task bindings applied: functionUnitId={}, tasks={}", functionUnit.getId(), flowKeyByTaskId.keySet());
    }

    static Map<String, String> toBindingMap(List<Map<String, Object>> bindings) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> b : bindings) {
            String taskId = b.get("serviceTaskId") instanceof String s ? s.trim() : "";
            String flowKey = b.get("flowKey") instanceof String s ? s.trim() : "";
            if (taskId.isEmpty() || flowKey.isEmpty()) {
                throw new AiGenerationException("AI_BPMN_SERVICE_TASK_BINDING_INVALID",
                        "serviceTaskId and flowKey must not be blank");
            }
            out.put(taskId, flowKey);
        }
        return out;
    }
}
