package com.developer.service.impl;

import com.developer.dto.AiGeneratedData;
import com.developer.entity.FunctionUnit;
import com.developer.entity.ProcessDefinition;
import com.developer.exception.AiGenerationException;
import com.developer.util.BpmnServiceTaskFlowRefs;
import com.developer.util.BpmnServiceTaskScanner;
import com.developer.util.XmlEncodingUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AUTOMATION 提案落库：解码 → 定点补丁 → 与设计器同款 Base64 回写；无流程定义显式失败。 */
class AiServiceTaskBindingWriterTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <bpmn:process id="p1">
                <bpmn:serviceTask id="svc_a" name="A"/>
                <bpmn:serviceTask id="svc_b" name="B"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private final AiServiceTaskBindingWriter writer = new AiServiceTaskBindingWriter();

    private static FunctionUnit unitWithProcess(String storedXml) {
        FunctionUnit fu = new FunctionUnit();
        fu.setId(7L);
        if (storedXml != null) {
            ProcessDefinition pd = new ProcessDefinition();
            pd.setBpmnXml(storedXml);
            fu.setProcessDefinition(pd);
        }
        return fu;
    }

    private static AiGeneratedData bindings(Map<String, Object>... entries) {
        return AiGeneratedData.builder().serviceTaskBindings(List.of(entries)).build();
    }

    @Test
    void patchesPlainStoredProcessAndWritesItBackEncoded() {
        FunctionUnit fu = unitWithProcess(BPMN);

        writer.write(fu, bindings(Map.of("serviceTaskId", "svc_a", "flowKey", "invoice-sync")));

        String stored = fu.getProcessDefinition().getBpmnXml();
        assertTrue(XmlEncodingUtil.isBase64Encoded(stored), "written back with the designer's Base64 encoding");
        String decoded = XmlEncodingUtil.smartDecode(stored);
        assertEquals(List.of("invoice-sync"), BpmnServiceTaskFlowRefs.extract(decoded));
        List<BpmnServiceTaskScanner.ServiceTaskInfo> tasks = BpmnServiceTaskScanner.scan(decoded);
        assertEquals("ap", tasks.get(0).serviceType());
        assertNull(tasks.get(1).flowKey(), "svc_b is untouched");
        assertNull(tasks.get(1).serviceType());
    }

    @Test
    void patchesBase64StoredProcessToo() {
        FunctionUnit fu = unitWithProcess(XmlEncodingUtil.encode(BPMN));

        writer.write(fu, bindings(
                Map.of("serviceTaskId", "svc_a", "flowKey", "k-a"),
                Map.of("serviceTaskId", "svc_b", "flowKey", "k-b")));

        String decoded = XmlEncodingUtil.smartDecode(fu.getProcessDefinition().getBpmnXml());
        assertEquals(List.of("k-a", "k-b"), BpmnServiceTaskFlowRefs.extract(decoded));
    }

    @Test
    void missingProcessOrBlankBindingFailsLoudly() {
        AiGenerationException noProcess = assertThrows(AiGenerationException.class,
                () -> writer.write(unitWithProcess(null), bindings(Map.of("serviceTaskId", "svc_a", "flowKey", "k"))));
        assertEquals("AI_WRITE_PROCESS_MISSING", noProcess.getErrorCode());

        FunctionUnit fu = unitWithProcess(BPMN);
        AiGenerationException blank = assertThrows(AiGenerationException.class,
                () -> writer.write(fu, bindings(Map.of("serviceTaskId", "svc_a", "flowKey", ""))));
        assertEquals("AI_BPMN_SERVICE_TASK_BINDING_INVALID", blank.getErrorCode());
        assertEquals(BPMN, fu.getProcessDefinition().getBpmnXml(), "nothing written on failure");

        AiGenerationException unknown = assertThrows(AiGenerationException.class,
                () -> writer.write(fu, bindings(Map.of("serviceTaskId", "ghost", "flowKey", "k"))));
        assertEquals("AI_BPMN_SERVICE_TASK_NOT_FOUND", unknown.getErrorCode());
    }

    @Test
    void hasBindingSliceDetectsPresence() {
        assertFalse(AiServiceTaskBindingWriter.hasBindingSlice(null));
        assertFalse(AiServiceTaskBindingWriter.hasBindingSlice(AiGeneratedData.builder().build()));
        assertFalse(AiServiceTaskBindingWriter.hasBindingSlice(AiGeneratedData.builder().serviceTaskBindings(List.of()).build()));
        assertTrue(AiServiceTaskBindingWriter.hasBindingSlice(bindings(Map.of("serviceTaskId", "a", "flowKey", "k"))));
    }
}
