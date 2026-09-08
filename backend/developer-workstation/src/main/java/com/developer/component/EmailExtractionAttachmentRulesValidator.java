package com.developer.component;

import com.developer.entity.FieldDefinition;
import com.developer.entity.TableDefinition;
import com.developer.enums.DataType;
import com.developer.enums.TableType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.TableDefinitionRepository;
import com.platform.common.i18n.I18nService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rejects Email Monitor Field Mapping rows that store attachments on a non-FILE column.
 */
@Component
@RequiredArgsConstructor
public class EmailExtractionAttachmentRulesValidator {

    private final TableDefinitionRepository tableDefinitionRepository;
    private final I18nService i18nService;

    public void validate(Long functionUnitId, Map<String, Object> extractionRules) {
        if (extractionRules == null || extractionRules.isEmpty()) {
            return;
        }
        Object fieldsObj = extractionRules.get("fields");
        if (!(fieldsObj instanceof List<?> fields) || fields.isEmpty()) {
            return;
        }
        Set<String> fileFields = mainFileFieldNames(functionUnitId);
        for (Object item : fields) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            if (!"ATTACHMENTS".equals(String.valueOf(row.get("source")))) {
                continue;
            }
            Object targetObj = row.get("target");
            String target = targetObj == null ? "" : String.valueOf(targetObj).trim();
            if (!StringUtils.hasText(target) || fileFields.contains(target)) {
                continue;
            }
            throw new DeveloperBusinessException(
                    "VALIDATION_ATTACHMENTS_TARGET_NOT_FILE",
                    i18nService.getMessage("email.monitor.attachments_target_must_be_file", target));
        }
    }

    private Set<String> mainFileFieldNames(Long functionUnitId) {
        Set<String> names = new HashSet<>();
        for (TableDefinition table : tableDefinitionRepository.findByFunctionUnitIdWithFields(functionUnitId)) {
            if (table.getTableType() != TableType.MAIN || table.getFieldDefinitions() == null) {
                continue;
            }
            for (FieldDefinition field : table.getFieldDefinitions()) {
                if (field.getDataType() == DataType.FILE && StringUtils.hasText(field.getFieldName())) {
                    names.add(field.getFieldName());
                }
            }
        }
        return names;
    }
}
