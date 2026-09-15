package com.admin.bi.service.impl;

import com.admin.bi.component.DashboardSyncComponent;
import com.admin.bi.dto.request.DashboardRegistryUpdateRequest;
import com.admin.bi.dto.response.DashboardRegistryResponse;
import com.admin.bi.dto.response.SyncResultResponse;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.repository.BiDashboardAssignmentRepository;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.repository.BiDataViewAssignmentRepository;
import com.admin.bi.service.BiDashboardRegistryService;
import com.admin.exception.DashboardHasAssignmentsException;
import com.admin.exception.DashboardNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard 注册表 Service 实现
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BiDashboardRegistryServiceImpl implements BiDashboardRegistryService {

    private final BiDashboardRegistryRepository registryRepository;
    private final BiDashboardAssignmentRepository assignmentRepository;
    private final DashboardSyncComponent dashboardSyncComponent;

    /** Kept outside the constructor so existing service-level property tests remain source-compatible. */
    @Autowired
    private BiDataViewAssignmentRepository dataViewAssignmentRepository;

    @Override
    public SyncResultResponse syncDashboards() {
        log.info("Manual Dashboard sync triggered");
        return dashboardSyncComponent.executeSyncOperation();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DashboardRegistryResponse> listDashboards(String title, String tags, DashboardStatus status, Pageable pageable) {
        return registryRepository.findByFilters(title, tags, status, pageable)
                .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardRegistryResponse getDashboard(String id) {
        BiDashboardRegistry entity = registryRepository.findById(id)
                .orElseThrow(() -> new DashboardNotFoundException(id));
        return toResponse(entity);
    }

    @Override
    @Transactional
    public DashboardRegistryResponse updateDashboard(String id, DashboardRegistryUpdateRequest request) {
        BiDashboardRegistry entity = registryRepository.findById(id)
                .orElseThrow(() -> new DashboardNotFoundException(id));

        if (request.getTags() != null) {
            entity.setTags(request.getTags());
        }
        if (request.getIsDefaultLanding() != null) {
            entity.setIsDefaultLanding(request.getIsDefaultLanding());
        }

        BiDashboardRegistry saved = registryRepository.save(entity);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public DashboardRegistryResponse enableDashboard(String id) {
        BiDashboardRegistry entity = registryRepository.findById(id)
                .orElseThrow(() -> new DashboardNotFoundException(id));

        entity.setStatus(DashboardStatus.ACTIVE);
        BiDashboardRegistry saved = registryRepository.save(entity);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public DashboardRegistryResponse disableDashboard(String id) {
        BiDashboardRegistry entity = registryRepository.findById(id)
                .orElseThrow(() -> new DashboardNotFoundException(id));

        entity.setStatus(DashboardStatus.MANUAL_INACTIVE);
        BiDashboardRegistry saved = registryRepository.save(entity);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteDashboard(String id) {
        BiDashboardRegistry entity = registryRepository.findById(id)
                .orElseThrow(() -> new DashboardNotFoundException(id));

        long assignmentCount = assignmentRepository.countByDashboardId(id)
                + (dataViewAssignmentRepository != null
                ? dataViewAssignmentRepository.countByDashboardId(id) : 0L);
        if (assignmentCount > 0) {
            throw new DashboardHasAssignmentsException(id);
        }

        registryRepository.delete(entity);
    }

    private DashboardRegistryResponse toResponse(BiDashboardRegistry entity) {
        return DashboardRegistryResponse.builder()
                .id(entity.getId())
                .dashboardTitle(entity.getDashboardTitle())
                .description(entity.getDescription())
                .embedId(entity.getEmbedId())
                .supersetDashboardUuid(entity.getSupersetDashboardUuid())
                .supersetDashboardId(entity.getSupersetDashboardId())
                .tags(entity.getTags())
                .isDefaultLanding(entity.getIsDefaultLanding())
                .status(entity.getStatus())
                .lastSyncedAt(entity.getLastSyncedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
