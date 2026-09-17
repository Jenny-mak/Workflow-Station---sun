package com.developer.repository;

import com.developer.entity.AiStudioThreadState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * AI Studio 共享进度仓库（主键即功能单元 id）。
 */
@Repository
public interface AiStudioThreadStateRepository extends JpaRepository<AiStudioThreadState, Long> {
}
