package com.developer.entity;

import com.developer.enums.AiDocumentType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * AI 生成文档实体
 */
@Entity
@Table(name = "dw_ai_documents")
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AiDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "function_unit_id", nullable = false)
    private Long functionUnitId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private AiDocumentType documentType;

    /** 内部序号：接口路径、保存时的版本比对都用它 */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 设计轮次；只有"开始新的 AI 设计"才 +1（界面显示 v{major}.{minor}） */
    @Column(name = "major_version", nullable = false)
    @Builder.Default
    private Integer majorVersion = 1;

    /** 本轮内的序号，从 1 开始 */
    @Column(name = "minor_version", nullable = false)
    @Builder.Default
    private Integer minorVersion = 1;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "summary", length = 500)
    private String summary;

    @CreatedBy
    @Column(name = "created_by", nullable = false, length = 64, updatable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
