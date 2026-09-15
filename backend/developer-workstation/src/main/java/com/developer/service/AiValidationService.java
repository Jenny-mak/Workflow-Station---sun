package com.developer.service;

import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiQualityScore;
import com.developer.dto.AiValidationResult;

/**
 * AI 生成数据校验服务
 */
public interface AiValidationService {

    /**
     * 校验 AI 生成的数据
     *
     * @param generatedData AI 生成的结构化数据
     * @return 校验结果，包含所有校验错误
     */
    AiValidationResult validate(AiGeneratedData generatedData);

    /**
     * 带"已有表目录"的校验：scoped 提案（FORMS / TABLE_RELATIONS 等）不含 tableDefinitions，
     * 其表引用要对着功能单元里已经存在的表解析。{@code existingTableFields} 为 表名 → 字段名集合；
     * 提案自带的同名表定义优先。
     */
    AiValidationResult validate(AiGeneratedData generatedData, java.util.Map<String, java.util.Set<String>> existingTableFields);

    /**
     * 计算 AI 生成数据的质量评分
     * <p>
     * 四维度评分：完整性（是否包含所有实体类型）、一致性（引用完整性得分）、
     * 复杂度（字段类型合理性）、命名规范（snake_case/camelCase 检查）。
     * 每个维度 0-25 分，总分 0-100。
     *
     * @param data AI 生成的结构化数据
     * @return 质量评分结果，包含总分、各维度得分和改进建议
     */
    AiQualityScore computeQualityScore(AiGeneratedData data);
}
