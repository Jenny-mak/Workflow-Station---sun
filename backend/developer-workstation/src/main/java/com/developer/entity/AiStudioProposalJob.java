package com.developer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * AI Studio 提案作业的持久化快照：DW 重启后状态与结果仍可查。不存任何凭证。
 */
@Entity
@Table(name = "dw_ai_studio_proposal_jobs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStudioProposalJob {

    @Id
    @Column(name = "job_id", length = 36)
    private String jobId;

    @Column(name = "function_unit_id", nullable = false)
    private Long functionUnitId;

    @Column(name = "phase", nullable = false, length = 30)
    private String phase;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "author_name", length = 100)
    private String authorName;

    @Column(name = "request_key", columnDefinition = "TEXT")
    private String requestKey;

    /** 发起时的原始消息，"重新发起"用 */
    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /** PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "reply", columnDefinition = "TEXT")
    private String reply;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal", columnDefinition = "jsonb")
    private Map<String, Object> proposal;

    @Column(name = "proposal_scope", length = 40)
    private String proposalScope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preview", columnDefinition = "jsonb")
    private Map<String, Object> preview;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
