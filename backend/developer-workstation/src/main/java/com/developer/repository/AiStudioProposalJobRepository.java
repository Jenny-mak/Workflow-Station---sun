package com.developer.repository;

import com.developer.entity.AiStudioProposalJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * AI Studio 提案作业快照仓库。
 */
@Repository
public interface AiStudioProposalJobRepository extends JpaRepository<AiStudioProposalJob, String> {

    Optional<AiStudioProposalJob> findFirstByFunctionUnitIdAndUserIdAndStatusInOrderBySubmittedAtDesc(
            Long functionUnitId, String userId, Collection<String> statuses);

    List<AiStudioProposalJob> findByStatusIn(Collection<String> statuses);

    /** 启动时：上个进程没跑完的作业一律判中断。 */
    @Modifying
    @Query("update AiStudioProposalJob j set j.status = 'FAILED', j.errorCode = :code, j.errorMessage = :message, "
            + "j.finishedAt = :now where j.status in ('PENDING', 'RUNNING')")
    int markUnfinishedAsInterrupted(@Param("code") String code, @Param("message") String message,
                                    @Param("now") Instant now);

    @Modifying
    @Query("delete from AiStudioProposalJob j where j.finishedAt is not null and j.finishedAt < :before "
            + "and j.status in ('SUCCEEDED', 'FAILED', 'CANCELLED')")
    int purgeFinishedBefore(@Param("before") Instant before);
}
