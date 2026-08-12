package com.portal.repository;

import com.portal.entity.DelegationRule;
import com.portal.enums.DelegationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 委托规则Repository
 */
@Repository
public interface DelegationRuleRepository extends JpaRepository<DelegationRule, Long>, JpaSpecificationExecutor<DelegationRule> {

    List<DelegationRule> findByDelegatorId(String delegatorId);

    Page<DelegationRule> findByDelegatorId(String delegatorId, Pageable pageable);

    List<DelegationRule> findByDelegateId(String delegateId);

    List<DelegationRule> findByDelegatorIdAndStatus(String delegatorId, DelegationStatus status);

    List<DelegationRule> findByDelegateIdAndStatus(String delegateId, DelegationStatus status);

    @Query("SELECT d FROM DelegationRule d WHERE d.delegatorId = :delegatorId " +
           "AND d.status = 'ACTIVE' " +
           "AND (d.startTime IS NULL OR d.startTime <= :now) " +
           "AND (d.endTime IS NULL OR d.endTime >= :now)")
    List<DelegationRule> findActiveDelegationRules(
            @Param("delegatorId") String delegatorId,
            @Param("now") LocalDateTime now);

    @Query("SELECT d FROM DelegationRule d WHERE d.delegateId = :delegateId " +
           "AND d.status = 'ACTIVE' " +
           "AND (d.startTime IS NULL OR d.startTime <= :now) " +
           "AND (d.endTime IS NULL OR d.endTime >= :now)")
    List<DelegationRule> findActiveDelegationsForDelegate(
            @Param("delegateId") String delegateId,
            @Param("now") LocalDateTime now);
}
