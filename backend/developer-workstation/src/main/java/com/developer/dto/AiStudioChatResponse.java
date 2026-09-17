package com.developer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** AI Studio Copilot 单轮回复；propose 轮次可附带结构化改动提案。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStudioChatResponse {

    private String reply;

    /** 改动提案（AiGeneratedData 同构的 Map），仅 propose 轮次且模型产出了数据块时非空 */
    private Map<String, Object> proposal;

    /** 提案对应的写入范围（TABLES / FORMS / ACTIONS / DECISIONS / PROCESS），Apply 时原样带回 */
    private String proposalScope;

    /** 提案卡预览（新增/更新/替换 + 预校验问题）；仅提案轮且预览成功时非空 */
    private AiStudioProposalPreview preview;
}
