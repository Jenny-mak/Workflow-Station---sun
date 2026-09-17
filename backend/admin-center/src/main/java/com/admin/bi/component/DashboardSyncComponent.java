package com.admin.bi.component;

import com.admin.bi.config.BiProperties;
import com.admin.bi.dto.response.SyncResultResponse;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.support.SupersetRoleIdCsv;
import com.admin.exception.SupersetSyncException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dashboard 同步组件
 * 从 Superset 数据库同步已发布且启用嵌入的 Dashboard 元数据到本地注册表
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardSyncComponent {

    private final JdbcTemplate jdbcTemplate;
    private final BiDashboardRegistryRepository registryRepository;
    private final BiProperties biProperties;

    private String supersetDashboardSyncSql() {
        String schema = biProperties.getSuperset().resolveDbSchemaForSql();
        return "SELECT d.id as superset_dashboard_id, d.dashboard_title, d.description, "
                + "d.uuid as superset_dashboard_uuid, e.uuid as embed_id "
                + "FROM " + schema + ".dashboards d "
                + "INNER JOIN " + schema + ".embedded_dashboards e ON d.id = e.dashboard_id "
                + "WHERE d.published = true";
    }

    /**
     * Superset dashboard-level RBAC (feature flag DASHBOARD_RBAC): which Superset roles were granted
     * on each dashboard. Drives the embed-side role filter in BiDashboardAssignmentService.
     */
    private String supersetDashboardRolesSql() {
        String schema = biProperties.getSuperset().resolveDbSchemaForSql();
        return "SELECT dr.dashboard_id, dr.role_id FROM " + schema + ".dashboard_roles dr";
    }

    /**
     * 定时同步入口
     */
    @Scheduled(cron = "${bi.sync.cron:0 0 */6 * * ?}")
    @SchedulerLock(name = "DashboardSync_scheduledSync", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void scheduledSync() {
        if (!biProperties.getSync().isEnabled()) {
            log.debug("Dashboard scheduled sync is disabled, skipping");
            return;
        }
        log.info("Starting scheduled Dashboard sync");
        try {
            SyncResultResponse result = executeSyncOperation();
            log.info("Scheduled Dashboard sync completed: created={}, updated={}, autoInactivated={}",
                    result.getCreated(), result.getUpdated(), result.getAutoInactivated());
        } catch (SupersetSyncException e) {
            log.error("Scheduled Dashboard sync failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行同步操作（可由定时任务或手动触发调用）
     *
     * @return 同步结果摘要
     * @throws SupersetSyncException 当 Superset 数据库查询失败时
     */
    @Transactional
    public SyncResultResponse executeSyncOperation() {
        LocalDateTime syncTime = LocalDateTime.now();

        // 1. 查询 Superset 数据库中符合条件的 Dashboard
        List<Map<String, Object>> supersetDashboards;
        try {
            supersetDashboards = jdbcTemplate.queryForList(supersetDashboardSyncSql());
        } catch (Exception e) {
            log.error("Failed to query Superset database: {}", e.getMessage(), e);
            String msg = e.getMessage() != null && e.getMessage().contains("does not exist")
                    ? "Superset tables (dashboards, embedded_dashboards) not found. Ensure Admin Center uses the same database as Superset "
                    + "(e.g. set SPRING_DATASOURCE_URL) and SUPERSET_DB_SCHEMA matches Superset's metadata schema (default: public)."
                    : "Failed to query Superset database: " + e.getMessage();
            throw new SupersetSyncException(msg, e);
        }
        Map<Integer, Set<Integer>> rolesByDashboard = querySupersetDashboardRoles();

        // 2. 查询所有本地注册记录，按 supersetDashboardId 建立索引
        List<BiDashboardRegistry> localRecords = registryRepository.findAll();
        Map<Integer, BiDashboardRegistry> localMap = localRecords.stream()
                .collect(Collectors.toMap(BiDashboardRegistry::getSupersetDashboardId, Function.identity()));

        // 3. 收集 Superset 端存在的 Dashboard ID 集合
        Set<Integer> supersetDashboardIds = new HashSet<>();

        int created = 0;
        int updated = 0;

        // 4. 遍历 Superset 数据，执行新增/更新/恢复逻辑
        for (Map<String, Object> row : supersetDashboards) {
            Integer supersetDashboardId = (Integer) row.get("superset_dashboard_id");
            String dashboardTitle = (String) row.get("dashboard_title");
            String description = (String) row.get("description");
            UUID supersetDashboardUuid = (UUID) row.get("superset_dashboard_uuid");
            UUID embedId = (UUID) row.get("embed_id");
            String supersetRoleIds = SupersetRoleIdCsv.format(rolesByDashboard.get(supersetDashboardId));

            supersetDashboardIds.add(supersetDashboardId);

            BiDashboardRegistry existing = localMap.get(supersetDashboardId);

            if (existing == null) {
                // 新增：创建新注册记录，状态为 ACTIVE
                BiDashboardRegistry newRecord = BiDashboardRegistry.builder()
                        .id(UUID.randomUUID().toString())
                        .dashboardTitle(dashboardTitle)
                        .description(description)
                        .embedId(embedId)
                        .supersetDashboardUuid(supersetDashboardUuid)
                        .supersetDashboardId(supersetDashboardId)
                        .supersetRoleIds(supersetRoleIds)
                        .status(DashboardStatus.ACTIVE)
                        .isDefaultLanding(false)
                        .lastSyncedAt(syncTime)
                        .build();
                registryRepository.save(newRecord);
                created++;
                log.debug("Created new Dashboard registry: supersetId={}, title={}", supersetDashboardId, dashboardTitle);
            } else {
                // 已存在：根据状态处理
                if (existing.getStatus() == DashboardStatus.MANUAL_INACTIVE) {
                    // MANUAL_INACTIVE 保持不变，仅更新同步时间和 Superset 来源字段
                    boolean fieldsChanged = updateSupersetFields(existing, dashboardTitle, description, embedId, supersetRoleIds);
                    existing.setLastSyncedAt(syncTime);
                    if (fieldsChanged) {
                        registryRepository.save(existing);
                        updated++;
                        log.debug("Updated MANUAL_INACTIVE Dashboard fields: supersetId={}", supersetDashboardId);
                    } else {
                        registryRepository.save(existing);
                    }
                } else if (existing.getStatus() == DashboardStatus.AUTO_INACTIVE) {
                    // AUTO_INACTIVE 恢复为 ACTIVE
                    updateSupersetFields(existing, dashboardTitle, description, embedId, supersetRoleIds);
                    existing.setStatus(DashboardStatus.ACTIVE);
                    existing.setLastSyncedAt(syncTime);
                    registryRepository.save(existing);
                    updated++;
                    log.debug("Restored AUTO_INACTIVE Dashboard to ACTIVE: supersetId={}", supersetDashboardId);
                } else {
                    // ACTIVE：检查字段是否变化
                    boolean fieldsChanged = updateSupersetFields(existing, dashboardTitle, description, embedId, supersetRoleIds);
                    existing.setLastSyncedAt(syncTime);
                    if (fieldsChanged) {
                        registryRepository.save(existing);
                        updated++;
                        log.debug("Updated ACTIVE Dashboard fields: supersetId={}", supersetDashboardId);
                    } else {
                        registryRepository.save(existing);
                    }
                }
            }
        }

        // 5. 处理本地多余记录：不在 Superset 结果中的设为 AUTO_INACTIVE
        int autoInactivated = 0;
        for (BiDashboardRegistry localRecord : localRecords) {
            if (!supersetDashboardIds.contains(localRecord.getSupersetDashboardId())) {
                if (localRecord.getStatus() != DashboardStatus.MANUAL_INACTIVE
                        && localRecord.getStatus() != DashboardStatus.AUTO_INACTIVE) {
                    localRecord.setStatus(DashboardStatus.AUTO_INACTIVE);
                    localRecord.setLastSyncedAt(syncTime);
                    registryRepository.save(localRecord);
                    autoInactivated++;
                    log.debug("Set Dashboard to AUTO_INACTIVE: supersetId={}", localRecord.getSupersetDashboardId());
                }
            }
        }

        log.info("Dashboard sync completed: created={}, updated={}, autoInactivated={}", created, updated, autoInactivated);

        return SyncResultResponse.builder()
                .created(created)
                .updated(updated)
                .autoInactivated(autoInactivated)
                .syncedAt(syncTime)
                .build();
    }

    /**
     * 更新 Superset 来源字段，保留本地扩展字段不变
     *
     * @return true 如果有字段发生变化
     */
    private boolean updateSupersetFields(BiDashboardRegistry existing, String title, String description,
                                         UUID embedId, String supersetRoleIds) {
        boolean changed = false;

        if (!Objects.equals(existing.getDashboardTitle(), title)) {
            existing.setDashboardTitle(title);
            changed = true;
        }
        if (!Objects.equals(existing.getDescription(), description)) {
            existing.setDescription(description);
            changed = true;
        }
        if (!Objects.equals(existing.getEmbedId(), embedId)) {
            existing.setEmbedId(embedId);
            changed = true;
        }
        if (!Objects.equals(existing.getSupersetRoleIds(), supersetRoleIds)) {
            existing.setSupersetRoleIds(supersetRoleIds);
            changed = true;
        }

        return changed;
    }

    /**
     * Read Superset {@code dashboard_roles} into dashboardId -> roleIds. A missing table is a hard
     * sync failure like the dashboards query: silently treating it as "no restrictions" would open
     * every role-restricted dashboard to every assigned user.
     */
    private Map<Integer, Set<Integer>> querySupersetDashboardRoles() {
        List<int[]> pairs;
        try {
            pairs = jdbcTemplate.query(supersetDashboardRolesSql(),
                    (rs, rowNum) -> new int[]{rs.getInt("dashboard_id"), rs.getInt("role_id")});
        } catch (Exception e) {
            log.error("Failed to query Superset dashboard_roles: {}", e.getMessage(), e);
            throw new SupersetSyncException("Failed to query Superset dashboard_roles: " + e.getMessage(), e);
        }
        Map<Integer, Set<Integer>> rolesByDashboard = new HashMap<>();
        for (int[] pair : pairs) {
            rolesByDashboard.computeIfAbsent(pair[0], k -> new HashSet<>()).add(pair[1]);
        }
        return rolesByDashboard;
    }
}
