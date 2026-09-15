package com.admin.bi.service.impl;

import com.admin.bi.component.SupersetRoleSyncComponent;
import com.admin.bi.dto.request.RbacMappingCreateRequest;
import com.admin.bi.dto.request.RbacMappingUpdateRequest;
import com.admin.bi.dto.response.RbacMappingResponse;
import com.admin.bi.dto.response.RoleOptionResponse;
import com.admin.bi.dto.response.SupersetRoleResponse;
import com.admin.bi.dto.response.SyncResultResponse;
import com.admin.bi.entity.BiRbacMapping;
import com.admin.bi.entity.BiSupersetRole;
import com.admin.bi.enums.SupersetRoleStatus;
import com.admin.bi.repository.BiRbacMappingRepository;
import com.admin.bi.repository.BiSupersetRoleRepository;
import com.admin.bi.service.BiRbacMappingService;
import com.admin.repository.RoleRepository;
import com.platform.security.entity.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RBAC 映射 Service 实现
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BiRbacMappingServiceImpl implements BiRbacMappingService {

    private final BiRbacMappingRepository mappingRepository;
    private final BiSupersetRoleRepository supersetRoleRepository;
    private final RoleRepository roleRepository;
    private final SupersetRoleSyncComponent supersetRoleSyncComponent;

    @Override
    public SyncResultResponse syncSupersetRoles() {
        log.info("Manual Superset Role sync triggered");
        return supersetRoleSyncComponent.executeSyncOperation();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupersetRoleResponse> listSupersetRoles() {
        return supersetRoleRepository.findAll().stream()
                .map(this::toSupersetRoleResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RbacMappingResponse> listMappings(String roleName, String roleType) {
        // 1. Query all mappings to determine which sys_role_ids have mapping records
        List<BiRbacMapping> allMappings = mappingRepository.findAll();
        Map<String, List<BiRbacMapping>> mappingsBySysRole = allMappings.stream()
                .collect(Collectors.groupingBy(BiRbacMapping::getSysRoleId));

        // 2. Only include roles that have mapping records (fix: was previously all active roles)
        Set<String> mappedSysRoleIds = mappingsBySysRole.keySet();
        if (mappedSysRoleIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. Query active roles and filter to only those with mapping records,
        //    then apply optional roleName/roleType filters within mapped roles only
        List<Role> filteredRoles = roleRepository.findAllActive().stream()
                .filter(r -> mappedSysRoleIds.contains(r.getId()))
                .filter(r -> roleName == null ||
                        r.getName().toLowerCase().contains(roleName.toLowerCase()))
                .filter(r -> roleType == null ||
                        r.getType().equalsIgnoreCase(roleType))
                .collect(Collectors.toList());

        // 4. Build superset role lookup
        Map<Integer, BiSupersetRole> supersetRoleMap = supersetRoleRepository.findAll().stream()
                .collect(Collectors.toMap(BiSupersetRole::getSupersetRoleId, r -> r));

        // 5. Build response for each mapped sys role
        return filteredRoles.stream().map(role -> {
            List<BiRbacMapping> roleMappings = mappingsBySysRole.getOrDefault(role.getId(), Collections.emptyList());
            List<SupersetRoleResponse> supersetRoles = roleMappings.stream()
                    .map(m -> supersetRoleMap.get(m.getSupersetRoleId()))
                    .filter(Objects::nonNull)
                    .map(this::toSupersetRoleResponse)
                    .collect(Collectors.toList());

            LocalDateTime lastUpdated = roleMappings.stream()
                    .map(BiRbacMapping::getCreatedAt)
                    .filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);

            return RbacMappingResponse.builder()
                    .sysRoleId(role.getId())
                    .sysRoleName(role.getName())
                    .sysRoleCode(role.getCode())
                    .sysRoleType(role.getType())
                    .supersetRoles(supersetRoles)
                    .lastUpdatedAt(lastUpdated)
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateMapping(String sysRoleId, RbacMappingUpdateRequest request) {
        // 1. Validate: only ACTIVE Superset_Roles can be mapped
        List<Integer> requestedIds = request.getSupersetRoleIds();
        if (requestedIds != null && !requestedIds.isEmpty()) {
            List<BiSupersetRole> supersetRoles = supersetRoleRepository.findBySupersetRoleIdIn(requestedIds);
            Map<Integer, BiSupersetRole> foundMap = supersetRoles.stream()
                    .collect(Collectors.toMap(BiSupersetRole::getSupersetRoleId, r -> r));

            for (Integer id : requestedIds) {
                BiSupersetRole role = foundMap.get(id);
                if (role == null) {
                    throw new IllegalArgumentException("Superset Role not found: " + id);
                }
                if (role.getStatus() != SupersetRoleStatus.ACTIVE) {
                    throw new IllegalArgumentException("Superset Role is not ACTIVE: " + id);
                }
            }
        }

        // 2. Delete all existing mappings for this sysRoleId
        mappingRepository.deleteBySysRoleId(sysRoleId);
        mappingRepository.flush();

        // 3. Create new mappings
        if (requestedIds != null) {
            for (Integer supersetRoleId : requestedIds) {
                BiRbacMapping mapping = BiRbacMapping.builder()
                        .id(UUID.randomUUID().toString())
                        .sysRoleId(sysRoleId)
                        .supersetRoleId(supersetRoleId)
                        .build();
                mappingRepository.save(mapping);
            }
        }

        log.info("Updated RBAC mapping for sysRoleId={}: {} Superset roles",
                sysRoleId, requestedIds != null ? requestedIds.size() : 0);
    }

    @Override
    @Transactional
    public void createMapping(RbacMappingCreateRequest request) {
        String sysRoleId = request.getSysRoleId();
        List<Integer> supersetRoleIds = request.getSupersetRoleIds();

        // 1. Validate: sysRoleId role exists and is active
        Role role = roleRepository.findById(sysRoleId)
                .orElseThrow(() -> new IllegalArgumentException("System role not found: " + sysRoleId));
        if (!"ACTIVE".equalsIgnoreCase(role.getStatus())) {
            throw new IllegalArgumentException("System role is not ACTIVE: " + sysRoleId);
        }

        // 2. Validate: no existing mapping for this role
        List<BiRbacMapping> existingMappings = mappingRepository.findBySysRoleId(sysRoleId);
        if (!existingMappings.isEmpty()) {
            throw new IllegalArgumentException("Mapping already exists for system role: " + sysRoleId);
        }

        // 3. Validate: all supersetRoleIds are ACTIVE status
        List<BiSupersetRole> supersetRoles = supersetRoleRepository.findBySupersetRoleIdIn(supersetRoleIds);
        Map<Integer, BiSupersetRole> foundMap = supersetRoles.stream()
                .collect(Collectors.toMap(BiSupersetRole::getSupersetRoleId, r -> r));

        for (Integer id : supersetRoleIds) {
            BiSupersetRole supersetRole = foundMap.get(id);
            if (supersetRole == null) {
                throw new IllegalArgumentException("Superset Role not found: " + id);
            }
            if (supersetRole.getStatus() != SupersetRoleStatus.ACTIVE) {
                throw new IllegalArgumentException("Superset Role is not ACTIVE: " + id);
            }
        }

        // 4. Batch insert mapping records
        for (Integer supersetRoleId : supersetRoleIds) {
            BiRbacMapping mapping = BiRbacMapping.builder()
                    .id(UUID.randomUUID().toString())
                    .sysRoleId(sysRoleId)
                    .supersetRoleId(supersetRoleId)
                    .build();
            mappingRepository.save(mapping);
        }

        log.info("Created RBAC mapping for sysRoleId={}: {} Superset roles",
                sysRoleId, supersetRoleIds.size());
    }

    @Override
    @Transactional
    public void deleteMapping(String sysRoleId) {
        mappingRepository.deleteBySysRoleId(sysRoleId);
        log.info("Deleted all RBAC mappings for sysRoleId={}", sysRoleId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleOptionResponse> listUnmappedRoles() {
        // 1. Get all sys_role_ids that already have mapping records
        Set<String> mappedRoleIds = new HashSet<>(mappingRepository.findDistinctSysRoleIds());

        // 2. Get all active roles and exclude those already mapped
        return roleRepository.findAllActive().stream()
                .filter(role -> !mappedRoleIds.contains(role.getId()))
                .map(role -> RoleOptionResponse.builder()
                        .id(role.getId())
                        .name(role.getName())
                        .code(role.getCode())
                        .type(role.getType())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Integer> getEffectiveSupersetRoleIds(List<String> sysRoleIds) {
        return resolveActiveSupersetRoles(sysRoleIds).stream()
                .map(BiSupersetRole::getSupersetRoleId)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getEffectiveSupersetRoleNames(List<String> sysRoleIds) {
        return resolveActiveSupersetRoles(sysRoleIds).stream()
                .map(BiSupersetRole::getName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<BiSupersetRole> getEffectiveSupersetRoles(List<String> sysRoleIds) {
        Map<Integer, BiSupersetRole> distinct = new LinkedHashMap<>();
        for (BiSupersetRole role : resolveActiveSupersetRoles(sysRoleIds)) {
            distinct.putIfAbsent(role.getSupersetRoleId(), role);
        }
        return new ArrayList<>(distinct.values());
    }

    /**
     * 给定系统角色 ID 列表，解析出有效（ACTIVE）的 Superset 角色实体（去重前）。
     * 供 getEffectiveSupersetRoleIds / getEffectiveSupersetRoleNames / getEffectiveSupersetRoles 共用。
     */
    private List<BiSupersetRole> resolveActiveSupersetRoles(List<String> sysRoleIds) {
        if (sysRoleIds == null || sysRoleIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. Query all mappings for the given sysRoleIds
        List<BiRbacMapping> mappings = mappingRepository.findBySysRoleIdIn(sysRoleIds);

        // 2. Collect all mapped superset role IDs
        Set<Integer> supersetRoleIds = mappings.stream()
                .map(BiRbacMapping::getSupersetRoleId)
                .collect(Collectors.toSet());

        if (supersetRoleIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. Filter to only ACTIVE Superset_Roles
        return supersetRoleRepository
                .findBySupersetRoleIdIn(new ArrayList<>(supersetRoleIds)).stream()
                .filter(r -> r.getStatus() == SupersetRoleStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    private SupersetRoleResponse toSupersetRoleResponse(BiSupersetRole entity) {
        return SupersetRoleResponse.builder()
                .id(entity.getId())
                .supersetRoleId(entity.getSupersetRoleId())
                .name(entity.getName())
                .status(entity.getStatus())
                .lastSyncedAt(entity.getLastSyncedAt())
                .build();
    }
}
