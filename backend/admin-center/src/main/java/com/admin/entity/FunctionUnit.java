package com.admin.entity;

import com.admin.enums.FunctionUnitStatus;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Admin Center FunctionUnit entity — a package imported from Developer Workstation.
 */
@Entity
@Table(name = "sys_function_units")
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class FunctionUnit {
    
    @Id
    @Column(length = 64)
    private String id;
    
    @Column(name = "name", nullable = false, length = 100)
    private String name;
    
    @Column(name = "code", nullable = false, length = 50)
    private String code;
    
    @Column(name = "version", nullable = false, length = 20)
    private String version;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "package_path", length = 500)
    private String packagePath;
    
    @Column(name = "package_size")
    private Long packageSize;
    
    @Column(name = "checksum", length = 64)
    private String checksum;
    
    @Column(name = "digital_signature", columnDefinition = "TEXT")
    private String digitalSignature;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private FunctionUnitStatus status = FunctionUnitStatus.DRAFT;
    
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;
    
    @Column(name = "imported_at")
    private Instant importedAt;
    
    @Column(name = "imported_by", length = 64)
    private String importedBy;
    
    /**
     * When this function unit was first deployed into the platform (persisted milestone).
     */
    @Column(name = "deployed_at", nullable = false)
    @Builder.Default
    private Instant deployedAt = Instant.now();
    
    @Column(name = "validated_at")
    private Instant validatedAt;
    
    @Column(name = "validated_by", length = 64)
    private String validatedBy;
    
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    
    @CreatedBy
    @Column(name = "created_by", length = 64, updatable = false)
    private String createdBy;
    
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    @LastModifiedBy
    @Column(name = "updated_by", length = 64)
    private String updatedBy;
    
    /**
     * Whether BPMN/process definitions were deployed to the Flowable engine.
     */
    @Column(name = "process_deployed")
    @Builder.Default
    private Boolean processDeployed = false;
    
    /**
     * Number of process definitions deployed from this package.
     */
    @Column(name = "process_deployment_count")
    @Builder.Default
    private Integer processDeploymentCount = 0;
    
    @Column(name = "icon_svg", columnDefinition = "TEXT")
    private String iconSvg;

    
    @OneToMany(mappedBy = "functionUnit", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<FunctionUnitDeployment> deployments = new HashSet<>();
    
    @OneToMany(mappedBy = "functionUnit", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<FunctionUnitDependency> dependencies = new HashSet<>();
    
    @OneToMany(mappedBy = "functionUnit", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<FunctionUnitContent> contents = new HashSet<>();
    
    /**
     * {@code true} if the unit may be deployed (validated or already deployed).
     */
    public boolean isDeployable() {
        return status == FunctionUnitStatus.VALIDATED
                || status == FunctionUnitStatus.DEPLOYED;
    }

    /**
     * {@code true} if successful validation may transition status to VALIDATED (draft only).
     * Content checks on VALIDATED/DEPLOYED units do not use this flag.
     */
    public boolean isValidatable() {
        return status == FunctionUnitStatus.DRAFT;
    }
    
    /**
     * {@code true} if status is DEPRECATED.
     */
    public boolean isDeprecated() {
        return status == FunctionUnitStatus.DEPRECATED;
    }
    
    /**
     * {@code true} if the {@code enabled} flag is set.
     */
    public boolean isEnabled() {
        return enabled != null && enabled;
    }
    
    /**
     * {@code true} if deployed and enabled (available to end users).
     */
    public boolean isAvailableToUsers() {
        return status == FunctionUnitStatus.DEPLOYED && isEnabled();
    }
    
    /**
     * Full version key: {@code code:version}.
     */
    public String getFullVersionId() {
        return code + ":" + version;
    }
    
    /**
     * Reset to draft and disable (after import or restore).
     */
    public void markAsDraft() {
        this.status = FunctionUnitStatus.DRAFT;
        this.enabled = false;
    }

    /**
     * Mark as archived and disable.
     */
    public void markAsArchived() {
        this.status = FunctionUnitStatus.ARCHIVED;
        this.enabled = false;
    }

    /**
     * Mark as validated; records validator and timestamp.
     */
    public void markAsValidated(String validatorId) {
        this.status = FunctionUnitStatus.VALIDATED;
        this.validatedAt = Instant.now();
        this.validatedBy = validatorId;
    }
    
    /**
     * Mark status as deployed.
     */
    public void markAsDeployed() {
        this.status = FunctionUnitStatus.DEPLOYED;
    }
    
    /**
     * Mark as deprecated.
     */
    public void markAsDeprecated() {
        this.status = FunctionUnitStatus.DEPRECATED;
    }
    
    /**
     * Attach a deployment record (owns both sides).
     */
    public void addDeployment(FunctionUnitDeployment deployment) {
        if (deployments == null) {
            deployments = new HashSet<>();
        }
        deployment.setFunctionUnit(this);
        deployments.add(deployment);
    }
    
    /**
     * Attach a dependency row.
     */
    public void addDependency(FunctionUnitDependency dependency) {
        if (dependencies == null) {
            dependencies = new HashSet<>();
        }
        dependency.setFunctionUnit(this);
        dependencies.add(dependency);
    }
    
    /**
     * Attach a package content artifact row.
     */
    public void addContent(FunctionUnitContent content) {
        if (contents == null) {
            contents = new HashSet<>();
        }
        content.setFunctionUnit(this);
        contents.add(content);
    }
}
