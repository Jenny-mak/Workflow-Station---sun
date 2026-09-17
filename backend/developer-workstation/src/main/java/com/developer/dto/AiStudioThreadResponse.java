package com.developer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AI Studio 共享状态概览：已确认阶段 + 每阶段消息数（前端据此决定要不要导入本地旧线程）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStudioThreadResponse {

    /** 已确认阶段；该功能单元还没有共享进度时为 null（前端据此上传本地进度） */
    private List<String> completedPhases;

    private String updatedBy;

    private Instant updatedAt;

    /** 阶段 → 消息数；没有消息的阶段不出现 */
    private Map<String, Long> messageCounts;

    /** 当前用户能否在线程里发言、确认阶段、标记 Apply（MODIFY 权限） */
    private boolean canModify;

    /** 文档同步作业正在跑（页面刷新后恢复"更新中"状态） */
    private boolean documentSyncRunning;
}
