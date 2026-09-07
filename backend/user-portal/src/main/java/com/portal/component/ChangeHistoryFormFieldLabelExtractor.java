package com.portal.component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Walks designer {@code rule} trees including nested layout {@code children}
 * (elCard / fcRow / col) and sub-form fields so Change History labels are not
 * an incomplete top-level allow-list.
 */
final class ChangeHistoryFormFieldLabelExtractor {

    private ChangeHistoryFormFieldLabelExtractor() {
    }

    @SuppressWarnings("unchecked")
    static void walk(Map<String, Object> config, Map<String, String> labels, Consumer<String> collector) {
        Object rule = config.get("rule");
        if (rule instanceof List<?> rules) {
            for (Object item : rules) {
                if (item instanceof Map<?, ?> ruleItem) {
                    collectRuleItem(ruleItem, labels, collector);
                }
            }
        }
        Object subForms = config.get("subForms");
        if (subForms instanceof Map<?, ?> subMap) {
            for (Object subConfig : subMap.values()) {
                if (subConfig instanceof Map<?, ?> sc) {
                    walk((Map<String, Object>) sc, labels, collector);
                }
            }
        }
    }

    private static void collectRuleItem(
            Map<?, ?> ruleItem,
            Map<String, String> labels,
            Consumer<String> collector) {
        Object field = ruleItem.get("field");
        Object title = ruleItem.get("title");
        if (field instanceof String fieldName && !fieldName.isBlank()) {
            if (title instanceof String titleText && !titleText.isBlank() && labels != null) {
                labels.putIfAbsent(fieldName, titleText);
            }
            if (collector != null) {
                collector.accept(fieldName);
            }
        }
        Object children = ruleItem.get("children");
        if (children instanceof List<?>) {
            walk(Map.of("rule", children), labels, collector);
        }
    }
}
