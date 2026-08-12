package com.portal.repository;

import com.portal.entity.DelegationAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 委托审计记录Repository
 */
@Repository
public interface DelegationAuditRepository extends JpaRepository<DelegationAudit, Long>, JpaSpecificationExecutor<DelegationAudit> {

    List<DelegationAudit> findByDelegatorId(String delegatorId);

    List<DelegationAudit> findByDelegateId(String delegateId);

    List<DelegationAudit> findByTaskId(String taskId);

    Page<DelegationAudit> findByDelegatorIdOrDelegateIdOrderByCreatedAtDesc(
            String delegatorId, String delegateId, Pageable pageable);

    List<DelegationAudit> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    Page<DelegationAudit> findByDelegateIdAndOperationTypeOrderByCreatedAtDesc(
            String delegateId, String operationType, Pageable pageable);

    List<DelegationAudit> findByDelegatorIdAndOperationTypeAndTaskIdIn(
            String delegatorId, String operationType, Collection<String> taskIds);
}
