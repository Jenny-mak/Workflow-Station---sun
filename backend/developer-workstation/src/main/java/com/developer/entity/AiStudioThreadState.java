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
import java.util.ArrayList;
import java.util.List;

/**
 * AI Studio 按功能单元共享的进度：已确认的阶段。
 *
 * <p>"当前停在哪个阶段"是每个人自己的，留在浏览器里，不进这张表。</p>
 */
@Entity
@Table(name = "dw_ai_studio_thread_states")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStudioThreadState {

    @Id
    @Column(name = "function_unit_id")
    private Long functionUnitId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "completed_phases", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> completedPhases = new ArrayList<>();

    /** 文档的当前设计轮次；"开始新的 AI 设计"时 +1（见 FunctionUnitDocumentService） */
    @Column(name = "document_major", nullable = false)
    @Builder.Default
    private Integer documentMajor = 1;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
