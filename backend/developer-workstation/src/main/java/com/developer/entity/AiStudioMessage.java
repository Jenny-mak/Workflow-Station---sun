package com.developer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * AI Studio Copilot 线程里的一条消息，按 (功能单元, 阶段) 共享给能打开该功能单元的所有人。
 *
 * <p>排序用自增 id：两个人同时发言也不会撞序。撤销令牌不在这里——令牌只回到 Apply 的发起人。</p>
 */
@Entity
@Table(name = "dw_ai_studio_messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AiStudioMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "function_unit_id", nullable = false)
    private Long functionUnitId;

    @Column(name = "phase", nullable = false, length = 30)
    private String phase;

    /** USER / ASSISTANT */
    @Column(name = "role", nullable = false, length = 10)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 结构化提案 {scope, data, preview}；普通回复为空 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal", columnDefinition = "jsonb")
    private Map<String, Object> proposal;

    @Column(name = "applied_by", length = 64)
    private String appliedBy;

    @Column(name = "applied_by_name", length = 100)
    private String appliedByName;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "author_user_id", length = 64)
    private String authorUserId;

    @Column(name = "author_name", length = 100)
    private String authorName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
