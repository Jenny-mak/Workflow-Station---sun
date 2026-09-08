package com.admin.component;

import com.admin.dto.request.FunctionUnitImportRequest;
import com.admin.dto.response.FunctionUnitInfo;
import com.admin.dto.response.ImportResult;
import com.admin.dto.response.ValidationResult;
import com.admin.entity.ActionDefinition;
import com.admin.entity.FunctionUnit;
import com.admin.entity.FunctionUnitContent;
import com.admin.entity.FunctionUnitDependency;
import com.admin.enums.ContentType;
import com.admin.enums.DependencyType;
import com.admin.enums.FunctionUnitStatus;
import com.admin.exception.AdminBusinessException;
import com.admin.repository.ActionDefinitionRepository;
import com.admin.repository.FunctionUnitAccessRepository;
import com.admin.repository.FunctionUnitContentRepository;
import com.admin.repository.FunctionUnitDependencyRepository;
import com.admin.repository.FunctionUnitRepository;
import com.admin.service.FunctionUnitAccessService;
import com.admin.service.FunctionUnitAuditAccessService;
import com.admin.util.ChecksumUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.i18n.I18nService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Function package import: validation, ZIP parse, catalog persist.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FunctionUnitImportComponent {

    private final FunctionUnitRepository functionUnitRepository;
    private final FunctionUnitDependencyRepository dependencyRepository;
    private final FunctionUnitContentRepository contentRepository;
    private final FunctionUnitAccessRepository accessRepository;
    private final FunctionUnitAccessService functionUnitAccessService;
    private final FunctionUnitAuditAccessService functionUnitAuditAccessService;
    private final FunctionUnitPackageParser packageParser;
    private final ActionDefinitionRepository actionDefinitionRepository;
    private final FunctionUnitVersionComponent versionComponent;
    private final RelationTableStructureImporter relationTableStructureImporter;
    private final ObjectMapper objectMapper;
    private final I18nService i18nService;
    private final EmailConnectionSyncComponent emailConnectionSyncComponent;
    private final EmailMonitorSyncComponent emailMonitorSyncComponent;
    private final ImportBpmnStructureValidator importBpmnStructureValidator;
    private final ImportViewAccessValidator importViewAccessValidator;

    @Transactional
    public ImportResult importFunctionPackage(FunctionUnitImportRequest request, String importerId) {
        log.info("Importing function package: {}", request.getFileName());
        ValidationResult validationResult = validatePackage(request);
        if (!validationResult.isValid()) {
            return ImportResult.validationFailed(validationResult.getErrors());
        }
        try {
            return persistImportedPackage(request, importerId);
        } catch (DataIntegrityViolationException e) {
            throw new AdminBusinessException("FU_IMPORT_CONSTRAINT", constraintMessage(e), e);
        } catch (IOException e) {
            throw new AdminBusinessException("FU_IMPORT_INVALID_PACKAGE",
                    i18nService.getMessage("admin.fu.import_failed", e.getMessage()), e);
        }
    }

    private ImportResult persistImportedPackage(FunctionUnitImportRequest request, String importerId)
            throws IOException {
        FunctionUnitPackageParser.ParsedImportPackage parsed = parseImportRequest(request);
        FunctionUnitManagerComponent.FunctionPackageContent packageContent = parsed.getPackageContent();
        validateImportedBpmn(packageContent);
        remapViewAccess(packageContent);

        FunctionUnit existingByCode = resolveExistingByCode(packageContent);
        rejectNameCollision(packageContent, existingByCode);

        FunctionUnit overwriteTarget = null;
        boolean versioned = false;
        if (existingByCode != null) {
            packageContent.setCode(existingByCode.getCode());
            versioned = true;
            Optional<FunctionUnit> existingSameVersion = functionUnitRepository.findByCodeAndVersion(
                    existingByCode.getCode(), packageContent.getVersion());
            if (existingSameVersion.isPresent()) {
                if (Boolean.FALSE.equals(request.getOverwrite())) {
                    throw new AdminBusinessException("FU_IMPORT_VERSION_EXISTS",
                            i18nService.getMessage("admin.fu.import_version_exists",
                                    existingByCode.getCode(), packageContent.getVersion()));
                }
                overwriteTarget = existingSameVersion.get();
            }
        }

        if (parsed.getIconSvg() != null && request.getIconSvg() == null) {
            request.setIconSvg(parsed.getIconSvg());
        }
        request.setIconSvg(FunctionUnitIconSvgSanitizer.sanitize(request.getIconSvg()));

        List<ImportResult.DependencyConflict> conflicts = detectConflicts(packageContent);

        FunctionUnit functionUnit = overwriteTarget != null
                ? overwriteFunctionUnit(overwriteTarget, packageContent, request, importerId)
                : createFunctionUnit(packageContent, request, importerId);

        if (overwriteTarget == null && existingByCode != null && packageContent.getCode() != null) {
            functionUnitAccessService.copyAccessFromSiblingVersions(
                    packageContent.getCode(), functionUnit.getId());
            functionUnitAuditAccessService.copyAuditAccessFromSiblingVersions(
                    packageContent.getCode(), functionUnit.getId());
        }

        saveDependencies(functionUnit, packageContent.getDependencies());
        saveContents(functionUnit, packageContent.getContents());
        if (parsed.getForms() != null) {
            saveContents(functionUnit, parsed.getForms());
        }
        saveImportedActions(functionUnit.getId(), parsed.getActions());
        relationTableStructureImporter.importRelationTables(
                parsed.getRelationTables(), importerId, functionUnit.getId());
        emailConnectionSyncComponent.syncConnections(functionUnit.getId(), parsed.getConnections());
        emailMonitorSyncComponent.syncMonitorRules(functionUnit.getId(), parsed.getEmailMonitors());

        log.info("Function package imported successfully: {}", functionUnit.getId());
        FunctionUnitInfo info = FunctionUnitInfo.fromEntity(functionUnit);
        if (!conflicts.isEmpty()) {
            ImportResult conflictResult = ImportResult.conflictDetected(info, conflicts);
            conflictResult.setVersioned(versioned);
            return conflictResult;
        }
        return ImportResult.success(info, versioned);
    }

    public ValidationResult validatePackage(FunctionUnitImportRequest request) {
        log.info("Validating function package: {}", request.getFileName());
        ValidationResult result = ValidationResult.builder()
                .valid(true)
                .fileFormatValid(true)
                .integrityValid(true)
                .signatureValid(true)
                .bpmnSyntaxValid(true)
                .dataTableValid(true)
                .formConfigValid(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .build();
        if (!validateFileFormat(request, result)) {
            result.setFileFormatValid(false);
        }
        if (!validateIntegrity(request, result)) {
            result.setIntegrityValid(false);
        }
        return result;
    }

    private boolean validateFileFormat(FunctionUnitImportRequest request, ValidationResult result) {
        if (request.getFileName() == null || request.getFileName().isEmpty()) {
            result.addError("FILE_FORMAT", "fileName", i18nService.getMessage("admin.fu.file_name_required"));
            return false;
        }
        String fileName = request.getFileName().toLowerCase();
        if (!fileName.endsWith(".zip") && !fileName.endsWith(".fpkg")) {
            result.addError("FILE_FORMAT", "fileName", i18nService.getMessage("admin.fu.file_format_unsupported"));
            return false;
        }
        if (request.getFileContent() == null && request.getFilePath() == null) {
            result.addError("FILE_FORMAT", "fileContent", i18nService.getMessage("admin.fu.file_content_required"));
            return false;
        }
        return true;
    }

    private boolean validateIntegrity(FunctionUnitImportRequest request, ValidationResult result) {
        if (request.getFileContent() != null && request.getFileContent().isEmpty()) {
            result.addError("INTEGRITY", "fileContent", i18nService.getMessage("admin.fu.file_content_empty"));
            return false;
        }
        return true;
    }

    public boolean validateBpmnSyntax(String bpmnContent, ValidationResult result) {
        if (bpmnContent == null || bpmnContent.isEmpty()) {
            result.addError("BPMN_SYNTAX", "content", i18nService.getMessage("admin.fu.bpmn_empty"));
            return false;
        }
        if (!bpmnContent.contains("definitions") || !bpmnContent.contains("process")) {
            result.addError("BPMN_SYNTAX", "content", i18nService.getMessage("admin.fu.bpmn_invalid"));
            return false;
        }
        return true;
    }

    public boolean validateDataTableStructure(String tableDefinition, ValidationResult result) {
        if (tableDefinition == null || tableDefinition.isEmpty()) {
            return true;
        }
        String upperDef = tableDefinition.toUpperCase();
        if (!upperDef.contains("CREATE TABLE") && !upperDef.contains("ALTER TABLE")) {
            result.addError("DATA_TABLE", "definition", i18nService.getMessage("admin.fu.data_table_invalid"));
            return false;
        }
        return true;
    }

    public boolean validateFormConfig(String formConfig, ValidationResult result) {
        if (formConfig == null || formConfig.isEmpty()) {
            return true;
        }
        if (!formConfig.trim().startsWith("{") && !formConfig.trim().startsWith("[")) {
            result.addError("FORM_CONFIG", "config", i18nService.getMessage("admin.fu.form_config_invalid"));
            return false;
        }
        return true;
    }

    public List<ImportResult.DependencyConflict> detectConflicts(
            FunctionUnitManagerComponent.FunctionPackageContent packageContent) {
        List<ImportResult.DependencyConflict> conflicts = new ArrayList<>();
        for (FunctionUnitManagerComponent.DependencyInfo dep : packageContent.getDependencies()) {
            Optional<FunctionUnit> existing = functionUnitRepository.findLatestByCode(dep.getCode());
            if (existing.isPresent()) {
                String existingVersion = existing.get().getVersion();
                if (!versionComponent.isVersionCompatible(dep.getVersion(), existingVersion)) {
                    conflicts.add(ImportResult.DependencyConflict.builder()
                            .dependencyCode(dep.getCode())
                            .requiredVersion(dep.getVersion())
                            .existingVersion(existingVersion)
                            .conflictType("VERSION_MISMATCH")
                            .build());
                }
            } else if (dep.isRequired()) {
                conflicts.add(ImportResult.DependencyConflict.builder()
                        .dependencyCode(dep.getCode())
                        .requiredVersion(dep.getVersion())
                        .existingVersion(null)
                        .conflictType("MISSING_DEPENDENCY")
                        .build());
            }
        }
        return conflicts;
    }

    private FunctionUnitPackageParser.ParsedImportPackage parseImportRequest(FunctionUnitImportRequest request)
            throws IOException {
        boolean zipName = request.getFileName() != null
                && request.getFileName().toLowerCase().endsWith(".zip");
        if (request.getFileContent() != null && !request.getFileContent().isBlank() && zipName) {
            FunctionUnitPackageParser.ParsedImportPackage parsed =
                    packageParser.parseBase64Zip(request.getFileContent());
            applyRequestOverrides(parsed.getPackageContent(), request);
            return parsed;
        }
        return FunctionUnitPackageParser.ParsedImportPackage.builder()
                .packageContent(parsePackageContentLegacy(request))
                .forms(List.of())
                .actions(List.of())
                .relationTables(List.of())
                .connections(List.of())
                .emailMonitors(List.of())
                .iconSvg(FunctionUnitIconSvgSanitizer.sanitize(request.getIconSvg()))
                .build();
    }

    private void applyRequestOverrides(FunctionUnitManagerComponent.FunctionPackageContent content,
                                       FunctionUnitImportRequest request) {
        if (content.getCode() == null || content.getCode().isBlank()) {
            content.setCode(extractCodeFromFileName(request.getFileName()));
        }
        if (content.getName() == null || content.getName().isBlank()) {
            content.setName(request.getName() != null ? request.getName() : content.getCode());
        }
        if (request.getCode() != null && !request.getCode().isBlank()) {
            content.setCode(request.getCode());
        }
        if (request.getVersion() != null && !request.getVersion().isBlank()) {
            content.setVersion(request.getVersion());
        }
        if (request.getDescription() != null) {
            content.setDescription(request.getDescription());
        }
    }

    private FunctionUnitManagerComponent.FunctionPackageContent parsePackageContentLegacy(
            FunctionUnitImportRequest request) {
        String code = request.getCode() != null && !request.getCode().isEmpty()
                ? request.getCode() : extractCodeFromFileName(request.getFileName());
        String version = request.getVersion() != null ? request.getVersion() : "1.0.0";
        String name = request.getName() != null ? request.getName() : code;
        List<FunctionUnitManagerComponent.ContentInfo> contents = new ArrayList<>();
        if (request.getFileContent() != null && !request.getFileContent().isEmpty()) {
            contents.add(FunctionUnitManagerComponent.ContentInfo.builder()
                    .contentType(ContentType.PROCESS)
                    .contentName("main-process.bpmn")
                    .contentPath("/processes/main-process.bpmn")
                    .contentData(request.getFileContent())
                    .build());
        }
        return FunctionUnitManagerComponent.FunctionPackageContent.builder()
                .code(code)
                .version(version)
                .name(name)
                .description(request.getDescription())
                .dependencies(new ArrayList<>())
                .contents(contents)
                .build();
    }

    private String extractCodeFromFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unknown";
        }
        String name = fileName;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            name = fileName.substring(0, dotIndex);
        }
        int dashIndex = name.lastIndexOf('-');
        if (dashIndex > 0 && name.substring(dashIndex + 1).matches("\\d+\\.\\d+\\.\\d+.*")) {
            name = name.substring(0, dashIndex);
        }
        return name;
    }

    private FunctionUnit existingByCodeOrNull(FunctionUnitManagerComponent.FunctionPackageContent packageContent) {
        if (packageContent.getCode() == null || packageContent.getCode().isBlank()) {
            return null;
        }
        return functionUnitRepository.findLatestByCode(packageContent.getCode()).orElse(null);
    }

    private FunctionUnit resolveExistingByCode(FunctionUnitManagerComponent.FunctionPackageContent packageContent) {
        return existingByCodeOrNull(packageContent);
    }

    private void rejectNameCollision(FunctionUnitManagerComponent.FunctionPackageContent packageContent,
                                     FunctionUnit existingByCode) {
        if (packageContent.getName() == null || packageContent.getName().isBlank()) {
            return;
        }
        FunctionUnit byName = functionUnitRepository.findLatestByName(packageContent.getName()).orElse(null);
        if (byName == null) {
            return;
        }
        if (existingByCode != null && byName.getCode().equals(existingByCode.getCode())) {
            return;
        }
        if (existingByCode == null && packageContent.getCode() != null
                && packageContent.getCode().equals(byName.getCode())) {
            return;
        }
        throw new AdminBusinessException("FU_IMPORT_NAME_COLLISION",
                i18nService.getMessage("admin.fu.import_name_collision",
                        packageContent.getName(), byName.getCode()));
    }

    private void validateImportedBpmn(FunctionUnitManagerComponent.FunctionPackageContent packageContent) {
        if (packageContent.getContents() == null) {
            return;
        }
        for (FunctionUnitManagerComponent.ContentInfo content : packageContent.getContents()) {
            if (content.getContentType() == ContentType.PROCESS) {
                importBpmnStructureValidator.validate(content.getContentData(), content.getContentName());
            }
        }
    }

    private void remapViewAccess(FunctionUnitManagerComponent.FunctionPackageContent packageContent) {
        if (packageContent.getContents() == null) {
            return;
        }
        for (FunctionUnitManagerComponent.ContentInfo content : packageContent.getContents()) {
            if (content.getContentType() == ContentType.MAIN_TABLE_VIEW) {
                content.setContentData(importViewAccessValidator.remapAndValidate(content.getContentData()));
            }
        }
    }

    private FunctionUnit overwriteFunctionUnit(FunctionUnit existing,
                                               FunctionUnitManagerComponent.FunctionPackageContent packageContent,
                                               FunctionUnitImportRequest request,
                                               String importerId) {
        contentRepository.deleteByFunctionUnitId(existing.getId());
        dependencyRepository.deleteByFunctionUnitId(existing.getId());
        functionUnitRepository.flush();

        existing.setDescription(packageContent.getDescription());
        if (packageContent.getVersion() != null && !packageContent.getVersion().isBlank()) {
            existing.setVersion(packageContent.getVersion());
        }
        existing.setPackagePath(request.getFilePath());
        existing.setPackageSize(request.getFileContent() != null ? (long) request.getFileContent().length() : 0L);
        existing.setChecksum(ChecksumUtils.sha256Hex(request.getFileContent()));
        existing.setImportedAt(Instant.now());
        existing.setImportedBy(importerId);
        if (request.getIconSvg() != null) {
            existing.setIconSvg(request.getIconSvg());
        }
        return functionUnitRepository.save(existing);
    }

    private FunctionUnit createFunctionUnit(FunctionUnitManagerComponent.FunctionPackageContent packageContent,
                                            FunctionUnitImportRequest request,
                                            String importerId) {
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(UUID.randomUUID().toString())
                .code(packageContent.getCode())
                .name(packageContent.getName())
                .version(packageContent.getVersion())
                .description(packageContent.getDescription())
                .packagePath(request.getFilePath())
                .packageSize(request.getFileContent() != null ? (long) request.getFileContent().length() : 0L)
                .checksum(ChecksumUtils.sha256Hex(request.getFileContent()))
                .status(FunctionUnitStatus.DRAFT)
                .enabled(false)
                .importedAt(Instant.now())
                .importedBy(importerId)
                .deployedAt(Instant.now())
                .iconSvg(request.getIconSvg())
                .build();
        return functionUnitRepository.save(functionUnit);
    }

    private void saveDependencies(FunctionUnit functionUnit,
                                  List<FunctionUnitManagerComponent.DependencyInfo> dependencies) {
        if (dependencies == null) {
            return;
        }
        for (FunctionUnitManagerComponent.DependencyInfo dep : dependencies) {
            dependencyRepository.save(FunctionUnitDependency.builder()
                    .id(UUID.randomUUID().toString())
                    .functionUnit(functionUnit)
                    .dependencyCode(dep.getCode())
                    .dependencyVersion(dep.getVersion())
                    .dependencyType(dep.isRequired() ? DependencyType.REQUIRED : DependencyType.OPTIONAL)
                    .build());
        }
    }

    private void saveContents(FunctionUnit functionUnit,
                              List<FunctionUnitManagerComponent.ContentInfo> contents) {
        if (contents == null) {
            return;
        }
        for (FunctionUnitManagerComponent.ContentInfo content : contents) {
            contentRepository.save(FunctionUnitContent.builder()
                    .id(UUID.randomUUID().toString())
                    .functionUnit(functionUnit)
                    .contentType(content.getContentType())
                    .contentName(content.getContentName())
                    .contentPath(content.getContentPath())
                    .contentData(content.getContentData())
                    .checksum(ChecksumUtils.sha256Hex(content.getContentData()))
                    .sourceId(content.getSourceId())
                    .build());
        }
    }

    private void saveImportedActions(String functionUnitId, List<Map<String, Object>> actions) {
        actionDefinitionRepository.deleteByFunctionUnitId(functionUnitId);
        // Hibernate otherwise flushes INSERTs before this DELETE, colliding with
        // uk_sys_action_name_fu when DW delete → re-import → Deploy overwrites the
        // same Admin catalog (code + version) that already has Mark Complete.
        actionDefinitionRepository.flush();
        if (actions == null || actions.isEmpty()) {
            return;
        }
        for (Map<String, Object> actionData : actions) {
            String actionName = actionData.get("actionName") != null
                    ? String.valueOf(actionData.get("actionName")) : null;
            String actionType = actionData.get("actionType") != null
                    ? String.valueOf(actionData.get("actionType")) : null;
            if (actionName == null || actionType == null) {
                throw new AdminBusinessException("FU_IMPORT_ACTION_INVALID",
                        i18nService.getMessage("admin.fu.import_action_invalid"));
            }
            ActionDefinition actionDef = ActionDefinition.builder()
                    .functionUnitId(functionUnitId)
                    .actionName(actionName)
                    .actionType(actionType)
                    .description(actionData.get("description") != null
                            ? String.valueOf(actionData.get("description")) : null)
                    .configJson(resolveActionConfigJson(actionData.get("configJson")))
                    .icon(actionData.get("icon") != null ? String.valueOf(actionData.get("icon")) : null)
                    .buttonColor(actionData.get("buttonColor") != null
                            ? String.valueOf(actionData.get("buttonColor")) : null)
                    .isDefault(Boolean.TRUE.equals(actionData.get("isDefault")))
                    .build();
            actionDefinitionRepository.save(actionDef);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveActionConfigJson(Object configJsonObj) {
        if (configJsonObj instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (configJsonObj instanceof String s && !s.isBlank()) {
            try {
                return objectMapper.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new AdminBusinessException("FU_IMPORT_ACTION_INVALID",
                        i18nService.getMessage("admin.fu.import_action_invalid"), e);
            }
        }
        return Map.of();
    }

    private String constraintMessage(DataIntegrityViolationException e) {
        String raw = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        if (raw != null && raw.contains("chk_content_type")) {
            return i18nService.getMessage("admin.fu.import_content_type_constraint");
        }
        return i18nService.getMessage("admin.fu.import_failed", raw);
    }

    @Transactional
    public void deleteExistingVersion(String code, String version) {
        Optional<FunctionUnit> existing = functionUnitRepository.findByCodeAndVersion(code, version);
        if (existing.isEmpty()) {
            return;
        }
        FunctionUnit unit = existing.get();
        accessRepository.deleteByFunctionUnitId(unit.getId());
        contentRepository.deleteByFunctionUnitId(unit.getId());
        dependencyRepository.deleteByFunctionUnitId(unit.getId());
        actionDefinitionRepository.deleteByFunctionUnitId(unit.getId());
        functionUnitRepository.delete(unit);
        functionUnitRepository.flush();
        log.info("Deleted existing function unit version: {}:{}", code, version);
    }
}
