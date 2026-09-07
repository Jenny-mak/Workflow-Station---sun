package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Reuses {@link BpmnMiXmlSupport#buildMiInnerTaskNameToSubProcessName} so Owner Case Handler
 * and My Request Current Step share one MI outer-box name.
 *
 * <p>Write paths must distinguish "engine/BPMN unavailable" from "this node is not inner MI".
 * The former must not be treated as ordinary-task people.</p>
 */
@Component
@RequiredArgsConstructor
public class MiOuterStepResolver {

    private final WorkflowEngineClient workflowEngineClient;
    private final MiBpmnNameMapCache cache = new MiBpmnNameMapCache();

    /**
     * Resolved lookup: {@code outerName} blank means the node is not an inner MI task.
     * Unresolved means BPMN could not be loaded — callers must not overwrite MAIN Handler.
     */
    public record OuterLookup(boolean resolved, String outerName) {
        public static OuterLookup unknown() {
            return new OuterLookup(false, null);
        }

        public static OuterLookup known(String outerName) {
            return new OuterLookup(true, outerName);
        }

        public boolean isUnknown() {
            return !resolved;
        }

        public boolean isInner() {
            return resolved && outerName != null && !outerName.isBlank();
        }
    }

    public OuterLookup lookup(String processDefinitionKey, String currentNode) {
        if (processDefinitionKey == null || processDefinitionKey.isBlank()
                || currentNode == null || currentNode.isBlank()) {
            return OuterLookup.known(null);
        }
        if (!workflowEngineClient.isAvailable()) {
            return OuterLookup.unknown();
        }
        Map<String, String> present = cache.getIfPresent(processDefinitionKey);
        if (present != null) {
            return OuterLookup.known(OwnerCaseHandlerCalculator.outerNameIfInner(currentNode, present));
        }
        Optional<String> xml = workflowEngineClient.getBpmnXml(processDefinitionKey);
        if (xml.isEmpty()) {
            return OuterLookup.unknown();
        }
        Map<String, String> map = cache.getOrLoad(processDefinitionKey, () ->
                BpmnMiXmlSupport.buildMiInnerTaskNameToSubProcessName(xml.get()));
        return OuterLookup.known(OwnerCaseHandlerCalculator.outerNameIfInner(currentNode, map));
    }
}
