package com.developer.component.impl;

import com.developer.component.FunctionUnitComponent;
import com.developer.dto.DevGroupAssignmentRequest;
import com.developer.dto.FunctionUnitRequest;
import com.developer.dto.FunctionUnitResponse;
import com.developer.dto.ValidationResult;
import com.developer.dto.VersionResponse;
import com.developer.entity.*;
import com.developer.enums.FunctionUnitStatus;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.entity.FunctionUnitDevGroupAssignment;
import com.developer.repository.*;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.component.VersionComponent;
import com.developer.util.DeveloperWorkstationSequenceSynchronizer;
import com.developer.util.MinimalBpmnTemplate;
import com.developer.util.FunctionUnitTagUtils;
import com.developer.util.XmlEncodingUtil;
import com.developer.service.MainTableViewService;
import com.developer.service.UserDisplayNameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import com.platform.security.util.SecurityContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Function unit component implementation.
 *
 * 闂ㄩ潰锛坒acade锛夛細瀹炵幇 {@link FunctionUnitComponent} 鎺ュ彛锛屼繚鎸佸叏閮?public 鏂规硶绛惧悕涓庝簨鍔?閴存潈璇箟涓嶅彉銆?
 * 鍏蜂綋涓氬姟閫昏緫濮旀墭缁欏悓鍖呯殑鍗忎綔绫伙細
 * - {@link FunctionUnitCodeGenerator}锛氬敮涓€ code 鐢熸垚涓庡悕绉拌鑼冨寲
 * - {@link FunctionUnitValidator}锛氬彂甯冨墠瀹屾暣鎬ф牎楠岋紙鍚?BPMN-DMN 浜ゅ弶寮曠敤銆丏ECISION_TABLE 閰嶇疆锛?
 * - {@link FunctionUnitSnapshotFactory}锛氱増鏈彿璁＄畻涓庡彂甯冨揩鐓у簭鍒楀寲
 * - {@link FunctionUnitCloner}锛氬姛鑳藉崟鍏冩繁鎷疯礉
 * - {@link FunctionUnitResponseAssembler}锛氬疄浣撳埌鍝嶅簲 DTO 鐨勫畨鍏ㄨ浆鎹?
 */
@Component
@Slf4j
public class FunctionUnitComponentImpl implements FunctionUnitComponent {

    private final FunctionUnitRepository functionUnitRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final VersionRepository versionRepository;
    private final IconRepository iconRepository;
    private final UserDisplayNameService userDisplayNameService;
    private final FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService;
    private final FunctionUnitDevGroupAssignmentRepository functionUnitDevGroupAssignmentRepository;
    private final VersionComponent versionComponent;
    private final MainTableViewService mainTableViewService;
    private final ForeignKeyRepository foreignKeyRepository;
    private final FunctionUnitExporter functionUnitExporter;
    private final ObjectMapper objectMapper;

    // Collaborators built inside this facade; repos/services come from constructor injection.
    private final FunctionUnitCodeGenerator codeGenerator;
    private final FunctionUnitValidator validator;
    private final FunctionUnitSnapshotFactory snapshotFactory;
    private final FunctionUnitCloner cloner;
    private final FunctionUnitResponseAssembler responseAssembler;

    public FunctionUnitComponentImpl(
            FunctionUnitRepository functionUnitRepository,
            ProcessDefinitionRepository processDefinitionRepository,
            TableDefinitionRepository tableDefinitionRepository,
            FormDefinitionRepository formDefinitionRepository,
            ActionDefinitionRepository actionDefinitionRepository,
            DecisionDefinitionRepository decisionDefinitionRepository,
            FormTableBindingRepository formTableBindingRepository,
            FormStageBindingRepository formStageBindingRepository,
            TableRelationRepository tableRelationRepository,
            SubTableViewConfigRepository subTableViewConfigRepository,
            VersionRepository versionRepository,
            IconRepository iconRepository,
            ObjectMapper objectMapper,
            UserDisplayNameService userDisplayNameService,
            FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService,
            FunctionUnitDevGroupAssignmentRepository functionUnitDevGroupAssignmentRepository,
            VersionComponent versionComponent,
            DeveloperWorkstationSequenceSynchronizer sequenceSynchronizer,
            MainTableViewService mainTableViewService,
            ForeignKeyRepository foreignKeyRepository,
            FunctionUnitExporter functionUnitExporter,
            com.developer.component.TableDesignComponent tableDesignComponent,
            EmailConnectionRepository emailConnectionRepository,
            EmailMonitorRuleRepository emailMonitorRuleRepository,
            EmailTemplateRepository emailTemplateRepository) {
        this.functionUnitRepository = functionUnitRepository;
        this.processDefinitionRepository = processDefinitionRepository;
        this.versionRepository = versionRepository;
        this.iconRepository = iconRepository;
        this.userDisplayNameService = userDisplayNameService;
        this.functionUnitWorkspaceAccessService = functionUnitWorkspaceAccessService;
        this.functionUnitDevGroupAssignmentRepository = functionUnitDevGroupAssignmentRepository;
        this.versionComponent = versionComponent;
        this.mainTableViewService = mainTableViewService;
        this.foreignKeyRepository = foreignKeyRepository;
        this.functionUnitExporter = functionUnitExporter;
        this.objectMapper = objectMapper;

        // Build collaborators so Spring wiring and test `new` stay behaviorally aligned.
        this.codeGenerator = new FunctionUnitCodeGenerator(functionUnitRepository);
        this.validator = new FunctionUnitValidator();
        this.snapshotFactory = new FunctionUnitSnapshotFactory(objectMapper);
        this.cloner = new FunctionUnitCloner(
                functionUnitRepository,
                processDefinitionRepository,
                tableDefinitionRepository,
                formDefinitionRepository,
                actionDefinitionRepository,
                decisionDefinitionRepository,
                formTableBindingRepository,
                formStageBindingRepository,
                tableRelationRepository,
                subTableViewConfigRepository,
                emailConnectionRepository,
                emailTemplateRepository,
                new EmailMonitorRulePortability(emailMonitorRuleRepository, objectMapper),
                objectMapper,
                functionUnitWorkspaceAccessService,
                sequenceSynchronizer,
                this.codeGenerator,
                mainTableViewService,
                tableDesignComponent);
        this.responseAssembler = new FunctionUnitResponseAssembler(functionUnitDevGroupAssignmentRepository);
    }

    /**
     * Returns the current operator.
     * Prefer Spring Security Context; return "system" when unavailable.
     *
     * Cases that yield "system":
     * - No authentication (not logged in)
     * - Anonymous user
     * - System background job
     * - Exception while resolving operator
     *
     * @return current operator username, or "system" when unavailable
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
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('TECH_LEAD', 'TEAM_LEAD')")
    public FunctionUnit create(FunctionUnitRequest request) {
        if (functionUnitRepository.existsByName(request.getName())) {
            throw new DeveloperBusinessException("CONFLICT_NAME_EXISTS",
                    "Function unit name already exists: " + request.getName(),
                    "Please use a different name");
        }

        // Generate unique code
        String code = codeGenerator.generateUniqueCode(request.getName());

        FunctionUnit functionUnit = FunctionUnit.builder()
                .name(request.getName())
                .code(code)
                .displayName(request.getDescription())
                .tags(FunctionUnitTagUtils.normalizeTags(request.getTags()))
                .status(FunctionUnitStatus.DRAFT)
                .build();

        if (request.getIconId() != null) {
            Icon icon = iconRepository.findById(request.getIconId())
                    .orElseThrow(() -> new ResourceNotFoundException("Icon", request.getIconId()));
            functionUnit.setIcon(icon);
        }

        functionUnit = functionUnitRepository.save(functionUnit);

        // 创建时绑定所属团队（虚拟组）——决定 FU 的可见范围。此处不走 ASSIGN_DEV_GROUPS 工作区校验：
        // 创建动作已由 @RequireDeveloperPermission(FUNCTION_UNIT_CREATE) + @PreAuthorize 校验，
        // 且新 FU 尚未 inScope，无法在创建后再自助分配（否则陷入「不可见→不可分配」死锁）。
        persistCreateTimeDevGroups(functionUnit.getId(),
                functionUnitWorkspaceAccessService.resolveCreationTeamGroupIds(request.getVirtualGroupIds()));

        String initialBpmnXml = MinimalBpmnTemplate.build(functionUnit.getCode());
        ProcessDefinition initialProcess = ProcessDefinition.builder()
                .functionUnit(functionUnit)
                .functionUnitVersionId(functionUnit.getId())
                .bpmnXml(XmlEncodingUtil.encode(initialBpmnXml))
                .build();
        processDefinitionRepository.save(initialProcess);

        return functionUnit;
    }

    private void persistCreateTimeDevGroups(Long functionUnitId, List<String> virtualGroupIds) {
        if (virtualGroupIds == null || virtualGroupIds.isEmpty()) {
            return;
        }
        String operator = getCurrentOperator();
        for (String gid : virtualGroupIds) {
            if (gid == null || gid.isBlank()) {
                continue;
            }
            functionUnitDevGroupAssignmentRepository.save(FunctionUnitDevGroupAssignment.builder()
                    .functionUnitId(functionUnitId)
                    .virtualGroupId(gid.trim())
                    .createdAt(Instant.now())
                    .createdBy(operator)
                    .build());
        }
    }

    @Override
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('TECH_LEAD', 'TEAM_LEAD', 'DEVELOPER')")
    public FunctionUnit update(Long id, FunctionUnitRequest request) {
        functionUnitWorkspaceAccessService.assertCanAccess(id, WorkspaceAccessAction.MODIFY);
        FunctionUnit functionUnit = getById(id);

        if (functionUnitRepository.existsByNameAndIdNot(request.getName(), id)) {
            throw new DeveloperBusinessException("CONFLICT_NAME_EXISTS",
                    "Function unit name already exists: " + request.getName(),
                    "Please use a different name");
        }

        functionUnit.setName(request.getName());
        // Only update description when explicitly provided (non-null); null means "keep existing"
        if (request.getDescription() != null) {
            functionUnit.setDisplayName(request.getDescription());
        }
        if (request.getTags() != null) {
            functionUnit.setTags(FunctionUnitTagUtils.normalizeTags(request.getTags()));
        }

        // Only update icon when iconId is provided (non-null); null means "keep existing"
        if (request.getIconId() != null) {
            Icon icon = iconRepository.findById(request.getIconId())
                    .orElseThrow(() -> new ResourceNotFoundException("Icon", request.getIconId()));
            functionUnit.setIcon(icon);
        }

        return functionUnitRepository.save(functionUnit);
    }

    @Override
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('TECH_LEAD', 'TEAM_LEAD')")
    public void delete(Long id) {
        functionUnitWorkspaceAccessService.assertCanAccess(id, WorkspaceAccessAction.DELETE);
        FunctionUnit functionUnit = getById(id);
        if (functionUnit.getStatus() != FunctionUnitStatus.ARCHIVED) {
            functionUnit.setStatus(FunctionUnitStatus.ARCHIVED);
            functionUnitRepository.save(functionUnit);
            log.info("Archived function unit id={}, name={}", id, functionUnit.getName());
            return;
        }
        // Foreign keys must be removed BEFORE the JPA cascade walks the table/field
        // graph. A dw_foreign_keys row's field_id/ref_field_id are NOT NULL, and they
        // are NOT mapped by an owning collection on FieldDefinition. So when the cascade
        // deletes a FieldDefinition, Hibernate tries to dissociate the still-loaded
        // ForeignKey via UPDATE ... SET field_id=null and violates the NOT NULL
        // constraint (the delete of an ARCHIVED unit then fails entirely). Bulk-deleting
        // the FU's foreign keys first clears those references; the same guard exists on
        // the re-import/rollback path (see VersionComponentImpl). Mirror the entity
        // collections too so the persistence context does not re-flush stale rows.
        for (TableDefinition table : functionUnit.getTableDefinitions()) {
            if (table.getForeignKeys() != null) {
                table.getForeignKeys().clear();
            }
        }
        foreignKeyRepository.deleteByFunctionUnitId(id);
        foreignKeyRepository.flush();

        // Note: dw_function_unit_dev_groups has ON DELETE CASCADE, so the FK
        // constraint handles child-row removal automatically.  We must NOT issue a
        // separate @Modifying JPQL DELETE here because it flushes the persistence
        // context and creates stale state for JPA cascade operations.
        functionUnitRepository.delete(functionUnit);
        log.info("Permanently deleted archived function unit id={}", id);
    }

    @Override
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('TECH_LEAD', 'TEAM_LEAD')")
    public FunctionUnit restore(Long id) {
        functionUnitWorkspaceAccessService.assertCanAccess(id, WorkspaceAccessAction.MODIFY);
        FunctionUnit functionUnit = getById(id);
        if (functionUnit.getStatus() != FunctionUnitStatus.ARCHIVED) {
            throw new DeveloperBusinessException("BIZ_NOT_ARCHIVED",
                    "Only an archived function unit can be restored");
        }
        functionUnit.setStatus(FunctionUnitStatus.DRAFT);
        FunctionUnit saved = functionUnitRepository.save(functionUnit);
        log.info("Restored function unit id={}, name={} to DRAFT", id, functionUnit.getName());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public FunctionUnit getById(Long id) {
        return functionUnitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", id));
    }

    @Override
    @Transactional(readOnly = true)
    public FunctionUnitResponse getByIdAsResponse(Long id) {
        functionUnitWorkspaceAccessService.assertCanAccess(id, WorkspaceAccessAction.VIEW);
        FunctionUnit entity = getById(id);
        FunctionUnitResponse response = toResponse(entity);
        response.setCanModify(functionUnitWorkspaceAccessService.canAccess(id, WorkspaceAccessAction.MODIFY));
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canAccessWorkspace() {
        String userId = SecurityContextUtils.getCurrentUserId().orElse(null);
        return functionUnitWorkspaceAccessService.canEnterWorkspace(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public com.developer.dto.MyDevGroupsResponse getMyDevGroups() {
        String userId = SecurityContextUtils.getCurrentUserId().orElse(null);
        return com.developer.dto.MyDevGroupsResponse.builder()
                .groups(functionUnitWorkspaceAccessService.getSelectableTeams(userId))
                .canSeeAllGroups(functionUnitWorkspaceAccessService.canSeeAllGroups(userId))
                .publicGroupId(com.developer.security.DevGroupConstants.PUBLIC_GROUP_ID)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FunctionUnitResponse> list(String name, String status, java.util.List<String> tags, Pageable pageable) {
        Specification<FunctionUnit> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Only show enabled versions to users
            predicates.add(cb.equal(root.get("enabled"), true));

            if (name != null && !name.trim().isEmpty()) {
                // Escape SQL LIKE special characters to prevent injection
                String escapedName = name.trim().toLowerCase()
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + escapedName + "%", '\\'));
            }

            if (status != null && !status.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("status"), FunctionUnitStatus.valueOf(status)));
            }

            // Server-side tag filter (AND semantics): function unit must have ALL specified tags.
            // Uses PostgreSQL JSONB ?& operator via FUNCTION('jsonb_exists_all', ...).
            if (tags != null && !tags.isEmpty()) {
                List<String> normalized = FunctionUnitTagUtils.normalizeTags(tags);
                if (!normalized.isEmpty()) {
                    Expression<String[]> tagArray = cb.literal(normalized.toArray(new String[0]));
                    Expression<Boolean> tagExpr = cb.function(
                            "jsonb_exists_all",
                            Boolean.class,
                            root.get("tags"),
                            tagArray);
                    predicates.add(cb.isTrue(tagExpr));
                }
            }

            java.util.Set<Long> visible = functionUnitWorkspaceAccessService.visibleFunctionUnitIds();
            if (visible != null && visible.isEmpty()) {
                predicates.add(cb.disjunction());
            } else if (visible != null) {
                predicates.add(root.get("id").in(visible));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        // Query via Specification; load associations manually
        // Specification has no EntityGraph; toResponse handles lazy loading safely
        Page<FunctionUnit> page = functionUnitRepository.findAll(spec, pageable);

        // Trigger lazy loading in transaction so associations are initialized
        page.getContent().forEach(entity -> {
            try {
                // Trigger lazy loading
                if (entity.getTableDefinitions() != null) {
                    entity.getTableDefinitions().size();
                }
                if (entity.getFormDefinitions() != null) {
                    entity.getFormDefinitions().size();
                }
                if (entity.getActionDefinitions() != null) {
                    entity.getActionDefinitions().size();
                }
                if (entity.getDecisionDefinitions() != null) {
                    entity.getDecisionDefinitions().size();
                }
                if (entity.getProcessDefinition() != null) {
                    entity.getProcessDefinition().getId();
                }
            } catch (Exception e) {
                log.warn("Failed to eagerly load relations for function unit {}: {}", entity.getId(), e.getMessage());
            }
        });

        java.util.Set<Long> modifiable = functionUnitWorkspaceAccessService.modifiableFunctionUnitIds();
        return page.map(entity -> {
            FunctionUnitResponse response = toResponse(entity);
            response.setCanModify(modifiable == null || modifiable.contains(entity.getId()));
            return response;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<String> getAllTags() {
        return functionUnitRepository.findAllDistinctTags();
    }

    @Override
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('TECH_LEAD', 'TEAM_LEAD', 'DEVELOPER')")
    public FunctionUnit publish(Long id, String changeLog) {
        functionUnitWorkspaceAccessService.assertCanAccess(id, WorkspaceAccessAction.MODIFY);
        FunctionUnit functionUnit = getById(id);

        // Validate function unit completeness
        ValidationResult validationResult = validate(id);
        if (!validationResult.isValid()) {
            throw new DeveloperBusinessException("BIZ_INVALID_FUNCTION_UNIT",
                    "Function unit validation failed, cannot publish",
                    "Please fix validation errors before retrying");
        }

        // Compute new version number
        String newVersion = snapshotFactory.calculateNextVersion(functionUnit.getCurrentVersion());

        // Check version exists to avoid unique constraint conflict
        boolean versionAlreadyExists = versionRepository.findByFunctionUnitIdAndVersionNumber(id, newVersion).isPresent();
        if (versionAlreadyExists) {
            // Version snapshot exists but currentVersion not updated (failed deploy); allow completing status update
            log.warn("Version snapshot {} already exists but function unit status not updated, continuing publish flow, functionUnitId={}", newVersion, id);
        } else {
            // Create version snapshot
            try {
                byte[] snapshotData = createSnapshot(functionUnit);
                Version version = Version.builder()
                        .functionUnit(functionUnit)
                        .versionNumber(newVersion)
                        .changeLog(changeLog)
                        .snapshotData(snapshotData)
                        .publishedBy(getCurrentOperator())
                        .build();
                versionRepository.save(version);
            } catch (DeveloperBusinessException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to create version snapshot, functionUnitId={}, version={}: {}", id, newVersion, e.getMessage(), e);
                throw new DeveloperBusinessException("SYS_SNAPSHOT_ERROR", "Failed to create version snapshot: " + e.getMessage());
            }
        }

        // Update function unit status
        functionUnit.setStatus(FunctionUnitStatus.PUBLISHED);
        functionUnit.setCurrentVersion(newVersion);
        mainTableViewService.publishViewsForFunctionUnit(id);

        return functionUnitRepository.save(functionUnit);
    }

    @Override
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('TECH_LEAD', 'TEAM_LEAD')")
    public FunctionUnit clone(Long id, String newName) {
        return cloner.clone(id, newName);
    }

    @Override
    @Transactional(readOnly = true)
    public ValidationResult validate(Long id) {
        functionUnitWorkspaceAccessService.assertCanAccess(id, WorkspaceAccessAction.VIEW);
        FunctionUnit functionUnit = getById(id);
        ValidationResult result = new ValidationResult();
        validator.validate(functionUnit, result);
        return result;
    }

    @Override
    public boolean existsByName(String name) {
        return functionUnitRepository.existsByName(name);
    }

    @Override
    public boolean existsByNameAndIdNot(String name, Long id) {
        return functionUnitRepository.existsByNameAndIdNot(name, id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VersionResponse> getVersionHistory(Long functionUnitId) {
        functionUnitWorkspaceAccessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", functionUnitId));
        String activeVersionNumber = functionUnit.getCurrentVersion();
        return versionRepository.findByFunctionUnitIdOrderByPublishedAtDesc(functionUnitId)
                .stream()
                .map(v -> {
                    VersionResponse resp = VersionResponse.from(v, activeVersionNumber);
                    resp.setCreatedBy(resolveUserDisplayName(v.getPublishedBy()));
                    return resp;
                })
                .toList();
    }

    @Override
    @Transactional
    public FunctionUnit rollback(Long functionUnitId, Long versionId) {
        functionUnitWorkspaceAccessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);
        return versionComponent.rollback(functionUnitId, versionId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> compareVersions(Long functionUnitId, Long versionId1, Long versionId2) {
        functionUnitWorkspaceAccessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        assertVersionBelongsToFunctionUnit(functionUnitId, versionId1);
        assertVersionBelongsToFunctionUnit(functionUnitId, versionId2);
        return versionComponent.compare(versionId1, versionId2);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportVersion(Long functionUnitId, Long versionId) {
        functionUnitWorkspaceAccessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        assertVersionBelongsToFunctionUnit(functionUnitId, versionId);
        return versionComponent.exportVersion(versionId);
    }

    /**
     * Authorization: ensure version belongs to functionUnit so users cannot read another unit's versions.
     */
    private void assertVersionBelongsToFunctionUnit(Long functionUnitId, Long versionId) {
        Version version = versionComponent.getById(versionId);
        if (version.getFunctionUnit() == null
                || !functionUnitId.equals(version.getFunctionUnit().getId())) {
            throw new DeveloperBusinessException(
                    "BIZ_VERSION_MISMATCH",
                    "Version " + versionId + " does not belong to function unit " + functionUnitId);
        }
    }

    private String resolveUserDisplayName(String userId) {
        return userDisplayNameService.resolve(userId);
    }

    private FunctionUnitResponse toResponse(FunctionUnit entity) {
        return responseAssembler.toResponse(entity);
    }

    /**
     * Uses export-format serializers so Publish snapshots match Rollback restore parity.
     */
    private byte[] createSnapshot(FunctionUnit functionUnit) throws Exception {
        Map<String, Object> payload = functionUnitExporter.buildVersionSnapshotPayload(functionUnit.getId());
        return objectMapper.writeValueAsBytes(payload);
    }

    @Override
    @Transactional
    public void replaceDevGroupAssignments(Long functionUnitId, DevGroupAssignmentRequest request) {
        functionUnitWorkspaceAccessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.ASSIGN_DEV_GROUPS);
        getById(functionUnitId);
        functionUnitDevGroupAssignmentRepository.deleteByFunctionUnitId(functionUnitId);
        String operator = getCurrentOperator();
        if (request.getVirtualGroupIds() == null) {
            return;
        }
        for (String gid : request.getVirtualGroupIds()) {
            if (gid == null || gid.isBlank()) {
                continue;
            }
            functionUnitDevGroupAssignmentRepository.save(FunctionUnitDevGroupAssignment.builder()
                    .functionUnitId(functionUnitId)
                    .virtualGroupId(gid.trim())
                    .createdAt(Instant.now())
                    .createdBy(operator)
                    .build());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getDevGroupAssignments(Long functionUnitId) {
        functionUnitWorkspaceAccessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        getById(functionUnitId);
        return functionUnitDevGroupAssignmentRepository.findByFunctionUnitId(functionUnitId).stream()
                .map(FunctionUnitDevGroupAssignment::getVirtualGroupId)
                .toList();
    }
}
