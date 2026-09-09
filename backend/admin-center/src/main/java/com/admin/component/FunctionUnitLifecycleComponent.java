package com.admin.component;

import com.admin.dto.response.DeletePreviewResponse;
import com.admin.dto.response.ValidationResult;
import com.admin.entity.FunctionUnit;
import com.admin.entity.FunctionUnitContent;
import com.admin.enums.FunctionUnitStatus;
import com.admin.exception.AdminBusinessException;
import com.admin.exception.FunctionUnitNotFoundException;
import com.admin.repository.FunctionUnitAccessRepository;
import com.admin.repository.FunctionUnitAuditAccessRepository;
import com.admin.repository.FunctionUnitContentRepository;
import com.admin.repository.FunctionUnitDependencyRepository;
import com.admin.repository.FunctionUnitRepository;
import com.platform.common.i18n.I18nService;
import com.platform.common.version.SemanticVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Function unit lifecycle: validation/deprecation state transitions, archive/restore,
 * cascade delete, enable/disable and version activation rules for portal initiation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FunctionUnitLifecycleComponent {

    private final FunctionUnitRepository functionUnitRepository;
    private final FunctionUnitDependencyRepository dependencyRepository;
    private final FunctionUnitContentRepository contentRepository;
    private final FunctionUnitAccessRepository accessRepository;
    private final FunctionUnitAuditAccessRepository auditAccessRepository;
    private final FunctionUnitValidationComponent validationComponent;
    private final FunctionUnitVersionComponent versionComponent;
    private final FunctionUnitLookup functionUnitLookup;
    private final I18nService i18nService;

    /**
     * Validate function unit: structure/dependency/trial deploy.
     * DRAFT transitions to VALIDATED on success; VALIDATED/DEPLOYED keep status
     * so DW overwrite-import of an already deployed catalog can still pass /validate.
     */
    @Transactional
    public ValidationResult validateFunctionUnit(String id, String validatorId) {
        FunctionUnit functionUnit = functionUnitLookup.getById(id);
        assertContentValidationAllowed(functionUnit);

        ValidationResult result = validationComponent.validate(id);
        result.setFunctionUnitId(id);
        result.setStatus(functionUnit.getStatus().name());
        if (!result.isValid()) {
            return result;
        }
        return persistDraftTransitionIfNeeded(functionUnit, validatorId, result);
    }

    private void assertContentValidationAllowed(FunctionUnit functionUnit) {
        FunctionUnitStatus status = functionUnit.getStatus();
        if (status == FunctionUnitStatus.DRAFT
                || status == FunctionUnitStatus.VALIDATED
                || status == FunctionUnitStatus.DEPLOYED) {
            return;
        }
        throw new AdminBusinessException("INVALID_STATUS",
                i18nService.getMessage("admin.fu.validate_draft_only", status));
    }

    private ValidationResult persistDraftTransitionIfNeeded(
            FunctionUnit functionUnit, String validatorId, ValidationResult result) {
        if (!functionUnit.isValidatable()) {
            return result;
        }
        functionUnit.markAsValidated(validatorId);
        functionUnitRepository.save(functionUnit);
        result.setStatus(FunctionUnitStatus.VALIDATED.name());
        return result;
    }

    /**
     * Deprecate function unit
     */
    @Transactional
    public FunctionUnit deprecateFunctionUnit(String id) {
        FunctionUnit functionUnit = functionUnitLookup.getById(id);
        functionUnit.markAsDeprecated();
        return functionUnitRepository.save(functionUnit);
    }

    /**
     * Delete preview
     * Count associated data that would be removed
     */
    @Transactional(readOnly = true)
    public DeletePreviewResponse getDeletePreview(String functionUnitId) {
        FunctionUnit unit = functionUnitLookup.getById(functionUnitId);

        // Count associated entities by type
        List<FunctionUnitContent> contents = contentRepository.findByFunctionUnitId(functionUnitId);

        int formCount = 0;
        int processCount = 0;
        int dataTableCount = 0;

        for (FunctionUnitContent content : contents) {
            switch (content.getContentType()) {
                case FORM:
                    formCount++;
                    break;
                case PROCESS:
                    processCount++;
                    break;
                case DATA_TABLE:
                    dataTableCount++;
                    break;
                default:
                    break;
            }
        }

        int dependencyCount = dependencyRepository.findByFunctionUnitId(functionUnitId).size();
        int deploymentCount = unit.getDeployments() != null ? unit.getDeployments().size() : 0;

        // Check running instances (simplified; production should call engine)
        boolean hasRunningInstances = false;
        int runningInstanceCount = 0;

        return DeletePreviewResponse.builder()
                .functionUnitId(functionUnitId)
                .functionUnitName(unit.getName())
                .functionUnitCode(unit.getCode())
                .formCount(formCount)
                .processCount(processCount)
                .dataTableCount(dataTableCount)
                .accessConfigCount(0) // filled in by a later query
                .deploymentCount(deploymentCount)
                .dependencyCount(dependencyCount)
                .hasRunningInstances(hasRunningInstances)
                .runningInstanceCount(runningInstanceCount)
                .build();
    }

    /**
     * Whether running process instances exist
     */
    public boolean hasRunningInstances(String functionUnitId) {
        // Simplified: should call process engine
        // Returns false here (no running instances)
        return false;
    }

    /**
     * Archive function unit by code (all versions; remove portal visibility)
     */
    @Transactional
    public void archiveFunctionUnitByCode(String functionUnitId) {
        FunctionUnit unit = functionUnitLookup.getById(functionUnitId);

        if (unit.getStatus() == FunctionUnitStatus.ARCHIVED) {
            log.info("Function unit already archived: {}", functionUnitId);
            return;
        }

        if (hasRunningInstances(functionUnitId)) {
            throw new AdminBusinessException("HAS_RUNNING_INSTANCES",
                    "Cannot archive: there are running process instances");
        }

        String code = unit.getCode();
        List<FunctionUnit> allVersions = functionUnitRepository.findAllByCodeOrderByVersionDesc(code);
        log.info("Archiving function unit code {} ({} version(s))", code, allVersions.size());

        for (FunctionUnit version : allVersions) {
            if (version.getStatus() == FunctionUnitStatus.ARCHIVED) {
                continue;
            }
            version.markAsArchived();
            functionUnitRepository.save(version);
        }

        log.info("Function unit archived successfully: code={}", code);
    }

    /**
     * Restore archived function unit (all ARCHIVED versions under code → DRAFT)
     */
    @Transactional
    public FunctionUnit restoreFunctionUnit(String functionUnitId) {
        FunctionUnit unit = functionUnitLookup.getById(functionUnitId);
        if (unit.getStatus() != FunctionUnitStatus.ARCHIVED) {
            throw new AdminBusinessException("INVALID_STATUS", "Only archived function units can be restored");
        }

        String code = unit.getCode();
        List<FunctionUnit> archivedVersions = functionUnitRepository.findByCodeAndStatus(code, FunctionUnitStatus.ARCHIVED);
        if (archivedVersions.isEmpty()) {
            throw new AdminBusinessException("NOT_FOUND", "No archived versions found for code: " + code);
        }

        FunctionUnit toRestore = archivedVersions.stream()
                .max(versionComponent::compareBySemver)
                .orElse(unit);
        for (FunctionUnit version : archivedVersions) {
            version.markAsDraft();
            functionUnitRepository.save(version);
        }
        log.info("Restored function unit {} ({} version(s)) to DRAFT", code, archivedVersions.size());
        return functionUnitRepository.findById(toRestore.getId()).orElse(toRestore);
    }

    /**
     * Cascade-delete function unit and related data (internal/test; public DELETE uses archive)
     */
    @Transactional
    public void deleteFunctionUnitCascade(String functionUnitId) {
        FunctionUnit unit = functionUnitLookup.getById(functionUnitId);

        // Whether running process instances exist
        if (hasRunningInstances(functionUnitId)) {
            throw new AdminBusinessException("HAS_RUNNING_INSTANCES",
                    "Cannot delete: there are running process instances");
        }

        log.info("Deleting function unit cascade: {} ({})", unit.getName(), functionUnitId);

        // Delete access permissions
        accessRepository.deleteByFunctionUnitId(functionUnitId);

        // Delete audit grants (separate table from the launch grants above)
        auditAccessRepository.deleteByFunctionUnitId(functionUnitId);

        // Delete contents
        contentRepository.deleteByFunctionUnitId(functionUnitId);

        // Delete dependencies
        dependencyRepository.deleteByFunctionUnitId(functionUnitId);

        // Delete function unit (cascades deployments)
        functionUnitRepository.delete(unit);

        log.info("Function unit deleted successfully: {}", functionUnitId);
    }

    /**
     * Set function unit enabled flag (with operator and reason).
     */
    @Transactional
    public FunctionUnit setEnabled(String functionUnitId, boolean enabled, String operatorId, String reason) {
        FunctionUnit unit = functionUnitLookup.getById(functionUnitId);
        String oldStatus = unit.getEnabled() ? "enabled" : "disabled";
        String newStatus = enabled ? "enabled" : "disabled";

        if (enabled) {
            if (unit.getStatus() != FunctionUnitStatus.DEPLOYED) {
                throw new AdminBusinessException("INVALID_STATUS",
                        "Only DEPLOYED versions can be enabled for portal initiation");
            }
            FunctionUnit maxDeployed = pickMaxSemverAmongDeployed(unit.getCode())
                    .orElseThrow(() -> new AdminBusinessException("NO_DEPLOYED", "No deployed version exists for this code"));
            if (!maxDeployed.getId().equals(unit.getId())) {
                throw new AdminBusinessException("NOT_MAX_DEPLOYED_VERSION",
                        "Only the highest semantic version among deployed versions can be enabled (current highest is " + maxDeployed.getVersion() + ")");
            }
            disableOtherVersions(unit.getCode(), unit.getVersion(), operatorId);
        }

        unit.setEnabled(enabled);
        FunctionUnit saved = functionUnitRepository.save(unit);

        log.info("Function unit {} (code: {}, version: {}) status changed from {} to {} by operator: {}, reason: {}, timestamp: {}",
                functionUnitId, unit.getCode(), unit.getVersion(), oldStatus, newStatus, operatorId, reason, Instant.now());

        return saved;
    }

    /**
     * List deployed and enabled function units
     */
    public Page<FunctionUnit> listDeployedAndEnabledFunctionUnits(Pageable pageable) {
        return functionUnitRepository.findByStatusAndEnabled(FunctionUnitStatus.DEPLOYED, true, pageable);
    }

    /**
     * Latest deployed version per function unit code
     * Group by code; keep highest SemanticVersion per group
     */
    public List<FunctionUnit> listLatestDeployedFunctionUnits() {
        List<FunctionUnit> allDeployed = functionUnitRepository.findByStatusAndEnabled(
                FunctionUnitStatus.DEPLOYED, true);

        if (allDeployed.isEmpty()) {
            return Collections.emptyList();
        }

        // Group by code; keep highest semantic version per group
        Map<String, FunctionUnit> latestByCode = new HashMap<>();
        for (FunctionUnit unit : allDeployed) {
            String code = unit.getCode();
            FunctionUnit existing = latestByCode.get(code);
            if (existing == null) {
                latestByCode.put(code, unit);
            } else {
                try {
                    SemanticVersion currentVersion = SemanticVersion.parse(unit.getVersion());
                    SemanticVersion existingVersion = SemanticVersion.parse(existing.getVersion());
                    if (currentVersion.compareTo(existingVersion) > 0) {
                        latestByCode.put(code, unit);
                    }
                } catch (IllegalArgumentException e) {
                    // Invalid semver; fall back to lexicographic compare
                    log.warn("Invalid semantic version format, falling back to lexicographic comparison: {} vs {}",
                            unit.getVersion(), existing.getVersion());
                    if (unit.getVersion().compareTo(existing.getVersion()) > 0) {
                        latestByCode.put(code, unit);
                    }
                }
            }
        }

        return new ArrayList<>(latestByCode.values());
    }

    /**
     * Portal start: highest semantic version among deployed+enabled for code (empty if none)
     */
    public Optional<FunctionUnit> getActiveCatalogForPortalStart(String code) {
        List<FunctionUnit> deployed = functionUnitRepository.findByCodeAndStatus(code, FunctionUnitStatus.DEPLOYED);
        List<FunctionUnit> enabledDeployed = deployed.stream().filter(FunctionUnit::isEnabled).toList();
        if (enabledDeployed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(enabledDeployed.stream().max(versionComponent::compareBySemver).orElseThrow());
    }

    private Optional<FunctionUnit> pickMaxSemverAmongDeployed(String code) {
        List<FunctionUnit> deployed = functionUnitRepository.findByCodeAndStatus(code, FunctionUnitStatus.DEPLOYED);
        if (deployed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(deployed.stream().max(versionComponent::compareBySemver).orElseThrow());
    }

    /**
     * Disable other versions for function unit code
     * @param code function unit code
     * @param enabledVersion version to keep enabled (null disables all)
     * @param operatorId operator id
     * @return list of disabled version strings
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> disableOtherVersions(String code, String enabledVersion, String operatorId) {
        log.info("Disabling other versions for code: {}, keeping enabled: {}, operator: {}",
                code, enabledVersion, operatorId);

        List<String> disabledVersions = new ArrayList<>();

        // Load all versions for code
        List<FunctionUnit> allVersions = functionUnitRepository.findAllByCodeOrderByVersionDesc(code);

        for (FunctionUnit unit : allVersions) {
            // Disable when not the version to keep and currently enabled
            // Trim compare: leading/trailing whitespace on manifest/path vs stored version must not disable the wrong deployed row
            if (!shouldKeepVersionEnabled(unit, enabledVersion) && unit.isEnabled()) {
                unit.setEnabled(false);
                functionUnitRepository.save(unit);
                disabledVersions.add(unit.getVersion());
                log.info("Disabled version {} of function unit {}", unit.getVersion(), code);
            }
        }

        // Flush so DB sees disabled rows before constraint checks
        functionUnitRepository.flush();

        log.info("Disabled {} versions for function unit {}: {}",
                disabledVersions.size(), code, disabledVersions);

        return disabledVersions;
    }

    /**
     * In {@link #disableOtherVersions(String, String, String)}, whether a row is the version that should stay enabled.
     * <p>{@code keepEnabledVersion == null} means pre-import: disable all enabled rows for the code (before inserting the new version); always false per row.
     */
    private static boolean shouldKeepVersionEnabled(FunctionUnit unit, String keepEnabledVersion) {
        if (keepEnabledVersion == null) {
            return false;
        }
        String v = unit.getVersion();
        if (v == null) {
            return false;
        }
        return v.trim().equals(keepEnabledVersion.trim());
    }

    /**
     * End of workstation one-click deploy: after disabling other versions for the code, enable the <strong>row deployed in this run</strong>.
     * <p>If a higher deployed semantic version exists, still enable this row and log a warning (matches designer publish expectation; portal start follows business rules).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FunctionUnit finalizeOneClickDeployEnable(String functionUnitId, String operatorId) {
        FunctionUnit unit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new FunctionUnitNotFoundException(functionUnitId));

        if (unit.getStatus() != FunctionUnitStatus.DEPLOYED) {
            throw new AdminBusinessException("INVALID_STATUS",
                    "Cannot finalize enable: function unit is not DEPLOYED (status=" + unit.getStatus() + ")");
        }

        Optional<FunctionUnit> maxDeployed = pickMaxSemverAmongDeployed(unit.getCode());
        if (maxDeployed.isEmpty()) {
            throw new AdminBusinessException("NO_DEPLOYED", "No deployed version exists for code: " + unit.getCode());
        }
        if (!maxDeployed.get().getId().equals(unit.getId())) {
            log.warn(
                    "One-click deploy: enabling {}:{} for operator {} while higher deployed semver exists ({})",
                    unit.getCode(), unit.getVersion(), operatorId, maxDeployed.get().getVersion());
        }

        disableOtherVersions(unit.getCode(), unit.getVersion(), operatorId);
        FunctionUnit fresh = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new FunctionUnitNotFoundException(functionUnitId));
        fresh.setEnabled(true);
        return functionUnitRepository.save(fresh);
    }

    /**
     * Currently enabled version for a code.
     */
    public Optional<FunctionUnit> getEnabledVersion(String code) {
        return functionUnitRepository.findByCodeAndEnabledTrue(code);
    }

    /**
     * Activate a specific version (same rules as {@link #setEnabled(String, boolean, String, String)}).
     * <ul>
     *   <li>Target must be {@link FunctionUnitStatus#DEPLOYED}</li>
     *   <li>Target must be the highest semantic version among all deployed rows for the code</li>
     * </ul>
     */
    @Transactional
    public FunctionUnit activateVersion(String code, String targetVersion, String operatorId) {
        log.info("Activating version {} for code: {}, operator: {}", targetVersion, code, operatorId);

        FunctionUnit targetUnit = functionUnitRepository.findByCodeAndVersion(code, targetVersion)
                .orElseThrow(() -> new FunctionUnitNotFoundException(
                        "Function unit version not found: " + code + ":" + targetVersion));

        if (targetUnit.getStatus() != FunctionUnitStatus.DEPLOYED) {
            throw new AdminBusinessException("INVALID_STATUS",
                    "Only DEPLOYED versions can be activated for portal initiation. Current status: " + targetUnit.getStatus());
        }

        FunctionUnit maxDeployed = pickMaxSemverAmongDeployed(code)
                .orElseThrow(() -> new AdminBusinessException("NO_DEPLOYED", "No deployed version exists for this code"));
        if (!maxDeployed.getId().equals(targetUnit.getId())) {
            throw new AdminBusinessException("NOT_MAX_DEPLOYED_VERSION",
                    "Only the highest semantic version among deployed versions can be activated (current highest is " + maxDeployed.getVersion() + ")");
        }

        disableOtherVersions(code, targetVersion, operatorId);

        FunctionUnit fresh = functionUnitRepository.findByCodeAndVersion(code, targetVersion)
                .orElseThrow(() -> new FunctionUnitNotFoundException(
                        "Function unit version not found: " + code + ":" + targetVersion));
        fresh.setEnabled(true);
        FunctionUnit activated = functionUnitRepository.save(fresh);

        log.info("Successfully activated version {} for function unit {}", targetVersion, code);

        return activated;
    }
}
