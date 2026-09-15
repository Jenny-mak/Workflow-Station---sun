package com.developer.service.impl;

import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiQualityScore;
import com.developer.dto.AiValidationResult;
import com.developer.service.AiValidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * AI 生成数据校验服务实现（门面）
 * <p>
 * 本类保留 {@link AiValidationService} 接口的全部 public 方法签名，方法体委托给同包协作类：
 * <ul>
 *   <li>{@link AiStructureValidator} —— 枚举/字段约束/表/表单/动作/决策/表关系/图标校验</li>
 *   <li>{@link AiReferenceValidator} —— 跨实体引用完整性与唯一性校验</li>
 *   <li>{@link AiSecurityValidator} —— SVG/DMN/BPMN XML 安全校验</li>
 *   <li>{@link AiQualityScorer} —— 四维度质量评分</li>
 * </ul>
 * 各协作类的校验/评分逻辑与拆分前逐字一致，本次重构为纯结构重组，业务行为零变化。
 */
@Slf4j
@Service
public class AiValidationServiceImpl implements AiValidationService {

    private final AiStructureValidator structureValidator;
    private final AiReferenceValidator referenceValidator;
    private final AiSecurityValidator securityValidator;
    private final AiQualityScorer qualityScorer;

    @Autowired
    public AiValidationServiceImpl(AiStructureValidator structureValidator,
                                   AiReferenceValidator referenceValidator,
                                   AiSecurityValidator securityValidator,
                                   AiQualityScorer qualityScorer) {
        this.structureValidator = structureValidator;
        this.referenceValidator = referenceValidator;
        this.securityValidator = securityValidator;
        this.qualityScorer = qualityScorer;
    }

    /**
     * 无参构造：自行装配无依赖的协作类，便于在测试中以 {@code new AiValidationServiceImpl()} 直接构造。
     */
    public AiValidationServiceImpl() {
        this.securityValidator = new AiSecurityValidator();
        this.structureValidator = new AiStructureValidator(this.securityValidator);
        this.referenceValidator = new AiReferenceValidator();
        this.qualityScorer = new AiQualityScorer();
    }

    @Override
    public AiValidationResult validate(AiGeneratedData generatedData) {
        return validate(generatedData, java.util.Map.of());
    }

    @Override
    public AiValidationResult validate(AiGeneratedData generatedData,
                                       java.util.Map<String, java.util.Set<String>> existingTableFields) {
        AiValidationResult result = AiValidationResult.builder().build();

        if (generatedData == null) {
            result.addError("NULL_DATA", "generatedData", "Generated data must not be null");
            return result;
        }

        // Enum value and field constraint validation
        structureValidator.validateTableDefinitions(generatedData.getTableDefinitions(), result);
        structureValidator.validateFormDefinitions(generatedData.getFormDefinitions(), result);
        structureValidator.validateActionDefinitions(generatedData.getActionDefinitions(), result);
        structureValidator.validateDecisionDefinitions(generatedData.getDecisionDefinitions(), result);
        structureValidator.validateTableRelations(generatedData, result);
        structureValidator.validateIcon(generatedData.getIcon(), result);
        structureValidator.validateEmailTemplates(generatedData.getEmailTemplates(), result);
        structureValidator.validateEmailConnections(generatedData.getEmailConnections(), result);
        structureValidator.validateEmailMonitorRules(generatedData.getEmailMonitorRules(), result);
        structureValidator.validateMainTableViews(generatedData.getMainTableViews(), result);
        structureValidator.validateServiceTaskBindings(generatedData.getServiceTaskBindings(), result);

        // SVG security validation and BPMN XML validation
        securityValidator.validateSvg(generatedData.getIcon(), result);
        securityValidator.validateBpmnXml(generatedData.getProcessDefinition(), result);

        // Reference integrity and uniqueness validation
        referenceValidator.validateReferenceIntegrity(generatedData, result,
                existingTableFields != null ? existingTableFields : java.util.Map.of());
        referenceValidator.validateUniqueness(generatedData, result);

        return result;
    }

    // ==================== Quality Score ====================

    @Override
    public AiQualityScore computeQualityScore(AiGeneratedData data) {
        return qualityScorer.computeQualityScore(data);
    }
}
