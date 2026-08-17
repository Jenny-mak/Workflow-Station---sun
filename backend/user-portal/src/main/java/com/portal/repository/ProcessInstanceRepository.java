package com.portal.repository;

import com.portal.entity.ProcessInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Process instance repository.
 */
@Repository
public interface ProcessInstanceRepository extends JpaRepository<ProcessInstance, String>,
        JpaSpecificationExecutor<ProcessInstance> {

    Page<ProcessInstance> findByStartUserIdOrderByStartTimeDesc(String startUserId, Pageable pageable);

    Page<ProcessInstance> findByStartUserIdAndStatusOrderByStartTimeDesc(String startUserId, String status, Pageable pageable);

    List<ProcessInstance> findByStartUserIdAndStatus(String startUserId, String status);

    long countByStartUserId(String startUserId);

    long countByStartUserIdAndStatus(String startUserId, String status);

    List<ProcessInstance> findByFunctionUnitCatalogId(String functionUnitCatalogId);

    // ── Team requests (by multiple start user IDs) ──

    Page<ProcessInstance> findByStartUserIdInOrderByStartTimeDesc(Collection<String> startUserIds, Pageable pageable);

    Page<ProcessInstance> findByStartUserIdInAndStatusOrderByStartTimeDesc(Collection<String> startUserIds, String status, Pageable pageable);

    long countByStartUserIdIn(Collection<String> startUserIds);

    long countByStartUserIdInAndStatus(Collection<String> startUserIds, String status);

    /**
     * Find active process instances assigned to the specified user.
     */
    Page<ProcessInstance> findByCurrentAssigneeAndStatusOrderByStartTimeDesc(String assignee, String status, Pageable pageable);

    /**
     * Find active process instances whose candidate users include the specified user (OR-sign scenarios).
     */
    @Query("SELECT p FROM ProcessInstance p WHERE p.status = :status AND p.candidateUsers LIKE %:userId%")
    Page<ProcessInstance> findByCandidateUsersContainingAndStatus(@Param("userId") String userId, @Param("status") String status, Pageable pageable);

    /**
     * Find active process instances assigned to the user or whose candidate users include the user.
     */
    @Query("SELECT p FROM ProcessInstance p WHERE p.status = :status AND (p.currentAssignee = :userId OR p.candidateUsers LIKE %:userId%)")
    Page<ProcessInstance> findByAssigneeOrCandidateAndStatus(@Param("userId") String userId, @Param("status") String status, Pageable pageable);

    Page<ProcessInstance> findByFunctionUnitCodeAndStartUserIdOrderByStartTimeDesc(
            String functionUnitCode, String startUserId, Pageable pageable);

    // All instances of a function unit (any initiator) — View Design shows all FU data, not per-user.
    Page<ProcessInstance> findByFunctionUnitCodeOrderByStartTimeDesc(
            String functionUnitCode, Pageable pageable);


    /**
     * Conditional update: updates {@code currentNode} and {@code currentAssignee} only when status is not COMPLETED.
     * Avoids overwriting COMPLETED set by ProcessCompletionListener after startProcess auto-completes the first task (race).
     * Returns affected row count: 0 means the process is already completed and no update is needed.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE ProcessInstance p SET p.currentNode = :currentNode, p.currentAssignee = :currentAssignee " +
           "WHERE p.id = :id AND p.status <> 'COMPLETED'")
    int updateCurrentNodeIfNotCompleted(@Param("id") String id,
                                        @Param("currentNode") String currentNode,
                                        @Param("currentAssignee") String currentAssignee);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE ProcessInstance p SET p.currentNode = :currentNode, p.currentAssignee = :currentAssignee, " +
           "p.candidateUsers = :candidateUsers WHERE p.id = :id AND p.status <> 'COMPLETED'")
    int updateCurrentNodeAndAssigneesIfNotCompleted(@Param("id") String id,
                                                    @Param("currentNode") String currentNode,
                                                    @Param("currentAssignee") String currentAssignee,
                                                    @Param("candidateUsers") String candidateUsers);
}
