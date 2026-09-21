package com.portal.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 委托审计记录实体
 */
@Entity
@Table(name = "up_delegation_audit")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DelegationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delegator_id", nullable = false, length = 64)
    private String delegatorId;

    @Column(name = "delegate_id", nullable = false, length = 64)
    private String delegateId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType;

    @Column(name = "operation_result", length = 50)
    private String operationResult;

    @Column(name = "operation_detail", columnDefinition = "TEXT")
    private String operationDetail;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** Resolved at list query time; stored value remains {@link #delegatorId}. */
    @Transient
    private String delegatorDisplayName;

    /**
     * Resolved at list query time for user ids only. BU+Role audit tokens
     * ({@code buCode/roleCode}) stay in {@link #delegateId} without a lookup.
     */
    @Transient
    private String delegateDisplayName;
}
