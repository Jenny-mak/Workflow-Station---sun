package com.developer.service;

import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiValidationResult;
import com.developer.service.impl.AiValidationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AiValidationService 单元测试
 * 校验具体场景示例：BPMN XML 格式、枚举值边界条件、SVG 安全校验
 */
class AiValidationServiceTest {

    private AiValidationServiceImpl validationService;

    @BeforeEach
    void setUp() {
        validationService = new AiValidationServiceImpl();
    }

    // ==================== BPMN XML Validation ====================

    @Test
    void validate_validBpmnXml_shouldPass() {
        String validBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             targetNamespace="http://example.com">
                  <process id="process1" isExecutable="true">
                    <startEvent id="start"/>
                  </process>
                </definitions>
                """;

        AiGeneratedData data = AiGeneratedData.builder()
                .processDefinition(Map.of("bpmnXml", validBpmn))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertTrue(result.isValid(), "Valid BPMN XML should pass validation");
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void validate_invalidBpmnXml_shouldFail() {
        String invalidXml = "<not-valid-xml><unclosed>";

        AiGeneratedData data = AiGeneratedData.builder()
                .processDefinition(Map.of("bpmnXml", invalidXml))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid(), "Invalid XML should fail validation");
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "BPMN_VALIDATION".equals(e.getErrorType())));
    }

    // ==================== Enum Value Validation ====================

    @Test
    void validate_validEnumValues_shouldPass() {
        AiGeneratedData data = AiGeneratedData.builder()
                .tableDefinitions(List.of(Map.of(
                        "tableName", "test_table",
                        "tableType", "MAIN",
                        "fieldDefinitions", List.of(Map.of(
                                "fieldName", "id",
                                "dataType", "BIGINT",
                                "isPrimaryKey", true
                        ))
                )))
                .formDefinitions(List.of(Map.of(
                        "formName", "test_form",
                        "formType", "PROCESS"
                )))
                .actionDefinitions(List.of(Map.of(
                        "actionName", "test_action",
                        "actionType", "APPROVE"
                )))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertTrue(result.isValid(), "All valid enum values should pass");
    }

    @Test
    void validate_invalidTableType_shouldFail() {
        AiGeneratedData data = AiGeneratedData.builder()
                .tableDefinitions(List.of(Map.of(
                        "tableName", "test_table",
                        "tableType", "INVALID_TYPE",
                        "fieldDefinitions", List.of(Map.of(
                                "fieldName", "id",
                                "dataType", "BIGINT",
                                "isPrimaryKey", true
                        ))
                )))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid(), "Invalid tableType should fail");
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "INVALID_ENUM".equals(e.getErrorType())
                        && e.getFieldPath().contains("tableType")));
    }

    // ==================== SVG Validation ====================

    @Test
    void validate_svgWithScript_shouldFail() {
        String maliciousSvg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="48" height="48">
                  <script>alert('xss')</script>
                  <circle cx="24" cy="24" r="20"/>
                </svg>
                """;

        AiGeneratedData data = AiGeneratedData.builder()
                .icon(Map.of(
                        "name", "test-icon",
                        "category", "GENERAL",
                        "svgContent", maliciousSvg
                ))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid(), "SVG with script tag should fail");
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "SVG_VALIDATION".equals(e.getErrorType())
                        && e.getDescription().contains("script")));
    }

    @Test
    void validate_validSvg_shouldPass() {
        String cleanSvg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 48 48">
                  <circle cx="24" cy="24" r="20" fill="#4A90D9"/>
                </svg>
                """;

        AiGeneratedData data = AiGeneratedData.builder()
                .icon(Map.of(
                        "name", "clean-icon",
                        "category", "GENERAL",
                        "svgContent", cleanSvg
                ))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertTrue(result.isValid(), "Clean SVG should pass validation");
    }

    // ==================== Email slices (AI Studio EMAIL_TEMPLATES / CONNECTIONS / EMAIL_MONITORS) ====================

    @Test
    void validate_emailSlices_wellFormed_shouldPass() {
        AiGeneratedData data = AiGeneratedData.builder()
                .emailTemplates(List.of(Map.of("name", "Approved", "subject", "Order ${order_no}",
                        "bodyHtml", "<p>Your order <b>${order_no}</b> is approved.</p>", "enabled", true)))
                .emailConnections(List.of(Map.of("name", "notify@example.com", "connectionType", "GMAIL",
                        "direction", "OUTBOUND", "fromName", "Workflow", "enabled", true)))
                .emailMonitorRules(List.of(Map.of("name", "Invoice inbox", "connectionName", "inbox@example.com",
                        "actionType", "START_PROCESS", "pollIntervalSeconds", 60,
                        "extractionRules", Map.of("fields", List.of(
                                Map.of("target", "order_no", "source", "SUBJECT", "type", "REGEX", "pattern", "#(\\d+)"))))))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertTrue(result.isValid(), () -> result.getErrors().toString());
    }

    @Test
    void validate_emailTemplateWithScript_shouldFail() {
        AiGeneratedData data = AiGeneratedData.builder()
                .emailTemplates(List.of(Map.of("name", "Bad", "bodyHtml", "<p>hi</p><script>alert(1)</script>")))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid());
        assertEquals("emailTemplates[0].bodyHtml", result.getErrors().get(0).getFieldPath());
    }

    @Test
    void validate_emailTemplateWithEventAttribute_shouldFail() {
        AiGeneratedData data = AiGeneratedData.builder()
                .emailTemplates(List.of(Map.of("name", "Bad", "bodyHtml", "<a href=\"#\" onclick=\"x()\">go</a>")))
                .build();

        assertFalse(validationService.validate(data).isValid());
    }

    @Test
    void validate_emailConnectionWithCredentialOrEndpointKeys_shouldFail() {
        AiGeneratedData data = AiGeneratedData.builder()
                .emailConnections(List.of(Map.of("name", "notify@example.com", "password", "x",
                        "host", "smtp.example.com")))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid());
        assertEquals(2, result.getErrors().stream().filter(e -> "FORBIDDEN_FIELD".equals(e.getErrorType())).count());
    }

    @Test
    void validate_emailConnectionNameMustBeEmailAndDirectionNotBoth_shouldFail() {
        AiGeneratedData data = AiGeneratedData.builder()
                .emailConnections(List.of(Map.of("name", "Notification Sender", "direction", "BOTH")))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getFieldPath().endsWith(".name")));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getFieldPath().endsWith(".direction")));
    }

    @Test
    void validate_viewWithOnlyBusinessUnitAccessRule_shouldFail() {
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(Map.of("mainTableName", "orders", "viewName", "Pending",
                        "accessRules", List.of(Map.of("targetType", "BUSINESS_UNIT", "targetId", "bu-1")))))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> "BIZ_VIEW_ACCESS_BU_ROLE_PAIR".equals(e.getErrorType())));
    }

    @Test
    void validate_viewWithPlatformKeysBadOperatorAndLookupColumn_shouldFail() {
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(Map.of("mainTableName", "orders", "viewName", "Pending",
                        "mainTableId", 5, "isDefault", true,
                        "fields", List.of(Map.of("fieldName", "customer", "columnType", "lookup_display")),
                        "sortConfig", List.of(Map.of("fieldName", "amount", "direction", "DOWN")),
                        "filterConfig", Map.of("logic", "xor", "conditions", List.of(
                                Map.of("fieldName", "amount", "operator", "between"))))))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid());
        List<String> paths = result.getErrors().stream().map(e -> e.getFieldPath()).toList();
        assertTrue(paths.contains("mainTableViews[0].mainTableId"));
        assertTrue(paths.contains("mainTableViews[0].isDefault"));
        assertTrue(paths.contains("mainTableViews[0].fields[0].columnType"));
        assertTrue(paths.contains("mainTableViews[0].sortConfig[0].direction"));
        assertTrue(paths.contains("mainTableViews[0].filterConfig.logic"));
        assertTrue(paths.contains("mainTableViews[0].filterConfig.conditions[0].operator"));
        assertEquals(6, result.getErrors().size());
    }

    @Test
    void validate_wellFormedViewProposal_shouldPass() {
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(Map.of("mainTableName", "orders", "viewName", "Pending",
                        "restrictToInvolvedUsers", false,
                        "fields", List.of(Map.of("fieldName", "order_no", "displayLabel", "Order", "columnWidth", 120,
                                "visible", true, "systemField", false, "columnType", "field")),
                        "sortConfig", List.of(Map.of("fieldName", "start_time", "direction", "DESC", "systemField", true)),
                        "filterConfig", Map.of("logic", "and", "conditions", List.of(
                                Map.of("fieldName", "process_status", "operator", "eq", "value", "RUNNING", "systemField", true))),
                        "accessRules", List.of(
                                Map.of("targetType", "BUSINESS_UNIT", "targetId", "bu-1"),
                                Map.of("targetType", "ROLE", "targetId", "role-1")))))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertTrue(result.isValid(), () -> String.valueOf(result.getErrors()));
    }

    @Test
    void validate_scopedFormProposal_resolvesBindingsAgainstExistingTables() {
        AiGeneratedData data = AiGeneratedData.builder()
                .formDefinitions(List.of(Map.of("formName", "order_form", "formType", "PROCESS",
                        "tableBindings", List.of(Map.of("tableName", "biz_order", "bindingType", "PRIMARY")))))
                .build();

        // 没有已有表目录：scoped 提案的绑定无处解析 → 失败（历史行为）
        AiValidationResult without = validationService.validate(data);
        assertFalse(without.isValid());
        assertTrue(without.getErrors().stream().anyMatch(e -> e.getFieldPath().endsWith("tableBindings[0].tableName")));

        // 交给它功能单元里已有的表 → 通过
        AiValidationResult with = validationService.validate(data,
                Map.of("biz_order", java.util.Set.of("id", "order_no"), "biz_order_item", java.util.Set.of("id")));
        assertTrue(with.isValid(), () -> String.valueOf(with.getErrors()));

        // 引用不在目录里的表仍然失败
        AiGeneratedData ghost = AiGeneratedData.builder()
                .formDefinitions(List.of(Map.of("formName", "f", "formType", "PROCESS",
                        "tableBindings", List.of(Map.of("tableName", "ghost", "bindingType", "PRIMARY")))))
                .build();
        assertFalse(validationService.validate(ghost, Map.of("biz_order", java.util.Set.of("id"))).isValid());
    }

    @Test
    void validate_serviceTaskBindingWithLegacyKeysOrDuplicates_shouldFail() {
        AiGeneratedData data = AiGeneratedData.builder()
                .serviceTaskBindings(List.of(
                        Map.of("serviceTaskId", "svc_1", "flowKey", "k1", "ap:inputMapping", "{}", "serviceType", "ap"),
                        Map.of("serviceTaskId", "svc_1", "flowKey", "k2"),
                        Map.of("serviceTaskId", "", "flowKey", "")))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid());
        List<String> paths = result.getErrors().stream().map(e -> e.getFieldPath()).toList();
        assertTrue(paths.contains("serviceTaskBindings[0].ap:inputMapping"));
        assertTrue(paths.contains("serviceTaskBindings[0].serviceType"));
        assertTrue(paths.contains("serviceTaskBindings[1].serviceTaskId"));
        assertTrue(paths.contains("serviceTaskBindings[2].serviceTaskId"));
        assertTrue(paths.contains("serviceTaskBindings[2].flowKey"));
        assertEquals(5, result.getErrors().size());
    }

    @Test
    void validate_wellFormedServiceTaskBinding_shouldPass() {
        AiGeneratedData data = AiGeneratedData.builder()
                .serviceTaskBindings(List.of(Map.of("serviceTaskId", "svc_1", "flowKey", "invoice-sync")))
                .build();

        assertTrue(validationService.validate(data).isValid());
    }

    @Test
    void validate_emailMonitorWithBindingFields_shouldFail() {
        AiGeneratedData data = AiGeneratedData.builder()
                .emailMonitorRules(List.of(Map.of("name", "Inbox", "connectionName", "inbox@example.com",
                        "startEventId", "start_1", "filterSubject", "invoice")))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid());
        assertEquals(2, result.getErrors().stream().filter(e -> "FORBIDDEN_FIELD".equals(e.getErrorType())).count());
    }

    @Test
    void validate_emailMonitorExtractionEnums_shouldFail() {
        AiGeneratedData data = AiGeneratedData.builder()
                .emailMonitorRules(List.of(Map.of("name", "Inbox", "connectionName", "inbox@example.com",
                        "actionType", "SEND_MAIL",
                        "extractionRules", Map.of("fields", List.of(Map.of("target", "", "source", "BODY", "type", "GUESS"))))))
                .build();

        AiValidationResult result = validationService.validate(data);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getFieldPath().endsWith(".actionType")));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getFieldPath().endsWith(".target")));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getFieldPath().endsWith(".source")));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getFieldPath().endsWith(".type")));
    }
}
