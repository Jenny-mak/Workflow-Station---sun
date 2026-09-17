package com.developer.component.impl;

import com.developer.component.VersionComponent;
import com.developer.entity.FunctionUnit;
import com.developer.entity.TableDefinition;
import com.developer.entity.Version;
import com.developer.enums.FunctionUnitStatus;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.ForeignKeyRepository;
import com.developer.repository.FunctionUnitRepository;
import com.developer.repository.LinkFormComponentRepository;
import com.developer.repository.ProcessDefinitionRepository;
import com.developer.repository.SubTableViewConfigRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.repository.VersionRepository;
import com.developer.service.MainTableViewService;
import com.developer.service.impl.FunctionUnitDocumentService;
import com.developer.util.DeveloperWorkstationSequenceSynchronizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.security.util.SecurityContextUtils;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Version management component implementation. */
@Component
@Slf4j
@RequiredArgsConstructor
public class VersionComponentImpl implements VersionComponent {
    
    private final VersionRepository versionRepository;
    private final FunctionUnitRepository functionUnitRepository;
    private final ObjectMapper objectMapper;
    private final DeveloperWorkstationSequenceSynchronizer sequenceSynchronizer;
    private final FunctionUnitExporter functionUnitExporter;
    private final FunctionUnitSnapshotRestorer snapshotRestorer;
    private final MainTableViewService mainTableViewService;
    private final SubTableViewConfigRepository subTableViewConfigRepository;
    private final ForeignKeyRepository foreignKeyRepository;
    private final LinkFormComponentRepository linkFormComponentRepository;
    private final EmailConnectionRepository emailConnectionRepository;
    private final EmailMonitorRuleRepository emailMonitorRuleRepository;
    private final EmailTemplateRepository emailTemplateRepository;
    private final TableRelationRepository tableRelationRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final EntityManager entityManager;
    private final FunctionUnitDocumentService documentService;
    
    /**
     * Resolves current operator username.
     * Prefers Spring Security context; falls back to {@code "system"} when unavailable.
     */
    private String getCurrentOperator() {
        try {
            return SecurityContextUtils.getCurrentUsername().orElse("system");
        } catch (Exception e) {
            log.debug("Failed to get current operator from security context: {}", e.getMessage());
        }
        return "system";
    }
    
    @Override
    @Transactional
    public Version createVersion(Long functionUnitId, String changeLog) {
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", functionUnitId));
        
        String newVersion = nextFreeVersion(functionUnitId, functionUnit.getCurrentVersion());
        
        try {
            byte[] snapshotData = createSnapshot(functionUnit);
            
            Version version = Version.builder()
                    .functionUnit(functionUnit)
                    .versionNumber(newVersion)
                    .changeLog(changeLog)
                    .snapshotData(snapshotData)
                    .publishedBy(getCurrentOperator())
                    .build();
            
            version = versionRepository.save(version);
            
            functionUnit.setCurrentVersion(newVersion);
            functionUnitRepository.save(functionUnit);
            
            return version;
        } catch (DeveloperBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create version snapshot, functionUnitId={}, version={}: {}", functionUnitId, newVersion, e.getMessage(), e);
            throw new DeveloperBusinessException("SYS_SNAPSHOT_ERROR", "Failed to create version snapshot: " + e.getMessage());
        }
    }
    
    @Override
    @Transactional
    public String snapshotAndClearForReimport(FunctionUnit functionUnit, String changeLog) {
        try {
            sequenceSynchronizer.synchronizeAll();

            String snapshotVersion = nextFreeVersion(functionUnit.getId(), functionUnit.getCurrentVersion());
            Version snapshot = Version.builder()
                    .functionUnit(functionUnit)
                    .versionNumber(snapshotVersion)
                    .changeLog(changeLog != null && !changeLog.isBlank()
                            ? changeLog : "Auto snapshot before re-import")
                    .snapshotData(createSnapshot(functionUnit))
                    .publishedBy(getCurrentOperator())
                    .build();
            versionRepository.saveAndFlush(snapshot);

            clearChildCollectionsAndFlush(functionUnit);
            functionUnit = refreshFunctionUnitAfterNativeClear(functionUnit);
            if (functionUnit.getProcessDefinition() != null) {
                functionUnit.setProcessDefinition(null);
                functionUnitRepository.saveAndFlush(functionUnit);
            }

            sequenceSynchronizer.synchronizeAllInTransaction();

            return functionUnit.getCurrentVersion();
        } catch (DeveloperBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to snapshot/clear before re-import, functionUnitId={}: {}",
                    functionUnit.getId(), e.getMessage(), e);
            throw new DeveloperBusinessException("SYS_SNAPSHOT_ERROR",
                    "Failed to snapshot before re-import: " + e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Version> getVersionHistory(Long functionUnitId) {
        return versionRepository.findByFunctionUnitIdOrderByPublishedAtDesc(functionUnitId);
    }
    
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> compare(Long versionId1, Long versionId2) {
        Version version1 = getById(versionId1);
        Version version2 = getById(versionId2);
        
        Map<String, Object> result = new HashMap<>();
        result.put("version1", Map.of(
                "id", version1.getId(),
                "versionNumber", version1.getVersionNumber(),
                "publishedAt", version1.getPublishedAt()
        ));
        result.put("version2", Map.of(
                "id", version2.getId(),
                "versionNumber", version2.getVersionNumber(),
                "publishedAt", version2.getPublishedAt()
        ));
        
        try {
            Map<String, Object> snapshot1 = objectMapper.readValue(version1.getSnapshotData(), Map.class);
            Map<String, Object> snapshot2 = objectMapper.readValue(version2.getSnapshotData(), Map.class);
            
            Map<String, Object> differences = findDifferences(snapshot1, snapshot2);
            result.put("differences", differences);
        } catch (Exception e) {
            log.error("Failed to compare versions", e);
            result.put("differences", Map.of("error", "Failed to compare versions"));
        }
        
        return result;
    }
    
    @Override
    @Transactional
    public FunctionUnit rollback(Long functionUnitId, Long versionId) {
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", functionUnitId));
        
        Version targetVersion = getById(versionId);
        
        if (!targetVersion.getFunctionUnit().getId().equals(functionUnitId)) {
            throw new DeveloperBusinessException("BIZ_VERSION_MISMATCH", "Version does not belong to this function unit");
        }

        String currentVersionNumber = functionUnit.getCurrentVersion();
        if (currentVersionNumber != null
                && currentVersionNumber.equals(targetVersion.getVersionNumber())) {
            throw new DeveloperBusinessException(
                    "BIZ_ROLLBACK_TO_CURRENT",
                    "Target version " + targetVersion.getVersionNumber()
                            + " is already the current active version, rollback is not needed.");
        }
        
        try {
            sequenceSynchronizer.synchronizeAll();

            String backupVersion = nextFreeVersion(functionUnitId, functionUnit.getCurrentVersion());
            Version backup = Version.builder()
                    .functionUnit(functionUnit)
                    .versionNumber(backupVersion)
                    .changeLog("Auto backup before rollback")
                    .snapshotData(createSnapshot(functionUnit))
                    .publishedBy(getCurrentOperator())
                    .build();
            versionRepository.saveAndFlush(backup);

            clearChildCollectionsAndFlush(functionUnit);
            functionUnit = refreshFunctionUnitAfterNativeClear(functionUnit);
            sequenceSynchronizer.synchronizeAllInTransaction();
            
            Map<String, Object> snapshot = objectMapper.readValue(targetVersion.getSnapshotData(), Map.class);
            snapshotRestorer.restore(functionUnit, snapshot);
            // Documents are appended (history kept); snapshots taken before they existed leave them untouched
            documentService.appendFromPackage(functionUnitId,
                    FunctionUnitDocumentService.fromPackage(snapshot.get(FunctionUnitDocumentService.PACKAGE_KEY)),
                    FunctionUnitDocumentService.SUMMARY_ROLLBACK_PREFIX + targetVersion.getVersionNumber(),
                    getCurrentOperator());

            functionUnitRepository.saveAndFlush(functionUnit);
            sequenceSynchronizer.synchronizeVersions();

            FunctionUnitStatus restoredStatus = resolveStatusFromSnapshot(snapshot);
            functionUnit.setStatus(restoredStatus);
            if (restoredStatus == FunctionUnitStatus.PUBLISHED) {
                mainTableViewService.publishViewsForFunctionUnit(functionUnitId);
            }
            
            String newVersion = nextFreeVersion(functionUnitId, backupVersion);
            Version rollbackVersion = Version.builder()
                    .functionUnit(functionUnit)
                    .versionNumber(newVersion)
                    .changeLog("Rollback to version " + targetVersion.getVersionNumber())
                    .snapshotData(targetVersion.getSnapshotData())
                    .publishedBy(getCurrentOperator())
                    .build();
            versionRepository.saveAndFlush(rollbackVersion);
            
            functionUnit.setCurrentVersion(newVersion);
            
            return functionUnitRepository.save(functionUnit);
        } catch (DeveloperBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Rollback failed, functionUnitId={}, versionId={}", functionUnitId, versionId, e);
            throw new DeveloperBusinessException("SYS_ROLLBACK_ERROR", "Rollback failed: " + e.getMessage());
        }
    }

    /**
     * Clears child collections keyed by unique (function_unit_id, *) constraints and flushes immediately
     * so DELETE runs before INSERT, avoiding Hibernate's default INSERT-before-DELETE ordering conflicts.
     */
    private void clearChildCollectionsAndFlush(FunctionUnit functionUnit) {
        subTableViewConfigRepository.deleteViewFieldsByFunctionUnitId(functionUnit.getId());
        subTableViewConfigRepository.deleteConfigsByFunctionUnitId(functionUnit.getId());
        linkFormComponentRepository.deleteByFunctionUnitId(functionUnit.getId());

        boolean hadForeignKeys = false;
        for (TableDefinition table : functionUnit.getTableDefinitions()) {
            if (table.getForeignKeys() != null && !table.getForeignKeys().isEmpty()) {
                table.getForeignKeys().clear();
                hadForeignKeys = true;
            }
        }
        if (hadForeignKeys) {
            foreignKeyRepository.deleteByFunctionUnitId(functionUnit.getId());
            foreignKeyRepository.flush();
        }

        Long functionUnitId = functionUnit.getId();
        emailMonitorRuleRepository.deleteByFunctionUnitId(functionUnitId);
        emailConnectionRepository.deleteByFunctionUnitId(functionUnitId);
        emailTemplateRepository.deleteByFunctionUnitId(functionUnitId);
        tableRelationRepository.deleteByFunctionUnitId(functionUnitId);
        // Explicit delete of the OneToOne process definition. Relying on orphanRemoval via
        // functionUnit.setProcessDefinition(null) does NOT fire after refreshFunctionUnitAfterNativeClear
        // (entityManager.clear() detaches the original, so Hibernate sees no old value to orphan-remove);
        // the old row then survives and the re-import's new insert reuses its id (dw_process_definitions_pkey).
        // Null the in-memory reference FIRST: the native delete below bypasses the persistence context, so
        // if functionUnit still referenced the (now deleted) process def, the cascade=ALL saveAndFlush at the
        // end of this method would re-insert it under a fresh id — which the importer's own insert then collides with.
        functionUnit.setProcessDefinition(null);
        processDefinitionRepository.deleteByFunctionUnitId(functionUnitId);
        emailMonitorRuleRepository.flush();
        emailConnectionRepository.flush();
        emailTemplateRepository.flush();
        tableRelationRepository.flush();
        processDefinitionRepository.flush();
        if (functionUnit.getEmailConnections() != null) {
            functionUnit.getEmailConnections().clear();
        }
        if (functionUnit.getTableRelations() != null) {
            functionUnit.getTableRelations().clear();
        }

        boolean dirty = false;
        if (!functionUnit.getActionDefinitions().isEmpty()) {
            functionUnit.getActionDefinitions().clear();
            dirty = true;
        }
        if (!functionUnit.getFormDefinitions().isEmpty()) {
            functionUnit.getFormDefinitions().clear();
            dirty = true;
        }
        if (!functionUnit.getDecisionDefinitions().isEmpty()) {
            functionUnit.getDecisionDefinitions().clear();
            dirty = true;
        }
        if (!functionUnit.getTableDefinitions().isEmpty()) {
            functionUnit.getTableDefinitions().clear();
            dirty = true;
        }
        if (dirty) {
            functionUnitRepository.saveAndFlush(functionUnit);
        }
    }

    /**
     * Native DELETE bypasses Hibernate; entities loaded during {@link #createSnapshot} (e.g.
     * {@code SubTableViewConfig}) can remain managed. After sequence sync the next INSERT may reuse
     * the same id and trigger NonUniqueObjectException on restore.
     */
    private FunctionUnit refreshFunctionUnitAfterNativeClear(FunctionUnit functionUnit) {
        entityManager.flush();
        entityManager.clear();
        return functionUnitRepository.findById(functionUnit.getId())
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", functionUnit.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportVersion(Long versionId) {
        Version version = getById(versionId);
        return version.getSnapshotData();
    }
    
    @Override
    @Transactional(readOnly = true)
    public Version getById(Long id) {
        return versionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Version", id));
    }
    
    private String calculateNextVersion(String currentVersion) {
        if (currentVersion == null || currentVersion.isBlank()) {
            return "1.0.0";
        }
        String clean = currentVersion.trim().split("-")[0];
        String[] parts = clean.split("\\.");
        if (parts.length < 3) {
            return "1.0.0";
        }
        try {
            int major = Integer.parseInt(parts[0].trim());
            int minor = Integer.parseInt(parts[1].trim());
            int patch = Integer.parseInt(parts[2].trim()) + 1;
            return major + "." + minor + "." + patch;
        } catch (NumberFormatException e) {
            return "1.0.0";
        }
    }

    /**
     * First version number after {@code after} not yet used by this FU. Re-import snapshots do not
     * advance currentVersion, so the plain "current + 1" can already exist (uk_version_fu).
     */
    private String nextFreeVersion(Long fuId, String after) {
        String candidate = calculateNextVersion(after);
        while (versionRepository.findByFunctionUnitIdAndVersionNumber(fuId, candidate).isPresent()) {
            candidate = calculateNextVersion(candidate);
        }
        return candidate;
    }

    private byte[] createSnapshot(FunctionUnit functionUnit) throws Exception {
        Map<String, Object> payload = functionUnitExporter.buildVersionSnapshotPayload(functionUnit.getId());
        return objectMapper.writeValueAsBytes(payload);
    }

    /** Restore FU status from snapshot; legacy snapshots without status default to DRAFT. */
    static FunctionUnitStatus resolveStatusFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return FunctionUnitStatus.DRAFT;
        }
        Object status = snapshot.get("status");
        if (!(status instanceof String statusStr) || statusStr.isBlank()) {
            return FunctionUnitStatus.DRAFT;
        }
        try {
            return FunctionUnitStatus.valueOf(statusStr.trim());
        } catch (IllegalArgumentException e) {
            return FunctionUnitStatus.DRAFT;
        }
    }
    
    private Map<String, Object> findDifferences(Map<String, Object> map1, Map<String, Object> map2) {
        Map<String, Object> differences = new HashMap<>();
        
        for (String key : map1.keySet()) {
            Object value1 = map1.get(key);
            Object value2 = map2.get(key);
            
            if (value2 == null) {
                differences.put(key, Map.of("type", "removed", "oldValue", value1));
            } else if (!value1.equals(value2)) {
                differences.put(key, Map.of("type", "modified", "oldValue", value1, "newValue", value2));
            }
        }
        
        for (String key : map2.keySet()) {
            if (!map1.containsKey(key)) {
                differences.put(key, Map.of("type", "added", "newValue", map2.get(key)));
            }
        }
        
        return differences;
    }
}
