package com.admin.bi.component;

import com.admin.bi.dto.response.DashboardRegistryResponse;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.entity.BiSupersetRole;
import com.admin.bi.repository.BiSupersetRoleRepository;
import com.admin.bi.support.SupersetRoleIdCsv;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds {@link DashboardRegistryResponse} from registry entities, resolving the synced Superset
 * role IDs to names with one lookup per batch. Shared by the registry service and the list query.
 */
@Component
@RequiredArgsConstructor
public class BiDashboardRegistryResponseAssembler {

    private final BiSupersetRoleRepository supersetRoleRepository;

    public DashboardRegistryResponse toResponse(BiDashboardRegistry entity) {
        return toResponses(List.of(entity)).get(0);
    }

    public List<DashboardRegistryResponse> toResponses(Collection<BiDashboardRegistry> entities) {
        Set<Integer> allRoleIds = new HashSet<>();
        for (BiDashboardRegistry entity : entities) {
            allRoleIds.addAll(SupersetRoleIdCsv.parse(entity.getSupersetRoleIds()));
        }
        Map<Integer, String> namesById = allRoleIds.isEmpty()
                ? Map.of()
                : supersetRoleRepository.findBySupersetRoleIdIn(new ArrayList<>(allRoleIds)).stream()
                        .collect(Collectors.toMap(BiSupersetRole::getSupersetRoleId, BiSupersetRole::getName,
                                (a, b) -> a));

        List<DashboardRegistryResponse> out = new ArrayList<>(entities.size());
        for (BiDashboardRegistry entity : entities) {
            List<Integer> roleIds = SupersetRoleIdCsv.parse(entity.getSupersetRoleIds());
            List<String> roleNames = roleIds.stream()
                    .map(id -> namesById.getOrDefault(id, "#" + id))
                    .collect(Collectors.toList());
            out.add(DashboardRegistryResponse.builder()
                    .id(entity.getId())
                    .dashboardTitle(entity.getDashboardTitle())
                    .description(entity.getDescription())
                    .embedId(entity.getEmbedId())
                    .supersetDashboardUuid(entity.getSupersetDashboardUuid())
                    .supersetDashboardId(entity.getSupersetDashboardId())
                    .tags(entity.getTags())
                    .isDefaultLanding(entity.getIsDefaultLanding())
                    .supersetRoleIds(roleIds)
                    .supersetRoleNames(roleNames)
                    .status(entity.getStatus())
                    .lastSyncedAt(entity.getLastSyncedAt())
                    .createdAt(entity.getCreatedAt())
                    .updatedAt(entity.getUpdatedAt())
                    .build());
        }
        return out;
    }
}
