package com.developer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 共享线程里的一条消息（读侧视图）。
 *
 * <p>只给名字与"是不是我"，不给撤销令牌——令牌只随 Apply 响应回到发起人的浏览器。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStudioThreadMessageDTO {

    private Long id;
    private String phase;
    /** USER / ASSISTANT */
    private String role;
    private String content;
    private String authorName;
    /** 作者是当前请求者 */
    private boolean mine;
    private Instant createdAt;
    /** 结构化提案；普通回复为 null */
    private Proposal proposal;
    /**
     * 文档同步结果（确认阶段 / 立即检查后写入）；其它消息为 null。
     * 结构：status(UPDATED/UNCHANGED/SKIPPED/FAILED)、phases、documents.{REQUIREMENTS,DESIGN}.
     * {fromVersion,toVersion,changeSummary,blockedBy}、errorCode、errorMessage。
     */
    private Map<String, Object> docSync;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Proposal {
        private String scope;
        private Map<String, Object> data;
        /** {@link AiStudioProposalPreview} 同构；老数据可能为空 */
        private Map<String, Object> preview;
        private boolean applied;
        private String appliedByName;
        /** Apply 的人是当前请求者（只有他能撤销） */
        private boolean appliedByMe;
        private Instant appliedAt;
    }
}
