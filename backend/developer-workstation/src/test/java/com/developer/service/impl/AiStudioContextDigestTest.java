package com.developer.service.impl;

import com.developer.dto.FunctionUnitContextDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 顾问轮的设计摘要：按阶段裁剪、名称级、不含大字段、有字符预算。 */
class AiStudioContextDigestTest {

    private final AiStudioContextDigest digest = new AiStudioContextDigest();

    private static FunctionUnitContextDTO fullContext() {
        FunctionUnitContextDTO c = new FunctionUnitContextDTO();
        c.setTableDefinitions(List.of(
                Map.of("tableName", "orders", "tableType", "MAIN", "tableDisplayName", "Orders",
                        "fieldDefinitions", List.of(
                                Map.of("fieldName", "id", "dataType", "BIGINT", "isPrimaryKey", true),
                                Map.of("fieldName", "order_no", "dataType", "VARCHAR")),
                        "foreignKeys", List.of()),
                Map.of("tableName", "order_lines", "tableType", "SUB",
                        "fieldDefinitions", List.of(
                                Map.of("fieldName", "id", "dataType", "BIGINT", "isPrimaryKey", true),
                                Map.of("fieldName", "order_id", "dataType", "BIGINT")),
                        "foreignKeys", List.of(Map.of("fieldName", "order_id", "refTableName", "orders", "refFieldName", "id")))));
        c.setTableRelations(List.of(Map.of("sourceTableName", "orders", "sourceFieldName", "id",
                "relationType", "ONE_TO_MANY", "targetTableName", "order_lines", "targetFieldName", "order_id")));
        c.setFormDefinitions(List.of(Map.of("formName", "order_form", "formType", "PROCESS",
                "configJson", Map.of("secretLayout", "x".repeat(5000)),
                "tableBindings", List.of(Map.of("tableName", "orders", "bindingType", "PRIMARY")),
                "stageBindings", List.of(Map.of("stageId", "task_submit", "stageName", "Submit")))));
        c.setActionDefinitions(List.of(Map.of("actionName", "submit", "actionType", "PROCESS_SUBMIT",
                "configJson", Map.of("k", "v"), "isDefault", true)));
        c.setDecisionDefinitions(List.of(Map.of("decisionKey", "credit_check", "decisionName", "Credit check",
                "hitPolicy", "FIRST", "dmnXml", "<definitions>" + "y".repeat(4000) + "</definitions>")));
        c.setProcessDefinition(Map.of("bpmnXml", """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p1">
                    <bpmn:startEvent id="start_1"/>
                    <bpmn:userTask id="task_submit" name="Submit request"/>
                    <bpmn:endEvent id="end_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """));
        c.setMainTableViews(List.of(Map.of("mainTableName", "orders", "viewName", "Pending", "isDefault", false,
                "status", "DRAFT", "fields", List.of(Map.of("fieldName", "order_no")),
                "accessRules", List.of(Map.of("targetType", "ROLE", "targetId", "r1")))));
        c.setOrgCatalog(Map.of("businessUnits", List.of(Map.of("id", "bu-1", "name", "Finance",
                "roles", List.of(Map.of("id", "role-1", "name", "Approver")))), "truncated", false));
        c.setServiceTasks(List.of(
                Map.of("id", "svc_sync", "name", "Sync", "flowKey", "invoice-sync"),
                Map.of("id", "svc_bare")));
        c.setAutomationFlows(List.of(Map.of("flowKey", "invoice-sync", "displayName", "Invoice sync",
                "published", true, "workspace", "Public")));
        c.setEmailConnections(List.of(Map.of("name", "inbox@x.com", "connectionType", "GMAIL",
                "direction", "INBOUND", "enabled", true, "hasCredentials", true)));
        c.setEmailTemplates(List.of(Map.of("name", "Order Shipped", "subject", "Order ${order_no} shipped",
                "bodyHtml", "<p>" + "z".repeat(4000) + "</p>", "enabled", true)));
        c.setEmailMonitorRules(List.of(Map.of("name", "Invoice inbox", "connectionName", "inbox@x.com",
                "actionType", "START_PROCESS", "enabled", true)));
        return c;
    }

    @Test
    void tableDesignShowsTablesFieldsKeysAndRelationsOnly() {
        String out = digest.digest("TABLE_DESIGN", fullContext());

        assertTrue(out.contains("### Tables and fields"));
        assertTrue(out.contains("orders (MAIN) \"Orders\": id:BIGINT [PK], order_no:VARCHAR"));
        assertTrue(out.contains("order_id:BIGINT -> orders.id"), out);
        assertTrue(out.contains("orders.id --ONE_TO_MANY--> order_lines.order_id"));
        // 其它阶段的切片不出现
        assertFalse(out.contains("### Forms"));
        assertFalse(out.contains("### Email templates"));
        assertFalse(out.contains("Process nodes"));
    }

    @Test
    void processDesignSummarisesNodesInsteadOfShippingBpmn() {
        String out = digest.digest("PROCESS_DESIGN", fullContext());

        assertTrue(out.contains("start_1 (startEvent)"));
        assertTrue(out.contains("task_submit (userTask) \"Submit request\""));
        assertTrue(out.contains("task_submit -> order_form"), out);
        assertFalse(out.contains("<bpmn:"), "BPMN XML must never reach the chat prompt");
    }

    @Test
    void perPhaseSlicesAreScopedAndBigFieldsAreNeverIncluded() {
        FunctionUnitContextDTO c = fullContext();

        String emails = digest.digest("EMAIL_TEMPLATES", c);
        assertTrue(emails.contains("Order Shipped subject=\"Order ${order_no} shipped\""));
        assertTrue(emails.contains("orders: id, order_no"), emails);
        assertFalse(emails.contains("zzz"), "template bodyHtml must not be included");
        assertFalse(emails.contains("### Forms"));

        String automation = digest.digest("AUTOMATION", c);
        assertTrue(automation.contains("svc_sync \"Sync\" -> flowKey=invoice-sync"));
        assertTrue(automation.contains("svc_bare (not bound)"));
        assertTrue(automation.contains("invoice-sync \"Invoice sync\" [published]"));
        assertFalse(automation.contains("### Email"));

        String views = digest.digest("VIEW_DESIGN", c);
        assertTrue(views.contains("orders / Pending"));
        assertTrue(views.contains("columns=1 accessRules=1"));
        assertTrue(views.contains("Finance (bu-1): Approver (role-1)"));

        String decisions = digest.digest("DECISION_DESIGN", c);
        assertTrue(decisions.contains("credit_check \"Credit check\" hitPolicy=FIRST"));
        assertFalse(decisions.contains("yyy"), "DMN XML must not be included");

        String forms = digest.digest("FORM_DESIGN", c);
        assertTrue(forms.contains("order_form (PROCESS) tables: orders/PRIMARY stages: task_submit"));
        assertFalse(forms.contains("xxx"), "form configJson must not be included");

        String monitors = digest.digest("EMAIL_MONITORS", c);
        assertTrue(monitors.contains("Invoice inbox connection=inbox@x.com action=START_PROCESS"));
        assertTrue(monitors.contains("inbox@x.com (GMAIL, INBOUND) enabled, credentials set"));

        String validation = digest.digest("VALIDATION", c);
        assertTrue(validation.contains("tables=2"));
        assertTrue(validation.contains("process=defined (3 nodes)"));
    }

    @Test
    void emptyFunctionUnitSaysSoInsteadOfEmittingBlankHeadings() {
        String out = digest.digest("TABLE_DESIGN", new FunctionUnitContextDTO());

        assertTrue(out.contains("### Tables and fields\n(none yet)"), out);
        assertTrue(out.contains("### Table relations\n(none yet)"));
        assertEquals("", digest.digest("TABLE_DESIGN", null));
    }

    @Test
    void digestStaysWithinItsCharBudgetAndMarksWhatWasCut() {
        FunctionUnitContextDTO c = new FunctionUnitContextDTO();
        List<Map<String, Object>> tables = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            tables.add(Map.of("tableName", "table_" + i, "tableType", "SUB",
                    "fieldDefinitions", List.of(Map.of("fieldName", "a_long_field_name_" + i, "dataType", "VARCHAR")),
                    "foreignKeys", List.of()));
        }
        c.setTableDefinitions(tables);

        String out = digest.digest("TABLE_DESIGN", c);

        assertTrue(out.length() <= AiStudioContextDigest.DIGEST_CHAR_BUDGET, "budget respected, got " + out.length());
        assertTrue(out.contains("…(truncated,"), out.substring(Math.max(0, out.length() - 200)));
        assertTrue(out.contains("table_0"), "the first entries survive truncation");
    }
}
