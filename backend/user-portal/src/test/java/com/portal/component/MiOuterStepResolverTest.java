package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        MiOuterStepResolver resolver = new MiOuterStepResolver(workflowEngineClient);

        MiOuterStepResolver.OuterLookup lookup = resolver.lookup("proc", "sub form1");

        assertThat(lookup.isUnknown()).isTrue();
        assertThat(lookup.isInner()).isFalse();
    }

    @Test
    @DisplayName("missing BPMN XML is unknown, not an empty MI map")
    void missingXmlIsUnknown() {
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        when(workflowEngineClient.getBpmnXml("proc")).thenReturn(Optional.empty());
        MiOuterStepResolver resolver = new MiOuterStepResolver(workflowEngineClient);

        assertThat(resolver.lookup("proc", "Review").isUnknown()).isTrue();
    }

    @Test
    @DisplayName("blank node is known non-inner")
    void blankNodeIsNotInner() {
        MiOuterStepResolver resolver = new MiOuterStepResolver(workflowEngineClient);
        MiOuterStepResolver.OuterLookup lookup = resolver.lookup("proc", "  ");
        assertThat(lookup.resolved()).isTrue();
        assertThat(lookup.isInner()).isFalse();
    }
}
