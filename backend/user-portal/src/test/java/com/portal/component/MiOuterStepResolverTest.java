package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MiOuterStepResolver")
class MiOuterStepResolverTest {

    @Mock
    private WorkflowEngineClient workflowEngineClient;

    @Test
    @DisplayName("engine down is unknown, not ordinary-task")
    void engineDownIsUnknown() {
        when(workflowEngineClient.isAvailable()).thenReturn(false);
        MiOuterStepResolver resolver = new MiOuterStepResolver(workflowEngineClient, new MiBpmnNameMapCache());

        MiOuterStepResolver.OuterLookup lookup = resolver.lookup("proc", "sub form1");

        assertThat(lookup.isUnknown()).isTrue();
        assertThat(lookup.isInner()).isFalse();
    }

    @Test
    @DisplayName("missing BPMN XML is unknown, not an empty MI map")
    void missingXmlIsUnknown() {
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        when(workflowEngineClient.getBpmnXml("proc")).thenReturn(Optional.empty());
        MiOuterStepResolver resolver = new MiOuterStepResolver(workflowEngineClient, new MiBpmnNameMapCache());

        assertThat(resolver.lookup("proc", "Review").isUnknown()).isTrue();
    }

    @Test
    @DisplayName("blank node is known non-inner")
    void blankNodeIsNotInner() {
        MiOuterStepResolver resolver = new MiOuterStepResolver(workflowEngineClient, new MiBpmnNameMapCache());
        MiOuterStepResolver.OuterLookup lookup = resolver.lookup("proc", "  ");
        assertThat(lookup.resolved()).isTrue();
        assertThat(lookup.isInner()).isFalse();
    }

    @Test
    @DisplayName("list mapsFor and Owner lookup share one BPMN fetch")
    void listMapsAndOwnerLookupShareCache() {
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        when(workflowEngineClient.getBpmnXml("proc")).thenReturn(Optional.of(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="proc">
                    <subProcess id="multi" name="multi">
                      <multiInstanceLoopCharacteristics/>
                      <userTask id="t1" name="sub form1"/>
                    </subProcess>
                  </process>
                </definitions>
                """));
        MiBpmnNameMapCache cache = new MiBpmnNameMapCache();
        MiOuterStepResolver list = new MiOuterStepResolver(workflowEngineClient, cache);
        MiOuterStepResolver writer = new MiOuterStepResolver(workflowEngineClient, cache);

        assertThat(list.mapsFor(List.of("proc")).get("proc")).containsEntry("sub form1", "multi");
        MiOuterStepResolver.OuterLookup lookup = writer.lookup("proc", "sub form1");

        assertThat(lookup.isInner()).isTrue();
        assertThat(lookup.outerName()).isEqualTo("multi");
        verify(workflowEngineClient, times(1)).getBpmnXml("proc");
    }
}
