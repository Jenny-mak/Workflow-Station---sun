package com.developer.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BPMN 节点摘要：一行一个节点，子流程缩进，坏输入返回空列表。 */
class BpmnNodeSummaryTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:flowable="http://flowable.org/bpmn">
              <bpmn:process id="p1" isExecutable="true">
                <bpmn:startEvent id="start_1"/>
                <bpmn:userTask id="task_submit" name="Submit request"/>
                <bpmn:exclusiveGateway id="gw_1" name="Approved?"/>
                <bpmn:subProcess id="sp_items" name="Per item">
                  <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                    <bpmn:extensionElements>
                      <flowable:collection>multiInstance_order_lines_collection</flowable:collection>
                      <flowable:elementVariable>currentItem</flowable:elementVariable>
                    </bpmn:extensionElements>
                  </bpmn:multiInstanceLoopCharacteristics>
                  <bpmn:userTask id="task_item" name="Handle item"/>
                </bpmn:subProcess>
                <bpmn:endEvent id="end_1"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    void summarisesMainNodesWithIdTypeAndName() {
        List<String> lines = BpmnNodeSummary.summarize(BPMN);

        assertTrue(lines.contains("start_1 (startEvent)"), String.valueOf(lines));
        assertTrue(lines.contains("task_submit (userTask) \"Submit request\""));
        assertTrue(lines.contains("gw_1 (exclusiveGateway) \"Approved?\""));
        assertTrue(lines.contains("end_1 (endEvent)"));
        assertTrue(lines.stream().noneMatch(l -> l.contains("<")), "no XML leaks into the summary");
    }

    @Test
    void subProcessNodesAreIndentedAndMultiInstanceIsFlagged() {
        List<String> lines = BpmnNodeSummary.summarize(BPMN);

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("sp_items (subProcess) \"Per item\" [multi-instance, parallel]")),
                String.valueOf(lines));
        assertTrue(lines.contains("  task_item (userTask) \"Handle item\""), String.valueOf(lines));
    }

    @Test
    void base64StoredProcessIsDecodedFirst() {
        assertEquals(BpmnNodeSummary.summarize(BPMN), BpmnNodeSummary.summarize(XmlEncodingUtil.encode(BPMN)));
    }

    @Test
    void blankOrUnparseableInputYieldsAnEmptyList() {
        assertEquals(List.of(), BpmnNodeSummary.summarize(null));
        assertEquals(List.of(), BpmnNodeSummary.summarize("   "));
        assertEquals(List.of(), BpmnNodeSummary.summarize("not xml at all"));
    }
}
