package com.portal.repository;

import com.portal.entity.ProcessDraft;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 流程草稿Repository
 */
@Repository
public interface ProcessDraftRepository extends JpaRepository<ProcessDraft, Long>, JpaSpecificationExecutor<ProcessDraft> {

    List<ProcessDraft> findByUserIdOrderByUpdatedAtDesc(String userId);

    Page<ProcessDraft> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    List<ProcessDraft> findByUserIdAndProcessDefinitionKey(String userId, String processDefinitionKey);

    Optional<ProcessDraft> findFirstByUserIdAndProcessDefinitionKeyOrderByUpdatedAtDesc(
            String userId, String processDefinitionKey);

    void deleteByUserIdAndProcessDefinitionKey(String userId, String processDefinitionKey);

    long countByUserId(String userId);
}
