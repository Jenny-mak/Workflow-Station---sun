package com.developer.service.impl;

import com.developer.dto.AiChatSseEvent;
import com.developer.dto.AiMessageResponse;
import com.developer.dto.AiSessionResponse;
import com.developer.dto.FunctionUnitContextDTO;
import com.developer.entity.AiDocument;
import com.developer.entity.AiMessage;
import com.developer.entity.AiSession;
import com.developer.entity.FunctionUnit;
import com.developer.enums.AiDocumentType;
import com.developer.enums.AiMessageRole;
import com.developer.enums.AiMode;
import com.developer.enums.AiPhase;
import com.developer.enums.AiSessionStatus;
import com.developer.exception.AiGenerationException;
import com.developer.repository.AiDocumentRepository;
import com.developer.repository.AiMessageRepository;
import com.developer.repository.AiSessionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.FunctionUnitRepository;
import com.developer.service.AiGenerationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI generation service implementation.
 */
@Service
@Slf4j
public class AiGenerationServiceImpl implements AiGenerationService {

    private final AiSessionRepository aiSessionRepository;
    private final AiMessageRepository aiMessageRepository;
    private final AiDocumentRepository aiDocumentRepository;
    private final FunctionUnitRepository functionUnitRepository;
    private final ObjectMapper objectMapper;
    private final int maxContextSizeBytes;

    // 单次模型调用的超时上限,同时决定对话 SSE emitter 的存活时长。
    // 300s: 推理模型生成 DESIGN 文档实测可达 ~230s。
    @Value("${ai-generation.gateway.timeout-seconds:300}")
    private int aiCallTimeoutSeconds;

    /** Timestamp of the last successful AI call, used for degradation info (requirements 45 linkage) */
    private volatile Instant lastAiCallSuccessTime;

    // 协作类（单一职责拆分）。Spring 通过字段注入用容器托管的 Bean 覆盖默认实例；
    // 默认实例保证脱离 Spring 上下文直接 new 本类时（单元测试）委托路径仍可用。
    // 两者均无状态、无外部依赖，故行为与原内联实现逐字一致。

    /** 上下文序列化协作类 */
    @Autowired
    private AiContextSerializer contextSerializer = new AiContextSerializer();

    /** SSE emitter 管理协作类 */
    @Autowired
    private AiSseEmitterManager sseEmitterManager = new AiSseEmitterManager();

    // 邮件模板 / 监控模板不挂在 FunctionUnit 实体上，上下文序列化按 FU 单独查。
    // 字段注入且可空：脱离 Spring 直接 new 本类的单测拿不到仓库，此时上下文里这两个切片为空列表。
    @Autowired(required = false)
    private EmailTemplateRepository emailTemplateRepository;

    @Autowired(required = false)
    private EmailMonitorRuleRepository emailMonitorRuleRepository;

    /** 主表视图快照与组织目录（VIEW_DESIGN 提案用），同样可空。 */
    @Autowired(required = false)
    private com.developer.service.MainTableViewService mainTableViewService;

    @Autowired(required = false)
    private AiOrgCatalogReader orgCatalogReader;

    /** Automation flow 目录（AUTOMATION 提案用），同样可空。 */
    @Autowired(required = false)
    private AiAutomationFlowCatalogReader automationFlowCatalogReader;

    // AI gateway 三件套(原 Activepieces flow 的 Build Prompt / Send Http request / Parse Response
    // 三个步骤的 Java 移植)。构造器注入,缺一个就启动失败——不做可空判空。
    private final AiPromptBuilder aiPromptBuilder;
    private final AiGatewayClient aiGatewayClient;
    private final AiResponseParser aiResponseParser;

    public AiGenerationServiceImpl(
            AiSessionRepository aiSessionRepository,
            AiMessageRepository aiMessageRepository,
            AiDocumentRepository aiDocumentRepository,
            FunctionUnitRepository functionUnitRepository,
            ObjectMapper objectMapper,
            AiPromptBuilder aiPromptBuilder,
            AiGatewayClient aiGatewayClient,
            AiResponseParser aiResponseParser,
            @Value("${ai-generation.context.max-size-bytes:102400}") int maxContextSizeBytes) {
        this.aiSessionRepository = aiSessionRepository;
        this.aiMessageRepository = aiMessageRepository;
        this.aiDocumentRepository = aiDocumentRepository;
        this.functionUnitRepository = functionUnitRepository;
        this.objectMapper = objectMapper;
        this.aiPromptBuilder = aiPromptBuilder;
        this.aiGatewayClient = aiGatewayClient;
        this.aiResponseParser = aiResponseParser;
        this.maxContextSizeBytes = maxContextSizeBytes;
    }

    // ==================== Session Management ====================

    @Override
    @Transactional
    public AiSession createSession(Long functionUnitId, String userId, AiMode mode) {
        AiMode resolvedMode = mode != null ? mode : determineMode(functionUnitId);

        AiSession session = AiSession.builder()
                .sessionId(UUID.randomUUID())
                .functionUnitId(functionUnitId)
                .userId(userId)
                .currentPhase(AiPhase.REQUIREMENTS)
                .mode(resolvedMode)
                .status(AiSessionStatus.ACTIVE)
                .build();

        AiSession saved = aiSessionRepository.save(session);
        log.info("Created AI session: sessionId={}, functionUnitId={}, userId={}, mode={}",
                saved.getSessionId(), functionUnitId, userId, resolvedMode);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public AiSession restoreSession(String sessionId) {
        UUID uuid = parseSessionId(sessionId);
        return aiSessionRepository.findBySessionId(uuid)
                .orElseThrow(() -> new AiGenerationException("AI_SESSION_NOT_FOUND", "Session not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiSessionResponse> getSessionsByFunctionUnitId(Long functionUnitId) {
        List<AiSession> sessions = aiSessionRepository.findByFunctionUnitIdOrderByCreatedAtDesc(functionUnitId);
        return sessions.stream()
                .map(this::toSessionResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateSessionPhase(String sessionId, AiPhase phase) {
        UUID uuid = parseSessionId(sessionId);
        AiSession session = aiSessionRepository.findBySessionId(uuid)
                .orElseThrow(() -> new AiGenerationException("AI_SESSION_NOT_FOUND", "Session not found"));

        session.setCurrentPhase(phase);
        aiSessionRepository.save(session);
        log.info("Updated session phase: sessionId={}, phase={}", sessionId, phase);
    }

    @Override
    @Transactional
    public void updateSessionStatus(String sessionId, AiSessionStatus status) {
        UUID uuid = parseSessionId(sessionId);
        AiSession session = aiSessionRepository.findBySessionId(uuid)
                .orElseThrow(() -> new AiGenerationException("AI_SESSION_NOT_FOUND", "Session not found"));

        validateStatusTransition(session.getStatus(), status);

        session.setStatus(status);
        aiSessionRepository.save(session);
        log.info("Updated session status: sessionId={}, status={}", sessionId, status);
    }

    // ==================== Mode Detection ====================

    @Override
    @Transactional(readOnly = true)
    public AiMode determineMode(Long functionUnitId) {
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new AiGenerationException("AI_FUNCTION_UNIT_NOT_FOUND", "Function unit not found"));

        boolean hasProcessDefinition = functionUnit.getProcessDefinition() != null;
        boolean hasTableDefinitions = functionUnit.getTableDefinitions() != null && !functionUnit.getTableDefinitions().isEmpty();
        boolean hasFormDefinitions = functionUnit.getFormDefinitions() != null && !functionUnit.getFormDefinitions().isEmpty();
        boolean hasActionDefinitions = functionUnit.getActionDefinitions() != null && !functionUnit.getActionDefinitions().isEmpty();
        boolean hasDecisionDefinitions = functionUnit.getDecisionDefinitions() != null && !functionUnit.getDecisionDefinitions().isEmpty();

        if (hasProcessDefinition || hasTableDefinitions || hasFormDefinitions || hasActionDefinitions || hasDecisionDefinitions) {
            log.debug("FunctionUnit {} has existing component data, mode=MODIFY", functionUnitId);
            return AiMode.MODIFY;
        }

        log.debug("FunctionUnit {} has no component data, mode=NEW", functionUnitId);
        return AiMode.NEW;
    }

    // ==================== Message Persistence ====================

    @Override
    @Transactional
    public AiMessage saveMessage(UUID sessionId, AiMessageRole role, String content, AiPhase phase) {
        AiMessage message = AiMessage.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .phase(phase)
                .build();

        AiMessage saved = aiMessageRepository.save(message);
        log.debug("Saved message: sessionId={}, role={}, phase={}", sessionId, role, phase);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiMessage> loadMessages(UUID sessionId) {
        return aiMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AiMessageResponse> getMessagesPaged(String sessionId, Pageable pageable) {
        UUID uuid = parseSessionId(sessionId);
        Page<AiMessage> messagePage = aiMessageRepository.findBySessionIdOrderByCreatedAtAsc(uuid, pageable);
        return messagePage.map(this::toMessageResponse);
    }

    // ==================== Document Version Management ====================

    @Override
    @Transactional
    public AiDocument saveDocument(Long functionUnitId, AiDocumentType documentType, String content, String summary, String userId) {
        Integer maxVersion = aiDocumentRepository
                .findTopByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(functionUnitId, documentType)
                .map(AiDocument::getVersion)
                .orElse(0);

        int newVersion = maxVersion + 1;

        AiDocument document = AiDocument.builder()
                .functionUnitId(functionUnitId)
                .documentType(documentType)
                .version(newVersion)
                .content(content)
                .summary(summary)
                .createdBy(userId)
                .build();

        AiDocument saved = aiDocumentRepository.save(document);
        log.info("Saved document: functionUnitId={}, type={}, version={}", functionUnitId, documentType, newVersion);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiDocument> getDocumentVersions(Long functionUnitId, AiDocumentType documentType) {
        return aiDocumentRepository.findByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(functionUnitId, documentType);
    }

    @Override
    @Transactional(readOnly = true)
    public AiDocument getDocumentByVersion(Long functionUnitId, AiDocumentType documentType, Integer version) {
        return aiDocumentRepository.findByFunctionUnitIdAndDocumentTypeAndVersion(functionUnitId, documentType, version)
                .orElseThrow(() -> new AiGenerationException("AI_DOCUMENT_NOT_FOUND",
                        String.format("Document version not found: functionUnitId=%d, type=%s, version=%d", functionUnitId, documentType, version)));
    }

    // ==================== Context Serialization ====================

    @Override
    @Transactional(readOnly = true)
    public FunctionUnitContextDTO serializeFunctionUnitContext(Long functionUnitId) {
        // Use findById instead of findByIdWithRelations to avoid MultipleBagFetchException
        // (Hibernate does not allow fetching multiple List-type associations simultaneously)
        // Within @Transactional, lazy loading will load associated collections one by one
        FunctionUnit fu = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new AiGenerationException("AI_FUNCTION_UNIT_NOT_FOUND", "Function unit not found"));

        FunctionUnitContextDTO dto = contextSerializer.buildContextDTO(fu,
                emailTemplateRepository != null
                        ? emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId) : List.of(),
                emailMonitorRuleRepository != null
                        ? emailMonitorRuleRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId) : List.of(),
                mainTableViewService != null
                        ? mainTableViewService.snapshotViewsForFunctionUnit(functionUnitId) : List.of(),
                orgCatalogReader != null ? orgCatalogReader.readContext() : null,
                automationFlowCatalogReader != null
                        ? automationFlowCatalogReader.toContext(automationFlowCatalogReader.readForFunctionUnit(functionUnitId))
                        : List.of());

        // Check size and truncate if needed
        byte[] jsonBytes = toJsonBytes(dto);
        if (jsonBytes.length <= maxContextSizeBytes) {
            return dto;
        }

        // First pass: truncate bpmnXml
        log.info("Context size {}B exceeds limit {}B for functionUnitId={}, truncating bpmnXml",
                jsonBytes.length, maxContextSizeBytes, functionUnitId);
        truncateBpmnXml(dto);
        jsonBytes = toJsonBytes(dto);
        if (jsonBytes.length <= maxContextSizeBytes) {
            return dto;
        }

        // Second pass: truncate all configJson
        log.info("Context still {}B after bpmnXml truncation, truncating configJson", jsonBytes.length);
        truncateConfigJson(dto);
        jsonBytes = toJsonBytes(dto);
        if (jsonBytes.length <= maxContextSizeBytes) {
            return dto;
        }

        throw new AiGenerationException("AI_CONTEXT_TOO_LARGE",
                String.format("Serialized context size %dB exceeds limit %dB", jsonBytes.length, maxContextSizeBytes));
    }

    private byte[] toJsonBytes(FunctionUnitContextDTO dto) {
        try {
            return objectMapper.writeValueAsBytes(dto);
        } catch (JsonProcessingException e) {
            throw new AiGenerationException("AI_CONTEXT_SERIALIZATION_ERROR", "Function unit context serialization failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void truncateBpmnXml(FunctionUnitContextDTO dto) {
        Map<String, Object> pd = dto.getProcessDefinition();
        if (pd != null && pd.get("bpmnXml") instanceof String bpmnXml) {
            if (bpmnXml.length() > 200) {
                pd.put("bpmnXml", bpmnXml.substring(0, 200) + "...[truncated]");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void truncateConfigJson(FunctionUnitContextDTO dto) {
        // Only truncate configJson on formDefinitions, not actionDefinitions
        if (dto.getFormDefinitions() == null) return;

        // Level 1 truncation: only truncate the rule array in configJson, keep business logic extension fields
        for (Map<String, Object> form : dto.getFormDefinitions()) {
            Object configObj = form.get("configJson");
            if (configObj instanceof Map) {
                Map<String, Object> config = (Map<String, Object>) configObj;
                if (config.containsKey("rule") && config.get("rule") instanceof List) {
                    List<?> rules = (List<?>) config.get("rule");
                    int originalCount = rules.size();
                    Map<String, Object> truncatedConfig = new LinkedHashMap<>(config);
                    truncatedConfig.put("rule", List.of(
                            new LinkedHashMap<>(Map.of("truncated", true, "originalCount", originalCount))
                    ));
                    form.put("configJson", truncatedConfig);
                    log.info("Tier-1 truncation: form '{}' rule array truncated ({} rules removed)",
                            form.get("formName"), originalCount);
                }
            }
        }

        // Check if still over limit
        byte[] jsonBytes = toJsonBytes(dto);
        if (jsonBytes.length <= maxContextSizeBytes) return;

        // Level 2 truncation: replace entire configJson on formDefinitions
        for (Map<String, Object> form : dto.getFormDefinitions()) {
            form.put("configJson", new LinkedHashMap<>(Map.of("truncated", true)));
            log.info("Tier-2 truncation: form '{}' entire configJson truncated", form.get("formName"));
        }
    }

    // ==================== Conversation History (chat/completions 无服务端会话) ====================

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, String>> buildConversationHistory(UUID sessionId) {
        List<AiMessage> messages = aiMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return messages.stream()
                .map(msg -> Map.of(
                        "role", msg.getRole().name().toLowerCase(),
                        "content", msg.getContent()))
                .collect(Collectors.toList());
    }

    /**
     * 构建"仅历史"对话记录:与 {@link #buildConversationHistory} 相同,但剔除末尾那条正是本轮
     * 用户消息的记录(它已由 message 字段单独下发),避免模型在 prompt 里看到当前消息两次。
     */
    @Transactional(readOnly = true)
    protected List<Map<String, String>> buildPriorConversationHistory(UUID sessionId, String currentMessage) {
        List<Map<String, String>> history = new ArrayList<>(buildConversationHistory(sessionId));
        if (!history.isEmpty()) {
            Map<String, String> last = history.get(history.size() - 1);
            if ("user".equals(last.get("role")) && currentMessage != null
                    && currentMessage.equals(last.get("content"))) {
                history.remove(history.size() - 1);
            }
        }
        return history;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, String>> getLatestDocuments(Long functionUnitId, AiPhase phase, AiMode mode) {
        List<Map<String, String>> documents = new ArrayList<>();

        switch (phase) {
            case REQUIREMENTS:
        // Whether NEW or MODIFY, always load existing requirements documents if present.
        // The user may have generated docs in the current session; subsequent messages need the AI to see them.
                aiDocumentRepository.findTopByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(
                        functionUnitId, AiDocumentType.REQUIREMENTS)
                    .ifPresent(doc -> documents.add(Map.of(
                        "documentType", doc.getDocumentType().name(),
                        "content", doc.getContent())));
                break;
            case DESIGN:
                aiDocumentRepository.findTopByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(
                        functionUnitId, AiDocumentType.REQUIREMENTS)
                    .ifPresent(doc -> documents.add(Map.of(
                        "documentType", doc.getDocumentType().name(),
                        "content", doc.getContent())));
                aiDocumentRepository.findTopByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(
                        functionUnitId, AiDocumentType.DESIGN)
                    .ifPresent(doc -> documents.add(Map.of(
                        "documentType", doc.getDocumentType().name(),
                        "content", doc.getContent())));
                break;
            case GENERATION:
                aiDocumentRepository.findTopByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(
                        functionUnitId, AiDocumentType.REQUIREMENTS)
                    .ifPresent(doc -> documents.add(Map.of(
                        "documentType", doc.getDocumentType().name(),
                        "content", doc.getContent())));
                aiDocumentRepository.findTopByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(
                        functionUnitId, AiDocumentType.DESIGN)
                    .ifPresent(doc -> documents.add(Map.of(
                        "documentType", doc.getDocumentType().name(),
                        "content", doc.getContent())));
                break;
        }

        return documents;
    }

    @Override
    public Map<String, Object> callAiModel(UUID sessionId, String message, AiPhase phase, AiMode mode,
                                           FunctionUnitContextDTO context, Long functionUnitId,
                                           List<Map<String, String>> existingDocuments,
                                           String regenerateScope, String amToken) {
        // chat/completions 是无状态的:每次调用都要把完整对话历史一并带上,才有多轮连续性。
        // saveMessage 已在调用本方法前持久化本轮用户消息,故构建后剔除末尾这条"当前用户消息",
        // 避免与单独传的 message 字段重复。
        List<Map<String, String>> priorHistory = buildPriorConversationHistory(sessionId, message);

        Map<String, Object> requestBody = buildAiRequestBody(sessionId, message, phase, mode,
                context, functionUnitId, existingDocuments, priorHistory, regenerateScope);

        return doCallAiWithRetry(requestBody, amToken);
    }

    private Map<String, Object> buildAiRequestBody(UUID sessionId, String message, AiPhase phase, AiMode mode,
                                                     FunctionUnitContextDTO context, Long functionUnitId,
                                                     List<Map<String, String>> existingDocuments,
                                                     List<Map<String, String>> conversationHistory,
                                                     String regenerateScope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId.toString());
        body.put("message", message);
        body.put("phase", phase.name());
        body.put("mode", mode.name());

        // Always pass functionUnitId to AI webhook for the Agent tool node to query the database.
        // Even if context is null (new function unit not yet generated), pass functionUnitId.
        if (functionUnitId != null) {
            body.put("functionUnitId", functionUnitId);
        } else if (context != null && context.getFunctionUnitId() != null) {
            body.put("functionUnitId", context.getFunctionUnitId());
        }

        if (context != null) {
            // Pre-serialize to JSON string to avoid AI webhook expression rendering as [object Object]
            body.put("context", toJsonString(context));
        }

        if (existingDocuments != null && !existingDocuments.isEmpty()) {
            List<Map<String, String>> truncated = truncateDocuments(existingDocuments);
            body.put("existingDocuments", formatDocumentsForPrompt(truncated));
        }

        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            body.put("conversationHistory", conversationHistory);
        }

        // Inject new architecture metadata to help AI generate data structures that conform to the new architecture (requirement 15)
        body.put("schemaMetadata", buildSchemaMetadata());

        // Instruct AI to include explanations when generating data (requirement 50)
        body.put("includeExplanations", true);

        // Incremental regeneration scope (requirement 42)
        body.put("regenerateScope", regenerateScope != null ? regenerateScope : "ALL");

        return body;
    }

    /**
     * Build new architecture metadata, including enum value lists, configJson extension
     * structure descriptions, ConditionExpression format, and new entity structures.
     * Provides AI webhook/AI with understanding of the current system architecture to generate
     * standard-compliant data.
     */
    private Map<String, Object> buildSchemaMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();

        // Enum value list
        metadata.put("formTypes", java.util.Arrays.stream(com.developer.enums.FormType.values())
                .map(Enum::name).collect(Collectors.toList()));
        metadata.put("tableTypes", java.util.Arrays.stream(com.developer.enums.TableType.values())
                .map(Enum::name).collect(Collectors.toList()));
        metadata.put("actionTypes", java.util.Arrays.stream(com.developer.enums.ActionType.values())
                .filter(actionType -> actionType != com.developer.enums.ActionType.N8N_ACTION)
                .map(Enum::name).collect(Collectors.toList()));

        // configJson extension field structure description
        Map<String, Object> configJsonExtensions = new LinkedHashMap<>();
        configJsonExtensions.put("formulas", "Array of { targetField, expression, dependsOn[] }");
        configJsonExtensions.put("linkages", "Array of { sourceField, targetField, linkageType: option-filtering|value-auto-fill|field-state-change }");
        configJsonExtensions.put("crossFieldRules", "Array of { fields[], operator, message, targetField }. "
                + "targetField is REQUIRED and must be one of fields[] — it is the field the validation message attaches to");
        configJsonExtensions.put("summaryRules", "Array of { sourceColumn, targetField, aggregation: SUM|AVG|COUNT|MIN|MAX }");
        configJsonExtensions.put("subTableValidation", "Object with sub-table validation rules");
        metadata.put("configJsonExtensions", configJsonExtensions);

        // visibilityCondition format description
        Map<String, Object> visibilityConditionFormat = new LinkedHashMap<>();
        visibilityConditionFormat.put("type", "ConditionExpression object (not a string)");
        visibilityConditionFormat.put("structure", "{ field, operator, value }");
        visibilityConditionFormat.put("validOperators", List.of(
                "equals", "not-equals", "contains", "greater-than", "less-than", "is-empty", "is-not-empty"));
        metadata.put("visibilityConditionFormat", visibilityConditionFormat);

        // New entity structures
        Map<String, Object> newEntities = new LinkedHashMap<>();
        newEntities.put("decisionDefinitions", "Array of { decisionKey, decisionName, dmnXml, hitPolicy: FIRST|UNIQUE|PRIORITY|ANY|COLLECT|RULE_ORDER|OUTPUT_ORDER, description }");
        newEntities.put("tableRelations", "Array of { sourceTableName, sourceFieldName, relationType: ONE_TO_ONE|ONE_TO_MANY|MANY_TO_MANY, targetTableName, targetFieldName }. "
                + "MANY_TO_ONE is NOT a valid relationType — express a child-to-parent relation as ONE_TO_MANY with the parent as source and the child as target");
        newEntities.put("formStageBindings", "Array of { stageId, stageName, readOnly: boolean } within formDefinitions[].stageBindings");
        newEntities.put("actionStageBindings", "Non-empty stageIds[] within each actionDefinitions entry; every value is an exact bpmn:userTask id. Numeric actionIds are injected after persistence");
        newEntities.put("userTaskAssignment", "Every bpmn:userTask has custom assigneeType: PROCESS_INITIATOR|ENTITY_MANAGER|FUNCTIONAL_MANAGER|HIERARCHY_ROLE|BU_ROLE|MANUAL_ASSIGN|ASSIGNEE_FROM_VARIABLE|ELEMENT_VARIABLE, plus required role/BU/variable properties");
        newEntities.put("emailTemplates", "Array of { name, subject, bodyHtml, enabled }. Upsert by name. Variables: ${fieldName} of a MAIN table field only");
        newEntities.put("emailConnections", "Array of { name (sender email address), connectionType: "
                + java.util.Arrays.stream(com.developer.enums.ConnectionType.values()).map(Enum::name).collect(Collectors.joining("|"))
                + ", direction: OUTBOUND|INBOUND, fromName, mailboxAddress, enabled }. Upsert by name+direction. "
                + "NEVER includes username/password/credentials/host/port/imap settings");
        newEntities.put("emailMonitorRules", "Array of monitor TEMPLATES { name, enabled, connectionName (existing INBOUND connection with hasCredentials=true), folderLabel, actionType: "
                + java.util.Arrays.stream(com.developer.enums.EmailMonitorActionType.values()).map(Enum::name).collect(Collectors.joining("|"))
                + ", targetFormName, extractionRules: { fields: [{ target: MAIN table fieldName, source, type, ... }] }, correlation, pollIntervalSeconds, reviewOnMissing }. "
                + "Upsert by name. Never includes startEventId/filterFrom/filterSubject/processDefinitionKey");
        newEntities.put("mainTableViews", "Array of { mainTableName, viewName, restrictToInvolvedUsers, detailFormName (SUB-table views only), "
                + "accessRules: [{ targetType: ROLE|BUSINESS_UNIT, targetId (id from orgCatalog) }], "
                + "sortConfig: [{ fieldName, direction: ASC|DESC, systemField }], "
                + "filterConfig: { logic: and|or, conditions: [{ fieldName, operator: eq|ne|contains|notContains|notStartsWith|endsWith|notEndsWith|gt|lt|isNull|isNotNull, value, systemField }] }, "
                + "fields: [{ fieldName, displayLabel, columnWidth, sortOrder, visible, systemField, columnType: field }] }. "
                + "Upsert by mainTableName+viewName; never includes id/mainTableId/detailFormId/isDefault/status. "
                + "accessRules must be empty (admin-only) or contain BOTH business units AND roles; every role must be listed under one of the chosen business units in orgCatalog. "
                + "System fields (process_status, start_time, initiator, current_step) exist on MAIN-table views only");
        newEntities.put("serviceTaskBindings", "Array of { serviceTaskId (exact id of a bpmn:serviceTask listed in serviceTasks), "
                + "flowKey (flowKey of an entry in automationFlows) }. Applied as an in-place patch of the existing BPMN: "
                + "writes the ap:flowKey extension property and serviceType=ap, clears legacy ap:* keys. "
                + "Never includes flowId, webhookUrl, inputMapping, outputMapping, timeoutSeconds, retryCount or serviceType");
        metadata.put("newEntities", newEntities);

        return metadata;
    }

    /**
     * Wraps doCallAi with an automatic retry (2-second delay) for AI_WEBHOOK_TIMEOUT
     * and AI_WEBHOOK_CALL_FAILED — the two transport-level codes the frontend treats as retryable.
     * On retry failure, builds degradation info and passes it to the caller via
     * AiGenerationException extraData (requirements 23 + 45 linkage).
     *
     * <p>传输层之外还有一类失败:gateway 回了 HTTP 200,但产物过不了平台的语义校验(阶段绑定、
     * BPMN 连通性、自环……)。这类同样不做"原样重发"——同一个 prompt 重来只是重新抽奖,每次换一种
     * 违规方式;走 {@link #repairAndRetry} 把校验器的原话喂回去定向重生成。</p>
     *
     * <p>纯模型侧失败(4xx/5xx、空回答)仍然不重试。</p>
     */
    private Map<String, Object> doCallAiWithRetry(Map<String, Object> requestBody, String amToken) {
        try {
            Map<String, Object> response = doCallAi(requestBody, amToken);
            lastAiCallSuccessTime = Instant.now();
            return response;
        } catch (AiGenerationException e) {
            if (REPAIRABLE_ERROR_CODES.contains(e.getErrorCode())) {
                return repairAndRetry(requestBody, amToken, e);
            }
            if ("AI_WEBHOOK_TIMEOUT".equals(e.getErrorCode()) || "AI_WEBHOOK_CALL_FAILED".equals(e.getErrorCode())) {
                log.warn("AI gateway call failed with {}, retrying in 2 seconds...", e.getErrorCode());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                try {
                    Map<String, Object> response = doCallAi(requestBody, amToken);
                    lastAiCallSuccessTime = Instant.now();
                    return response;
                } catch (AiGenerationException retryEx) {
                    log.warn("AI gateway retry also failed with {}: {}", retryEx.getErrorCode(), retryEx.getMessage());
                    // Build degradation info (requirements 45 linkage)
                    Map<String, Object> degradationInfo = new LinkedHashMap<>();
                    degradationInfo.put("lastSuccessTime",
                            lastAiCallSuccessTime != null ? lastAiCallSuccessTime.toString() : null);
                    degradationInfo.put("degradationOptions", List.of("SAVE_DRAFT", "MANUAL_CREATE"));
                    throw new AiGenerationException(e.getErrorCode(), e.getMessage(), degradationInfo);
                }
            }
            throw e;
        }
    }

    /**
     * 平台语义校验拒绝产物时用的错误码集合——都是"模型可以照着改"的结构性违规，
     * 与传输层失败(超时/连不上)和纯模型侧失败(4xx/空回答)区分开。
     */
    private static final Set<String> REPAIRABLE_ERROR_CODES = Set.of(
            "AI_DESIGN_STAGE_BINDING_INVALID", "AI_DESIGN_SELF_LOOP", "AI_DESIGN_DUPLICATE_FLOW",
            "AI_BPMN_NO_TASK_NODES", "AI_BPMN_DISCONNECTED_NODES", "AI_BPMN_MISSING_DI",
            "AI_BPMN_INVALID_XML", "AI_TASK_ASSIGNEE_INVALID",
            "AI_FORM_STAGE_BINDING_INVALID", "AI_ACTION_STAGE_BINDING_INVALID",
            "AI_BPMN_DECISION_VALUE_INVALID");

    /**
     * 带着校验器原话再生成一次。只修一次:单次调用上限就是 {@code aiCallTimeoutSeconds},
     * 而对话 emitter 只按两次调用的时长存活,再多一轮修复会在前端先超时。
     */
    private Map<String, Object> repairAndRetry(Map<String, Object> requestBody, String amToken,
                                               AiGenerationException failure) {
        log.warn("AI output rejected by platform validation ({}), regenerating once with the violation fed back: {}",
                failure.getErrorCode(), failure.getMessage());

        Map<String, Object> repairBody = new LinkedHashMap<>(requestBody);
        Object original = requestBody.get("message");
        repairBody.put("message", buildRepairMessage(original instanceof String s ? s : "", failure));

        try {
            Map<String, Object> response = doCallAi(repairBody, amToken);
            lastAiCallSuccessTime = Instant.now();
            log.info("AI repair pass succeeded after {}", failure.getErrorCode());
            return response;
        } catch (AiGenerationException repairEx) {
            log.warn("AI repair pass still failed with {}: {}", repairEx.getErrorCode(), repairEx.getMessage());
            throw repairEx;
        }
    }

    /**
     * 修复指令块。除了回灌具体违规，还要显式授权模型推翻自己上一轮遵循的 DESIGN 文档——
     * 87 号功能单元那次就是设计文档本身违规（把提交动作挂在 startEvent 上、给校验失败画了自环），
     * 模型每轮都在"照做非法设计"和"遵守平台约束"之间换一种折中，所以错误一直在变。
     */
    private String buildRepairMessage(String originalMessage, AiGenerationException failure) {
        return originalMessage + "\n\n"
                + "========== AUTOMATIC CORRECTION REQUEST (system-generated, highest priority) ==========\n"
                + "Your previous answer was rejected by the platform validator and was NOT saved.\n"
                + "Violation code: " + failure.getErrorCode() + "\n"
                + "Validator message: " + failure.getMessage() + "\n\n"
                + "Produce the COMPLETE output for this phase again and fix exactly this violation, keeping everything\n"
                + "that was already correct. The rules that are most often broken:\n"
                + "- A stage id is always a bpmn:userTask id. A startEvent, endEvent, gateway or sequenceFlow id is\n"
                + "  never a valid stage for an action or a TASK form; bind the submit action to the FIRST user task.\n"
                + "- Never emit a user-task self-loop. A failed check or 'not verified' outcome keeps the process on\n"
                + "  the current user task and produces no sequence flow; rollback and rework are runtime actions.\n"
                + "- Emit at most one sequence flow per source/target pair.\n"
                + "- Every branching user task has exactly one unconditional outgoing flow into its own\n"
                + "  bpmn:exclusiveGateway, and all branch conditions sit on that gateway's outgoing flows.\n"
                + "- Every flow node must be reachable from the start event and must reach an end event.\n"
                + "- Every BPMNEdge needs distinct first and last waypoints.\n"
                + "- An approval branch condition is exactly ${decision == 'yes'} or ${decision == 'no'}. Those are the\n"
                + "  only values the platform ever writes: APPROVE/PROCESS_SUBMIT sets decision=yes and\n"
                + "  REJECT/PROCESS_REJECT sets decision=no. 'approved', 'rejected', 'APPROVE', 'REJECT', true and\n"
                + "  false are never written, so a branch testing for them can never be taken.\n"
                + "If the supplied DESIGN document is what forces the violation, the platform rule wins: follow the\n"
                + "rule, correct the design accordingly, and say in your reply which part of the design you changed.\n"
                + "========== End of correction request ==========";
    }

    /** Build prompt → POST chat/completions → parse response，对应原 AP flow 的中间三步。 */
    private Map<String, Object> doCallAi(Map<String, Object> requestBody, String amToken) {
        AiPromptBuilder.RenderedPrompt prompt = aiPromptBuilder.build(requestBody);
        Map<String, Object> httpResult = aiGatewayClient.chat(prompt, amToken);
        return aiResponseParser.parse(httpResult);
    }

    // ==================== SSE Emitter Management ====================

    private static final int MAX_DOCUMENT_CONTENT_LENGTH = 50000;

    @Override
    public SseEmitter createChatEmitter(Long functionUnitId, String userId) {
        // Compute timeout here so it stays aligned with the configured AI gateway timeout (incl. one retry),
        // then delegate emitter lifecycle management to the SSE collaborator.
        long chatEmitterTimeout = (long) aiCallTimeoutSeconds * 2 * 1000 + 60_000L;
        return sseEmitterManager.createChatEmitter(functionUnitId, userId, chatEmitterTimeout);
    }

    @Override
    public SseEmitter createEventEmitter(Long functionUnitId, String userId) {
        return sseEmitterManager.createEventEmitter(functionUnitId, userId);
    }

    @Override
    public void sendChatEvent(Long functionUnitId, String userId, AiChatSseEvent event) {
        sseEmitterManager.sendChatEvent(functionUnitId, userId, event);
    }

    @Override
    public boolean isChatEmitterSuperseded(Long functionUnitId, String userId, SseEmitter emitter) {
        return sseEmitterManager.isChatEmitterSuperseded(functionUnitId, userId, emitter);
    }

    @Override
    public void sendEventNotification(Long functionUnitId, AiChatSseEvent event) {
        sseEmitterManager.sendEventNotification(functionUnitId, event);
    }

    @Override
    public void completeChatEmitter(Long functionUnitId, String userId) {
        sseEmitterManager.completeChatEmitter(functionUnitId, userId);
    }

    @Override
    public void removeChatEmitter(Long functionUnitId, String userId) {
        sseEmitterManager.removeChatEmitter(functionUnitId, userId);
    }

    @Override
    public void removeEventEmitter(Long functionUnitId, String userId) {
        sseEmitterManager.removeEventEmitter(functionUnitId, userId);
    }

    // ==================== Private Helpers ====================

    private AiSessionResponse toSessionResponse(AiSession session) {
        return AiSessionResponse.builder()
                .sessionId(session.getSessionId().toString())
                .functionUnitId(session.getFunctionUnitId())
                .currentPhase(session.getCurrentPhase())
                .mode(session.getMode())
                .status(session.getStatus())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    private AiMessageResponse toMessageResponse(AiMessage message) {
        return AiMessageResponse.builder()
                .id(message.getId())
                .sessionId(message.getSessionId().toString())
                .role(message.getRole())
                .content(message.getContent())
                .phase(message.getPhase())
                .createdAt(message.getCreatedAt())
                .build();
    }

    private void validateStatusTransition(AiSessionStatus currentStatus, AiSessionStatus newStatus) {
        // Idempotent transition: setting the same status again is a no-op.
        if (currentStatus == newStatus) {
            return;
        }

        if (currentStatus == AiSessionStatus.ACTIVE
                && (newStatus == AiSessionStatus.COMPLETED || newStatus == AiSessionStatus.CANCELLED)) {
            return; // Valid transitions
        }
        throw new AiGenerationException("AI_SESSION_INVALID_STATUS_TRANSITION",
                String.format("Invalid status transition: %s -> %s", currentStatus, newStatus));
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON serialization failed", e);
            return "{}";
        }
    }

    /**
     * Format document list as human-readable plain text for AI to understand in systemMessage.
     * Avoids JSON strings (double-escaping makes it hard for AI to parse).
     */
    private String formatDocumentsForPrompt(List<Map<String, String>> documents) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Map<String, String> doc = documents.get(i);
            String docType = doc.getOrDefault("documentType", "UNKNOWN");
            String content = doc.getOrDefault("content", "");
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append("[").append(docType).append(" Document]\n");
            sb.append(content);
        }
        return sb.toString();
    }

    private List<Map<String, String>> truncateDocuments(List<Map<String, String>> documents) {
        return documents.stream().map(doc -> {
            String content = doc.get("content");
            if (content != null && content.length() > MAX_DOCUMENT_CONTENT_LENGTH) {
                log.warn("Document content exceeds {} chars, truncating: documentType={}",
                        MAX_DOCUMENT_CONTENT_LENGTH, doc.get("documentType"));
                content = content.substring(0, MAX_DOCUMENT_CONTENT_LENGTH) + "[truncated]";
            }
            return Map.of(
                    "documentType", doc.getOrDefault("documentType", ""),
                    "content", content != null ? content : "");
        }).collect(Collectors.toList());
    }

    private UUID parseSessionId(String sessionId) {
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            throw new AiGenerationException("AI_SESSION_INVALID_ID", "Invalid session ID format");
        }
    }
}
