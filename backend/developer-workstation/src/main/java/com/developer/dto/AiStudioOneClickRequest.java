package com.developer.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI Studio 一键生成请求：一次模型调用产出整套核心设计，校验通过后由后端直接写入。
 */
@Data
public class AiStudioOneClickRequest {

    @NotNull
    private Long functionUnitId;

    /**
     * 需求描述。功能单元已有 Requirements 文档时可空（文档即输入）；两者都没有时以
     * {@code AI_STUDIO_ONE_CLICK_NO_INPUT} 拒绝。上限与 Copilot 单条消息一致，更长的需求走 Requirements 文档导入。
     */
    @Size(max = 4000)
    private String requirements;

    /**
     * true = 这是对上一轮结果的修正轮（"让 AI 修正"）：{@code requirements} 是修正指令而不是业务需求，
     * 写入成功后不记进 Requirements 文档。
     */
    private boolean followUp;
}
