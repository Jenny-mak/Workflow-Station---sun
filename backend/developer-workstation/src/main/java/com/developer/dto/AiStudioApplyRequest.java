package com.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

/**
 * 应用 AI Studio Copilot 改动提案。
 *
 * <p>generatedData 是 propose 轮次返回的 proposal 原样带回（{@link AiGeneratedData} 同构），
 * scope 限定写入范围——与 AI Generate 的 regenerateScope 语义一致，由
 * {@code AiWriteService#applyGeneratedData} 消费。</p>
 */
@Data
public class AiStudioApplyRequest {

    @NotNull
    private Long functionUnitId;

    @NotBlank
    @Pattern(regexp = "TABLES|FORMS|ACTIONS|DECISIONS|PROCESS|TABLE_RELATIONS|EMAIL_TEMPLATES|CONNECTIONS|EMAIL_MONITORS|VIEWS|SERVICE_TASK_BINDINGS|ALL")
    private String scope;

    @NotNull
    private Map<String, Object> generatedData;
}
