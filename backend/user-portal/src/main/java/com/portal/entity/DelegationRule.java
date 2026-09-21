package com.portal.entity;

import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
import com.portal.enums.DelegateTargetType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 委托规则实体
 */
@Entity
@Table(name = "up_delegation_rule")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DelegationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delegator_id", nullable = false, length = 64)
    private String delegatorId;

    @Column(name = "delegate_id", length = 64)
    private String delegateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "delegate_target_type", length = 20)
    private DelegateTargetType delegateTargetType;

    @Column(name = "delegate_bu_code", length = 64)
    private String delegateBuCode;

    @Column(name = "delegate_role_code", length = 64)
    private String delegateRoleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "delegation_type", nullable = false, length = 20)
    private DelegationType delegationType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "process_types", columnDefinition = "jsonb")
    private List<String> processTypes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "priority_filter", columnDefinition = "jsonb")
    private List<String> priorityFilter;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private DelegationStatus status = DelegationStatus.ACTIVE;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Version field for optimistic locking.
     * JPA will automatically increment this on each update and check for concurrent modifications.
     */
    @jakarta.persistence.Version
    @Column(name = "lock_version")
    private Long lockVersion;

    /** Resolved at list query time; stored value remains {@link #delegateId}. */
    @Transient
    private String delegateDisplayName;

    public boolean isUserTarget() {
        return delegateTargetType == null || delegateTargetType == DelegateTargetType.USER;
    }

    public boolean isBuRoleTarget() {
        return delegateTargetType == DelegateTargetType.BU_ROLE;
    }
}
