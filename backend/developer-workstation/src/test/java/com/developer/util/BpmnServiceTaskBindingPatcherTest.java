package com.developer.util;

import com.developer.exception.AiGenerationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** service task → flow 绑定的 BPMN 定点补丁：只改点名任务的 ap 属性，其余原样。 */
class BpmnServiceTaskBindingPatcherTest {

    private static final String DEFINITIONS_OPEN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"%s>
              <bpmn:process id="p1" isExecutable="true">
            """;
    private static final String DEFINITIONS_CLOSE = """
                <bpmn:userTask id="u1" name="Review">
                  <bpmn:extensionElements><custom_x:properties xmlns:custom_x="http://custom.bpmn.io/schema">
                    <custom_x:property name="assigneeType" value="PROCESS_INITIATOR"/>
                  </custom_x:properties></bpmn:extensionElements>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="f1" sourceRef="u1" targetRef="svc_sync"/>
              </bpmn:process>
              <bpmndi:BPMNDiagram id="d1"><bpmndi:BPMNPlane id="pl1" bpmnElement="p1">
                <bpmndi:BPMNShape id="s1" bpmnElement="u1"><dc:Bounds x="10" y="10" width="100" height="80"/></bpmndi:BPMNShape>
              </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """;

    private static String bpmn(String extraNs, String serviceTasks) {
        return DEFINITIONS_OPEN.formatted(extraNs) + serviceTasks + DEFINITIONS_CLOSE;
    }

    private static BpmnServiceTaskScanner.ServiceTaskInfo task(String xml, String id) {
        return BpmnServiceTaskScanner.scan(xml).stream().filter(t -> id.equals(t.id())).findFirst().orElseThrow();
    }

    @Test
    void bareServiceTaskGetsPlatformContainerFlowKeyAndServiceType() {
        String xml = bpmn("", "<bpmn:serviceTask id=\"svc_sync\" name=\"Sync\"/>");

        String patched = BpmnServiceTaskBindingPatcher.bind(xml, Map.of("svc_sync", "invoice-sync"));

        BpmnServiceTaskScanner.ServiceTaskInfo info = task(patched, "svc_sync");
        assertEquals("invoice-sync", info.flowKey());
        assertEquals("ap", info.serviceType());
        assertNull(info.legacyFlowId());
        assertEquals(List.of("invoice-sync"), BpmnServiceTaskFlowRefs.extract(patched));
        // 无任何已声明前缀时按设计器写法声明 custom → 平台命名空间
        assertTrue(patched.contains("xmlns:custom=\"http://workflow.platform/schema/custom\""), patched);
        assertTrue(patched.contains("<custom:properties>") || patched.contains("<custom:properties "));
        // 其它节点与 DI 原样保留（DOM 序列化会按名排序属性，所以只断言属性存在，不断言顺序）
        assertTrue(patched.contains("name=\"assigneeType\" value=\"PROCESS_INITIATOR\""));
        assertTrue(patched.contains("bpmndi:BPMNShape") && patched.contains("bpmnElement=\"u1\"") && patched.contains("id=\"s1\""));
        assertTrue(patched.contains("bpmn:sequenceFlow") && patched.contains("id=\"f1\"") && patched.contains("targetRef=\"svc_sync\""));
    }

    @Test
    void existingPlatformContainerIsReusedKeyReplacedAndLegacyKeysCleared() {
        String xml = bpmn(" xmlns:custom=\"http://workflow.platform/schema/custom\"", """
                <bpmn:serviceTask id="svc_sync">
                  <bpmn:extensionElements><custom:properties>
                    <custom:property name="serviceType" value="ap"/>
                    <custom:property name="ap:flowId" value="old-env-id"/>
                    <custom:property name="ap:flowKey" value="old-key"/>
                    <custom:property name="ap:inputMapping" value="{&quot;a&quot;:1}"/>
                    <custom:property name="ap:outputMapping" value="{}"/>
                    <custom:property name="ap:timeoutSeconds" value="120"/>
                    <custom:property name="ap:retryCount" value="2"/>
                    <custom:property name="ap:webhookUrl" value="http://x"/>
                    <custom:property name="note" value="keep me"/>
                  </custom:properties></bpmn:extensionElements>
                </bpmn:serviceTask>
                """);

        String patched = BpmnServiceTaskBindingPatcher.bind(xml, Map.of("svc_sync", "new-key"));

        BpmnServiceTaskScanner.ServiceTaskInfo info = task(patched, "svc_sync");
        assertEquals("new-key", info.flowKey());
        assertEquals("ap", info.serviceType());
        assertNull(info.legacyFlowId());
        for (String legacy : BpmnServiceTaskBindingPatcher.LEGACY_AP_KEYS) {
            assertFalse(patched.contains("name=\"" + legacy + "\""), legacy + " must be cleared");
        }
        assertTrue(patched.contains("name=\"note\" value=\"keep me\""));
        assertEquals(1, countOccurrences(patched, "name=\"ap:flowKey\""));
        assertEquals(1, countOccurrences(patched, "name=\"serviceType\""));
        // 没有为平台命名空间再声明第二个前缀
        assertEquals(1, countOccurrences(patched, "http://workflow.platform/schema/custom"));
    }

    @Test
    void bpmnJsContainerIsReusedWhenThatIsTheOnlyOne() {
        String xml = bpmn(" xmlns:custom_1=\"http://custom.bpmn.io/schema\"", """
                <bpmn:serviceTask id="svc_sync">
                  <bpmn:extensionElements><custom_1:properties>
                    <custom_1:values name="ap:flowId" value="legacy"/>
                  </custom_1:properties></bpmn:extensionElements>
                </bpmn:serviceTask>
                """);

        String patched = BpmnServiceTaskBindingPatcher.bind(xml, Map.of("svc_sync", "k1"));

        assertEquals("k1", task(patched, "svc_sync").flowKey());
        assertTrue(patched.contains("<custom_1:property name=\"ap:flowKey\" value=\"k1\""));
        assertFalse(patched.contains("http://workflow.platform/schema/custom"));
        assertFalse(patched.contains("ap:flowId"));
    }

    @Test
    void onlyNamedTasksChangeAndNestedTasksAreReachable() {
        String xml = bpmn("", """
                <bpmn:serviceTask id="svc_a"/>
                <bpmn:subProcess id="sp"><bpmn:serviceTask id="svc_nested"/></bpmn:subProcess>
                """);

        String patched = BpmnServiceTaskBindingPatcher.bind(xml, Map.of("svc_nested", "nested-key"));

        assertEquals("nested-key", task(patched, "svc_nested").flowKey());
        BpmnServiceTaskScanner.ServiceTaskInfo untouched = task(patched, "svc_a");
        assertNull(untouched.flowKey());
        assertNull(untouched.serviceType());
        assertEquals(List.of("nested-key"), BpmnServiceTaskFlowRefs.extract(patched));
    }

    @Test
    void unknownTaskFailsLoudlyAndEmptyBindingsAreNoop() {
        String xml = bpmn("", "<bpmn:serviceTask id=\"svc_sync\"/>");

        AiGenerationException ex = assertThrows(AiGenerationException.class,
                () -> BpmnServiceTaskBindingPatcher.bind(xml, Map.of("nope", "k")));
        assertEquals("AI_BPMN_SERVICE_TASK_NOT_FOUND", ex.getErrorCode());

        assertSame(xml, BpmnServiceTaskBindingPatcher.bind(xml, Map.of()));
        assertThrows(AiGenerationException.class, () -> BpmnServiceTaskBindingPatcher.bind(xml, Map.of("svc_sync", " ")));
    }

    @Test
    void unbindClearsFlowKeyServiceTypeAndLegacyKeysButLeavesOtherTasks() {
        String xml = bpmn(" xmlns:custom=\"http://workflow.platform/schema/custom\"", """
                <bpmn:serviceTask id="svc_sync">
                  <bpmn:extensionElements><custom:properties>
                    <custom:property name="serviceType" value="ap"/>
                    <custom:property name="ap:flowKey" value="k"/>
                    <custom:property name="ap:inputMapping" value="{}"/>
                    <custom:property name="note" value="keep me"/>
                  </custom:properties></bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:serviceTask id="svc_other">
                  <bpmn:extensionElements><custom:properties>
                    <custom:property name="ap:flowKey" value="other"/>
                  </custom:properties></bpmn:extensionElements>
                </bpmn:serviceTask>
                """);

        String patched = BpmnServiceTaskBindingPatcher.unbind(xml, List.of("svc_sync"));

        BpmnServiceTaskScanner.ServiceTaskInfo unbound = task(patched, "svc_sync");
        assertNull(unbound.flowKey());
        assertNull(unbound.serviceType());
        assertNull(unbound.legacyFlowId());
        assertFalse(patched.contains("ap:inputMapping"));
        assertTrue(patched.contains("name=\"note\" value=\"keep me\""), "unrelated properties survive");
        assertEquals("other", task(patched, "svc_other").flowKey(), "other tasks are untouched");
    }

    @Test
    void unbindIsANoopForUnknownTasksOrEmptyInput() {
        String xml = bpmn("", "<bpmn:serviceTask id=\"svc_sync\"/>");
        assertSame(xml, BpmnServiceTaskBindingPatcher.unbind(xml, List.of()));
        // 未知 id 不抛：撤销时任务可能已被别的改动删掉
        assertEquals(List.of(), BpmnServiceTaskFlowRefs.extract(BpmnServiceTaskBindingPatcher.unbind(xml, List.of("ghost"))));
    }

    @Test
    void base64StoredProcessIsScannable() {
        String xml = bpmn("", "<bpmn:serviceTask id=\"svc_sync\"/>");
        String patched = BpmnServiceTaskBindingPatcher.bind(xml, Map.of("svc_sync", "k"));

        List<BpmnServiceTaskScanner.ServiceTaskInfo> fromEncoded =
                BpmnServiceTaskScanner.scan(XmlEncodingUtil.encode(patched));

        assertEquals(1, fromEncoded.size());
        assertEquals("k", fromEncoded.get(0).flowKey());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
