package com.developer.util;

import com.developer.dto.FieldDefinitionRequest;
import com.developer.enums.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormCreateRuleToFieldMapperTest {

    @Test
    @DisplayName("maps input/switch/upload and skips subTable layout")
    void mapsCommonTypes() {
        List<?> rule = List.of(
                Map.of(
                        "type", "elCard",
                        "children", List.of(
                                Map.of("type", "input", "field", "case_number", "title", "Case Number",
                                        "validate", List.of(Map.of("required", true))),
                                Map.of("type", "switch", "field", "legal_hold", "title", "Legal Hold"),
                                Map.of("type", "upload", "field", "file", "title", "File"),
                                Map.of("type", "advancedUpload", "field", "meeting_doc", "title", "Meeting Doc"),
                                Map.of("type", "subTable", "_bindingId", 273)
                        )
                )
        );
        List<FieldDefinitionRequest> fields = FormCreateRuleToFieldMapper.fromRules(rule);
        assertEquals(4, fields.size());
        assertEquals("case_number", fields.get(0).getFieldName());
        assertEquals(DataType.VARCHAR, fields.get(0).getDataType());
        assertEquals(Boolean.FALSE, fields.get(0).getNullable());
        assertEquals(DataType.BOOLEAN, fields.get(1).getDataType());
        assertEquals(DataType.FILE, fields.get(2).getDataType());
        assertEquals("meeting_doc", fields.get(3).getFieldName());
        assertEquals(DataType.FILE, fields.get(3).getDataType());
    }

    @Test
    @DisplayName("maps owner to VARCHAR (docs/design/owner-field-component.md §5.2)")
    void mapsOwnerToVarchar() {
        List<?> rule = List.of(
                Map.of("type", "owner", "field", "owner", "title", "Owner",
                        "props", Map.of("ownerConfig", "{\"source\":\"CREATOR\"}"))
        );
        List<FieldDefinitionRequest> fields = FormCreateRuleToFieldMapper.fromRules(rule);
        assertEquals(1, fields.size());
        assertEquals("owner", fields.get(0).getFieldName());
        assertEquals(DataType.VARCHAR, fields.get(0).getDataType());
    }

    @Test
    void sanitizeTableNamePart() {
        assertEquals("mcy_debit", FormCreateRuleToFieldMapper.sanitizeTableNamePart("MCY Debit"));
        assertTrue(FormCreateRuleToFieldMapper.sanitizeTableNamePart("123").startsWith("t_"));
    }
}
