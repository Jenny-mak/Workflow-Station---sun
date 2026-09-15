package com.admin.bi.service.impl;

import com.admin.bi.component.DashboardRoleGate;
import com.admin.bi.dto.request.DashboardAssignmentCreateRequest;
import com.admin.bi.dto.response.DashboardAssignmentResponse;
import com.admin.bi.dto.response.UserDashboardResponse;
import com.admin.bi.entity.BiDashboardAssignment;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.enums.AssignmentTargetType;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.enums.LayoutMode;
import com.admin.bi.repository.BiDashboardAssignmentRepository;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.service.BiDashboardAssignmentService;
import com.admin.exception.AssignmentTargetNotFoundException;
import com.admin.exception.DashboardInactiveException;
import com.admin.exception.DashboardNotFoundException;
import com.admin.exception.DuplicateAssignmentException;
import com.admin.repository.BusinessUnitRepository;
import com.admin.repository.RoleRepository;
import com.admin.repository.UserRepository;
import com.admin.repository.UserRoleRepository;
import com.admin.service.UserBusinessUnitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dashboard 分配 Service 实现
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BiDashboardAssignmentServiceImpl implements BiDashboardAssignmentService {

    private final BiDashboardAssignmentRepository assignmentRepository;
    private final BiDashboardRegistryRepository registryRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BusinessUnitRepository businessUnitRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserBusinessUnitService userBusinessUnitService;
    private final DashboardRoleGate dashboardRoleGate;

    @Override
    @Transactional
    public DashboardAssignmentResponse createAssignment(DashboardAssignmentCreateRequest request) {
        // 1. Validate Dashboard exists and is ACTIVE
        BiDashboardRegistry dashboard = registryRepository.findById(request.getDashboardId())
                .orElseThrow(() -> new DashboardNotFoundException(request.getDashboardId()));

        if (dashboard.getStatus() != DashboardStatus.ACTIVE) {
            throw new DashboardInactiveException(request.getDashboardId());
        }

        // 2. Validate Target exists
        validateTargetExists(request.getTargetType(), request.getTargetId());

        // 3. Check uniqueness
        if (assignmentRepository.existsByDashboardIdAndTargetTypeAndTargetId(
                request.getDashboardId(), request.getTargetType(), request.getTargetId())) {
            throw new DuplicateAssignmentException(
                    request.getDashboardId(),
                    request.getTargetType().name(),
                    request.getTargetId());
        }

        // 4. Create assignment
        BiDashboardAssignment assignment = BiDashboardAssignment.builder()
                .id(UUID.randomUUID().toString())
                .dashboardId(request.getDashboardId())
                .targetType(request.getTargetType())
                .targetId(request.getTargetId())
                .layoutMode(request.getLayoutMode() != null ? request.getLayoutMode() : LayoutMode.SINGLE)
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : false)
                .build();

        BiDashboardAssignment saved = assignmentRepository.save(assignment);
        return toResponse(saved, dashboard.getDashboardTitle());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DashboardAssignmentResponse> listAssignments(
            AssignmentTargetType targetType, String dashboardTitle, Pageable pageable) {
        return assignmentRepository.findByFilters(targetType, dashboardTitle, pageable)
                .map(assignment -> {
                    String title = registryRepository.findById(assignment.getDashboardId())
                            .map(BiDashboardRegistry::getDashboardTitle)
                            .orElse(null);
                    return toResponse(assignment, title);
                });
    }

    @Override
    @Transactional
    public DashboardAssignmentResponse updateAssignment(String id, DashboardAssignmentCreateRequest request) {
        BiDashboardAssignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new DashboardNotFoundException(id));

        if (request.getLayoutMode() != null) {
            assignment.setLayoutMode(request.getLayoutMode());
        }
        if (request.getDisplayOrder() != null) {
            assignment.setDisplayOrder(request.getDisplayOrder());
        }
        if (request.getIsDefault() != null) {
            assignment.setIsDefault(request.getIsDefault());
        }

        BiDashboardAssignment saved = assignmentRepository.save(assignment);
        String title = registryRepository.findById(saved.getDashboardId())
                .map(BiDashboardRegistry::getDashboardTitle)
                .orElse(null);
        return toResponse(saved, title);
    }

    @Override
    @Transactional
    public void deleteAssignment(String id) {
        BiDashboardAssignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new DashboardNotFoundException(id));
        assignmentRepository.delete(assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDashboardResponse> getUserDashboards(String userId, String activeBusinessUnitId) {
        // 1. Query USER dimension assignments
        List<BiDashboardAssignment> userAssignments =
                assignmentRepository.findByTargetTypeAndTargetId(AssignmentTargetType.USER, userId);

        // 2. Get user's role IDs and query ROLE dimension assignments
        List<String> roleIds = userRoleRepository.findAllRoleIdsByUserId(userId);
        List<BiDashboardAssignment> roleAssignments = roleIds.isEmpty()
                ? Collections.emptyList()
                : assignmentRepository.findByTargetTypeAndTargetIdIn(AssignmentTargetType.ROLE, roleIds);

        // 3. BU dimension: when an active BU context is provided, restrict to that BU only;
        //    otherwise fall back to all BUs the user belongs to.
        List<String> buIds;
        if (activeBusinessUnitId != null && !activeBusinessUnitId.isBlank()) {
            buIds = List.of(activeBusinessUnitId);
        } else {
            buIds = userBusinessUnitService.getUserBusinessUnitIds(userId);
        }
        List<BiDashboardAssignment> buAssignments = buIds.isEmpty()
                ? Collections.emptyList()
                : assignmentRepository.findByTargetTypeAndTargetIdIn(AssignmentTargetType.BUSINESS_UNIT, buIds);

        // 4. Merge and deduplicate with priority USER > ROLE > BU
        Map<String, BiDashboardAssignment> mergedMap = new LinkedHashMap<>();

        // Add BU assignments first (lowest priority)
        for (BiDashboardAssignment a : buAssignments) {
            mergedMap.put(a.getDashboardId(), a);
        }
        // Override with ROLE assignments (medium priority)
        for (BiDashboardAssignment a : roleAssignments) {
            mergedMap.put(a.getDashboardId(), a);
        }
        // Override with USER assignments (highest priority)
        for (BiDashboardAssignment a : userAssignments) {
            mergedMap.put(a.getDashboardId(), a);
        }

        // 5. Filter to only ACTIVE dashboards the user's mapped Superset roles may see, sort by displayOrder
        DashboardRoleGate.Check roleGate = dashboardRoleGate.forSysRoles(userId, roleIds);
        return mergedMap.values().stream()
                .map(assignment -> {
                    Optional<BiDashboardRegistry> dashOpt = registryRepository.findById(assignment.getDashboardId());
                    if (dashOpt.isEmpty() || dashOpt.get().getStatus() != DashboardStatus.ACTIVE) {
                        return null;
                    }
                    BiDashboardRegistry dash = dashOpt.get();
                    if (!roleGate.allows(dash)) {
                        return null;
                    }
                    return UserDashboardResponse.builder()
                            .dashboardId(dash.getId())
                            .dashboardTitle(dash.getDashboardTitle())
                            .description(dash.getDescription())
                            .embedId(dash.getEmbedId())
                            .layoutMode(assignment.getLayoutMode())
                            .displayOrder(assignment.getDisplayOrder())
                            .isDefault(assignment.getIsDefault())
                            .build();
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(UserDashboardResponse::getDisplayOrder))
                .collect(Collectors.toList());
    }

    /**
     * Validate that the target exists in the corresponding dimension
     */
    private void validateTargetExists(AssignmentTargetType targetType, String targetId) {
        boolean exists = switch (targetType) {
            case USER -> userRepository.existsById(targetId);
            case ROLE -> roleRepository.existsById(targetId);
            case BUSINESS_UNIT -> businessUnitRepository.existsById(targetId);
        };
        if (!exists) {
            throw new AssignmentTargetNotFoundException(targetType.name(), targetId);
        }
    }

    private DashboardAssignmentResponse toResponse(BiDashboardAssignment entity, String dashboardTitle) {
        return DashboardAssignmentResponse.builder()
                .id(entity.getId())
                .dashboardId(entity.getDashboardId())
                .dashboardTitle(dashboardTitle)
                .targetType(entity.getTargetType())
                .targetId(entity.getTargetId())
                .targetName(resolveTargetName(entity.getTargetType(), entity.getTargetId()))
                .layoutMode(entity.getLayoutMode())
                .displayOrder(entity.getDisplayOrder())
                .isDefault(entity.getIsDefault())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Resolve the display name for an assignment target based on its type and ID.
     */
    private String resolveTargetName(AssignmentTargetType targetType, String targetId) {
        try {
            return switch (targetType) {
                case USER -> userRepository.findById(targetId)
                        .map(user -> user.getDisplayName() != null ? user.getDisplayName() : user.getUsername())
                        .orElse(null);
                case ROLE -> roleRepository.findById(targetId)
                        .map(role -> role.getName())
                        .orElse(null);
                case BUSINESS_UNIT -> businessUnitRepository.findById(targetId)
                        .map(bu -> bu.getName())
                        .orElse(null);
            };
        } catch (Exception e) {
            log.warn("Failed to resolve target name for type={}, id={}: {}", targetType, targetId, e.getMessage());
            return null;
        }
    }
}
