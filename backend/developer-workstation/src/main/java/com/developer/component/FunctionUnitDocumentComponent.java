package com.developer.component;

import com.developer.dto.FunctionUnitDocumentDTO;
import com.developer.enums.AiDocumentType;

import java.util.List;
import java.util.Map;

/**
 * 功能单元 Requirements / Function Unit Design 文档。读需要 VIEW，写需要 MODIFY。
 */
public interface FunctionUnitDocumentComponent {

    /** 两类文档的最新版本（含正文）；还没有文档的类型值为 null。 */
    Map<AiDocumentType, FunctionUnitDocumentDTO> current(Long functionUnitId);

    /** 历史版本（新→旧，不含正文）。 */
    List<FunctionUnitDocumentDTO> history(Long functionUnitId, AiDocumentType type);

    FunctionUnitDocumentDTO version(Long functionUnitId, AiDocumentType type, int version);

    FunctionUnitDocumentDTO save(Long functionUnitId, AiDocumentType type, String content, int baseVersion);

    FunctionUnitDocumentDTO restore(Long functionUnitId, AiDocumentType type, int version, int baseVersion);
}
