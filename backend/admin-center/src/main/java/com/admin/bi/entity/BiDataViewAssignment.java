package com.admin.bi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/** Dashboard binding for a User Portal Data -> Views table. */
@Entity
@Table(name = "bi_data_view_assignment",
        uniqueConstraints = @UniqueConstraint(columnNames = {"dashboard_id", "table_id"}),
        indexes = {
                @Index(name = "idx_bi_data_view_assignment_fu", columnList = "function_unit_id"),
                @Index(name = "idx_bi_data_view_assignment_table", columnList = "table_id"),
                @Index(name = "idx_bi_data_view_assignment_dashboard", columnList = "dashboard_id")
        })
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class BiDataViewAssignment {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "dashboard_id", nullable = false, length = 64)
    private String dashboardId;

    @Column(name = "function_unit_id", nullable = false)
    private Long functionUnitId;

    @Column(name = "table_id", nullable = false)
    private Long tableId;

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
