package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Single loader for inner-user-task → outer multi-instance box name.
 * My Request Current Step / Current Assignee and Owner MAIN Case Handler writes
 * both consume {@link OwnerCaseHandlerCalculator} plus this map — not a second algorithm.
 *
 * <p>Write paths must distinguish "engine/BPMN unavailable" from "this node is not inner MI".
 * The former must not be treated as ordinary-task people.</p>
 */
@Component
public class MiOuterStepResolver {

    private final WorkflowEngineClient workflowEngineClient;
    private final MiBpmnNameMapCache cache;

    public MiOuterStepResolver(WorkflowEngineClient workflowEngineClient, MiBpmnNameMapCache cache) {
        this.workflowEngineClient = workflowEngineClient;
        this.cache = cache == null ? new MiBpmnNameMapCache() : cache;
    }

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
        return mapIfResolved(processDefinitionKey)
                .map(map -> OuterLookup.known(OwnerCaseHandlerCalculator.outerNameIfInner(currentNode, map)))
                .orElseGet(OuterLookup::unknown);
    }

    /**
     * Batch load for a My Request page. Missing keys mean unknown (do not treat as "not MI").
     */
    public Map<String, Map<String, String>> mapsFor(Collection<String> processDefinitionKeys) {
        Map<String, Map<String, String>> byKey = new LinkedHashMap<>();
        if (processDefinitionKeys == null) {
            return byKey;
        }
        for (String key : processDefinitionKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            mapIfResolved(key).ifPresent(map -> byKey.put(key, map));
        }
        return byKey;
    }

    /**
     * Empty = BPMN could not be loaded. Present (even empty map) = resolved for this definition.
     */
    Optional<Map<String, String>> mapIfResolved(String processDefinitionKey) {
        if (processDefinitionKey == null || processDefinitionKey.isBlank()) {
            return Optional.of(Map.of());
        }
        if (!workflowEngineClient.isAvailable()) {
            return Optional.empty();
        }
        Map<String, String> present = cache.getIfPresent(processDefinitionKey);
        if (present != null) {
            return Optional.of(present);
        }
        try {
            Optional<String> xml = workflowEngineClient.getBpmnXml(processDefinitionKey);
            if (xml.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(cache.getOrLoad(processDefinitionKey, () ->
                    BpmnMiXmlSupport.buildMiInnerTaskNameToSubProcessName(xml.get())));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
