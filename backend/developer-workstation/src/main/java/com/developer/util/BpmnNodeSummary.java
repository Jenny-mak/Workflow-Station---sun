package com.developer.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * BPMN 的节点摘要：一行一个节点，给模型看流程结构用，不带 XML。
 *
 * <p>AI Studio 的顾问式对话需要知道"流程里有哪些节点"，但整份 BPMN（含 DI 坐标）动辄几十 KB，
 * 塞进对话 prompt 会把窗口占满且拖慢每一轮。这里复用同包的 {@link BpmnGraphParser} 出节点清单，
 * 子流程内的节点缩进一层并标出多实例。</p>
 *
 * <p>解析失败返回空列表——摘要是辅助信息，调用方按"没有流程"渲染即可，不该让一次对话失败。</p>
 */
@Slf4j
public final class BpmnNodeSummary {

    private BpmnNodeSummary() {
    }

    /**
     * @param bpmnXml 明文或 Base64 的 BPMN
     * @return 每行形如 {@code task_submit (userTask) "Submit request"}；子流程节点前缀两个空格
     */
    public static List<String> summarize(String bpmnXml) {
        if (bpmnXml == null || bpmnXml.isBlank()) {
            return List.of();
        }
        try {
            String xml = XmlEncodingUtil.smartDecode(bpmnXml);
            BpmnGraphParser.ParsedBpmnGraph graph = BpmnGraphParser.parse(xml);
            List<String> lines = new ArrayList<>();
            for (BpmnProcessSimulator.NodeInfo node : graph.mainNodes().values()) {
                lines.add(line(node, ""));
            }
            for (BpmnGraphParser.SubProcessScope scope : graph.subProcesses().values()) {
                String mi = scope.miLoop().isPresent()
                        ? " [multi-instance" + (scope.miLoop().get().sequential() ? ", sequential]" : ", parallel]")
                        : "";
                lines.add(scope.id() + " (subProcess)"
                        + (scope.name() != null && !scope.name().isBlank() ? " \"" + scope.name() + "\"" : "") + mi);
                for (BpmnProcessSimulator.NodeInfo node : scope.nodes().values()) {
                    lines.add(line(node, "  "));
                }
            }
            return lines;
        } catch (RuntimeException e) {
            log.warn("BPMN node summary failed; treating the process as unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    private static String line(BpmnProcessSimulator.NodeInfo node, String indent) {
        String name = node.name();
        return indent + node.id() + " (" + node.type() + ")"
                + (name != null && !name.isBlank() ? " \"" + name + "\"" : "");
    }
}
