package com.portal.component;

import java.util.Map;

record ChangeHistoryBindingAliases(
        Map<String, String> aliasToBinding,
        Map<String, String> bindingToHistoryName,
        Map<String, Integer> aliasPriorities) {
}
