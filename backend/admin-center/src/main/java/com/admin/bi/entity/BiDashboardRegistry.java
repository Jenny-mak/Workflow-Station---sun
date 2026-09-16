package com.admin.bi.entity;

import com.admin.bi.enums.DashboardStatus;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dashboard 本地注册表实体
 * 存储从 Superset 同步的 Dashboard 元数据及本地扩展字段
 */
@Entity
@Table(name = "bi_dashboard_registry", indexes = {
        @Index(name = "idx_bi_dashboard_status", columnList = "status"),
        @Index(name = "idx_bi_dashboard_superset_id", columnList = "superset_dashboard_id")
})
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class BiDashboardRegistry {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "dashboard_title", nullable = false, length = 500)
    private String dashboardTitle;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "embed_id", nullable = false)
    private UUID embedId;

    @Column(name = "superset_dashboard_uuid", nullable = false, unique = true)
    private UUID supersetDashboardUuid;

    @Column(name = "superset_dashboard_id", nullable = false, unique = true)
    private Integer supersetDashboardId;

    @Column(name = "tags", length = 500)
    private String tags;

    /**
     * Superset role IDs granted on this dashboard (synced from Superset {@code dashboard_roles}),
     * stored as a sorted CSV; null/blank = no role restriction. See {@link com.admin.bi.support.SupersetRoleIdCsv}.
     */
    @Column(name = "superset_role_ids", columnDefinition = "TEXT")
    private String supersetRoleIds;

    @Column(name = "is_default_landing", nullable = false)
    @Builder.Default
    private Boolean isDefaultLanding = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private DashboardStatus status = DashboardStatus.ACTIVE;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by", length = 64, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", length = 64)
    private String updatedBy;
}
