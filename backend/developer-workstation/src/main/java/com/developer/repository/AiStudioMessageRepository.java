package com.developer.repository;

import com.developer.entity.AiStudioMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * AI Studio 共享线程消息仓库。
 */
@Repository
public interface AiStudioMessageRepository extends JpaRepository<AiStudioMessage, Long> {

    /** 某阶段线程最新的若干条（新→旧）；调用方按需反转。 */
    List<AiStudioMessage> findByFunctionUnitIdAndPhaseOrderByIdDesc(Long functionUnitId, String phase, Pageable pageable);

    /** id 大于 afterId 的消息（旧→新），增量拉取用。 */
    List<AiStudioMessage> findByFunctionUnitIdAndPhaseAndIdGreaterThanOrderByIdAsc(
            Long functionUnitId, String phase, Long afterId, Pageable pageable);

    Optional<AiStudioMessage> findByIdAndFunctionUnitId(Long id, Long functionUnitId);

    /** 每阶段消息数：[phase, count] */
    @Query("select m.phase, count(m) from AiStudioMessage m where m.functionUnitId = :functionUnitId group by m.phase")
    List<Object[]> countByPhase(@Param("functionUnitId") Long functionUnitId);

    long countByFunctionUnitIdAndPhase(Long functionUnitId, String phase);

    /** 只保留某阶段最新的 keep 条。 */
    @Modifying
    @Query(value = """
            DELETE FROM dw_ai_studio_messages
            WHERE function_unit_id = :functionUnitId AND phase = :phase
              AND id NOT IN (
                  SELECT id FROM dw_ai_studio_messages
                  WHERE function_unit_id = :functionUnitId AND phase = :phase
                  ORDER BY id DESC LIMIT :keep)
            """, nativeQuery = true)
    int trimThread(@Param("functionUnitId") Long functionUnitId, @Param("phase") String phase, @Param("keep") int keep);
}
