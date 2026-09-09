package com.developer.component;

import com.developer.entity.FieldDefinition;
import com.developer.entity.TableDefinition;
import com.developer.enums.DataType;
import com.developer.enums.TableType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.TableDefinitionRepository;
import com.platform.common.i18n.I18nService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailExtractionAttachmentRulesValidatorTest {

    private TableDefinitionRepository tableDefinitionRepository;
    private I18nService i18nService;
    private EmailExtractionAttachmentRulesValidator validator;

    @BeforeEach
    void setUp() {
        tableDefinitionRepository = mock(TableDefinitionRepository.class);
        i18nService = mock(I18nService.class);
        validator = new EmailExtractionAttachmentRulesValidator(tableDefinitionRepository, i18nService);
        FieldDefinition file = FieldDefinition.builder().fieldName("quote_files").dataType(DataType.FILE).build();
        FieldDefinition title = FieldDefinition.builder().fieldName("title").dataType(DataType.VARCHAR).build();
        TableDefinition main = TableDefinition.builder()
                .tableType(TableType.MAIN)
                .fieldDefinitions(List.of(file, title))
                .build();
        when(tableDefinitionRepository.findByFunctionUnitIdWithFields(8L)).thenReturn(List.of(main));
    }

    @Test
    void acceptsAttachmentsMappedToFileColumn() {
        Map<String, Object> rules = Map.of("fields", List.of(Map.of(
                "target", "quote_files", "source", "ATTACHMENTS", "type", "DIRECT")));
        assertDoesNotThrow(() -> validator.validate(8L, rules));
    }

    @Test
    void rejectsAttachmentsMappedToNonFileColumn() {
        when(i18nService.getMessage("email.monitor.attachments_target_must_be_file", "title"))
                .thenReturn("must be FILE");
        Map<String, Object> rules = Map.of("fields", List.of(Map.of(
                "target", "title", "source", "ATTACHMENTS", "type", "DIRECT")));

        DeveloperBusinessException ex = assertThrows(
                DeveloperBusinessException.class, () -> validator.validate(8L, rules));
        assertEquals("VALIDATION_ATTACHMENTS_TARGET_NOT_FILE", ex.getErrorCode());
    }
}
