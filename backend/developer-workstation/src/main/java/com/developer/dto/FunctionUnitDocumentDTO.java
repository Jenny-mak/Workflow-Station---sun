package com.developer.dto;

import com.developer.entity.AiDocument;
import com.developer.enums.AiDocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 功能单元 Requirements / Design 文档的一个版本。历史列表不带正文（content 为 null）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionUnitDocumentDTO {

    private AiDocumentType documentType;
    /** 内部序号（保存时回传为 baseVersion、恢复与对比都用它） */
    private Integer version;
    /** 显示用：v{majorVersion}.{minorVersion} */
    private Integer majorVersion;
    private Integer minorVersion;
    private String content;
    /** 版本来源代码（MANUAL / AI_SYNC:… / RESTORED:… / ROLLBACK:… / IMPORTED / CLONED），见 FunctionUnitDocumentService */
    private String summary;
    private String createdBy;
    private Instant createdAt;

    public static FunctionUnitDocumentDTO of(AiDocument document, boolean withContent) {
        return FunctionUnitDocumentDTO.builder()
                .documentType(document.getDocumentType())
                .version(document.getVersion())
                .majorVersion(document.getMajorVersion())
                .minorVersion(document.getMinorVersion())
                .content(withContent ? document.getContent() : null)
                .summary(document.getSummary())
                .createdBy(document.getCreatedBy())
                .createdAt(document.getCreatedAt())
                .build();
    }
}
