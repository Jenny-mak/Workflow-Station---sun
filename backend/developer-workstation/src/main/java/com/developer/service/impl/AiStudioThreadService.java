package com.developer.service.impl;

import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.AiStudioProposalPreview;
import com.developer.dto.AiStudioThreadEvent;
import com.developer.dto.AiStudioThreadImportRequest;
import com.developer.dto.AiStudioThreadMessageDTO;
import com.developer.entity.AiStudioMessage;
import com.developer.entity.AiStudioThreadState;
import com.developer.enums.AiStudioPhase;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.AiStudioMessageRepository;
import com.developer.repository.AiStudioThreadStateRepository;
import com.developer.util.PgText;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * AI Studio 按功能单元共享的线程与进度。
 *
 * <p>对话消息只由后端写（对话轮成功后、提案作业提交与完成时），前端只改两类状态：
 * 提案卡是否已 Apply、已确认的阶段。权限在 Component 层校验，这里不再判断。</p>
 *
 * <p>每次写入都发出 {@link AiStudioThreadEvent}，由推送中心在事务提交后推给订阅者。</p>
 */
@Slf4j
@Service
public class AiStudioThreadService {

    /** 每个阶段线程保留的最新消息数（与前端 localStorage 时代的上限一致） */
    static final int MESSAGES_PER_PHASE_CAP = 50;

    /** 送给模型的共享历史窗口 */
    static final int HISTORY_WINDOW = 10;

    /** proposal 列里文档同步结果的键 */
    static final String DOC_SYNC_KEY = "docSync";

    private final AiStudioMessageRepository messageRepository;
    private final AiStudioThreadStateRepository stateRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public AiStudioThreadService(AiStudioMessageRepository messageRepository,
                                 AiStudioThreadStateRepository stateRepository,
                                 ObjectMapper objectMapper,
                                 ApplicationEventPublisher events) {
        this.messageRepository = messageRepository;
        this.stateRepository = stateRepository;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    /** 请求者身份：id 用于"是不是我"，name 只用于展示。 */
    public record Author(String userId, String name) {
    }

    // ---- 读 ----

    @Transactional(readOnly = true)
    public Optional<AiStudioThreadState> state(Long functionUnitId) {
        return stateRepository.findById(functionUnitId);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> messageCounts(Long functionUnitId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : messageRepository.countByPhase(functionUnitId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** 某阶段线程（旧→新，最多 {@link #MESSAGES_PER_PHASE_CAP} 条）。 */
    @Transactional(readOnly = true)
    public List<AiStudioThreadMessageDTO> messages(Long functionUnitId, String phase, String viewerUserId) {
        requirePhase(phase);
        return latest(functionUnitId, phase, MESSAGES_PER_PHASE_CAP).stream()
                .map(m -> toDto(m, viewerUserId))
                .toList();
    }

    /** 增量：id 大于 afterId 的消息（旧→新，最多 {@link #MESSAGES_PER_PHASE_CAP} 条）。 */
    @Transactional(readOnly = true)
    public List<AiStudioThreadMessageDTO> messagesAfter(Long functionUnitId, String phase, long afterId,
                                                        String viewerUserId) {
        requirePhase(phase);
        return messageRepository.findByFunctionUnitIdAndPhaseAndIdGreaterThanOrderByIdAsc(
                        functionUnitId, phase, afterId, PageRequest.of(0, MESSAGES_PER_PHASE_CAP)).stream()
                .map(m -> toDto(m, viewerUserId))
                .toList();
    }

    /** 单条消息（已应用状态变化后按 id 刷新用）。 */
    @Transactional(readOnly = true)
    public AiStudioThreadMessageDTO message(Long functionUnitId, Long messageId, String viewerUserId) {
        return messageRepository.findByIdAndFunctionUnitId(messageId, functionUnitId)
                .map(m -> toDto(m, viewerUserId))
                .orElseThrow(() -> new ResourceNotFoundException("AiStudioMessage", messageId));
    }

    /**
     * 共享线程最近的对话，作为模型历史（含队友的讨论与提案）。线程为空返回空列表，
     * 调用方据此回落到前端带来的历史。
     */
    @Transactional(readOnly = true)
    public List<AiStudioChatRequest.HistoryMessage> recentHistory(Long functionUnitId, String phase) {
        List<AiStudioChatRequest.HistoryMessage> history = new ArrayList<>();
        for (AiStudioMessage m : latest(functionUnitId, phase, HISTORY_WINDOW)) {
            AiStudioChatRequest.HistoryMessage h = new AiStudioChatRequest.HistoryMessage();
            h.setRole(m.getRole());
            h.setContent(m.getContent());
            if (m.getProposal() != null && m.getProposal().get("data") instanceof Map<?, ?> data) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) data;
                h.setProposal(typed);
                h.setProposalScope(m.getProposal().get("scope") instanceof String s ? s : null);
            }
            history.add(h);
        }
        return history;
    }

    private List<AiStudioMessage> latest(Long functionUnitId, String phase, int limit) {
        List<AiStudioMessage> newestFirst = new ArrayList<>(messageRepository
                .findByFunctionUnitIdAndPhaseOrderByIdDesc(functionUnitId, phase, PageRequest.of(0, limit)));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    // ---- 写 ----

    /**
     * 追加一条用户消息。同一作者在该阶段最后一条消息就是同样内容的用户消息时不重复写——
     * 刷新页面/双击导致的重复提交会复用同一个提案作业，线程里也只该有一条。
     */
    @Transactional
    public AiStudioMessage appendUserMessage(Long functionUnitId, String phase, String content, Author author) {
        requirePhase(phase);
        List<AiStudioMessage> last = latest(functionUnitId, phase, 1);
        if (!last.isEmpty()) {
            AiStudioMessage tail = last.get(0);
            if (AiStudioMessage.ROLE_USER.equals(tail.getRole())
                    && Objects.equals(tail.getAuthorUserId(), author.userId())
                    && Objects.equals(tail.getContent(), PgText.clean(content))) {
                return tail;
            }
        }
        return append(functionUnitId, phase, AiStudioMessage.ROLE_USER, content, null, author);
    }

    /** 追加一条助手回复；提案轮带上 scope、数据与预览。 */
    @Transactional
    public AiStudioMessage appendAssistantMessage(Long functionUnitId, String phase, String content,
                                                  String proposalScope, Map<String, Object> proposal,
                                                  AiStudioProposalPreview preview, Author author) {
        requirePhase(phase);
        Map<String, Object> stored = null;
        if (proposal != null && proposalScope != null) {
            stored = new LinkedHashMap<>();
            stored.put("scope", proposalScope);
            stored.put("data", proposal);
            stored.put("preview", preview != null ? objectMapper.convertValue(preview, Map.class) : null);
        }
        return append(functionUnitId, phase, AiStudioMessage.ROLE_ASSISTANT, content != null ? content : "",
                stored, author);
    }

    /**
     * 追加一条文档同步结果（助手消息）。库里的角色约束只有 USER / ASSISTANT，结构化结果放在 proposal
     * 列的 {@value #DOC_SYNC_KEY} 键下（没有 scope，不是可 Apply 的提案）；content 是给旧页面看的可读摘要。
     */
    @Transactional
    public AiStudioMessage appendDocSyncMessage(Long functionUnitId, String phase, String content,
                                                Map<String, Object> docSync, Author author) {
        requirePhase(phase);
        return append(functionUnitId, phase, AiStudioMessage.ROLE_ASSISTANT, content,
                Map.of(DOC_SYNC_KEY, docSync), author);
    }

    private AiStudioMessage append(Long functionUnitId, String phase, String role, String content,
                                   Map<String, Object> proposal, Author author) {
        AiStudioMessage saved = messageRepository.save(AiStudioMessage.builder()
                .functionUnitId(functionUnitId)
                .phase(phase)
                .role(role)
                .content(PgText.clean(content))
                .proposal(PgText.cleanJson(proposal))
                .authorUserId(author.userId())
                .authorName(author.name())
                .createdAt(Instant.now())
                .build());
        messageRepository.flush();
        messageRepository.trimThread(functionUnitId, phase, MESSAGES_PER_PHASE_CAP);
        publish(AiStudioThreadEvent.message(AiStudioThreadEvent.MESSAGE_ADDED, functionUnitId, phase,
                saved.getId(), author.userId()));
        return saved;
    }

    /**
     * 标记/取消提案卡的已应用状态。取消（撤销后）只允许当初 Apply 的人——撤销令牌也只在他手里。
     */
    @Transactional
    public AiStudioThreadMessageDTO markApplied(Long functionUnitId, Long messageId, boolean applied, Author author) {
        AiStudioMessage message = messageRepository.findByIdAndFunctionUnitId(messageId, functionUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("AiStudioMessage", messageId));
        if (message.getProposal() == null || !(message.getProposal().get("scope") instanceof String)) {
            throw new DeveloperBusinessException("AI_STUDIO_MESSAGE_NOT_PROPOSAL",
                    "Message " + messageId + " carries no proposal");
        }
        if (applied) {
            message.setAppliedBy(author.userId());
            message.setAppliedByName(author.name());
            message.setAppliedAt(Instant.now());
        } else if (message.getAppliedBy() != null) {
            if (!Objects.equals(message.getAppliedBy(), author.userId())) {
                throw new DeveloperBusinessException("AI_STUDIO_APPLIED_BY_OTHER",
                        "Only the user who applied this proposal can revert its applied state");
            }
            message.setAppliedBy(null);
            message.setAppliedByName(null);
            message.setAppliedAt(null);
        }
        AiStudioMessage saved = messageRepository.save(message);
        publish(AiStudioThreadEvent.message(AiStudioThreadEvent.MESSAGE_UPDATED, functionUnitId, saved.getPhase(),
                saved.getId(), author.userId()));
        return toDto(saved, author.userId());
    }

    /** 覆盖写已确认阶段（去重、丢弃未知值，保留提交顺序）。 */
    @Transactional
    public List<String> saveCompletedPhases(Long functionUnitId, List<String> phases, String userId) {
        List<String> clean = cleanPhases(phases);
        AiStudioThreadState state = stateRepository.findById(functionUnitId)
                .orElseGet(() -> AiStudioThreadState.builder().functionUnitId(functionUnitId).build());
        state.setCompletedPhases(clean);
        state.setUpdatedBy(userId);
        state.setUpdatedAt(Instant.now());
        stateRepository.save(state);
        publish(AiStudioThreadEvent.progress(functionUnitId, userId));
        return clean;
    }

    /**
     * 首次迁移：只填后端还没有消息的阶段；共享进度不存在时写入本地进度。
     *
     * @return 实际导入的阶段
     */
    @Transactional
    public List<String> importThreads(Long functionUnitId, AiStudioThreadImportRequest request, Author author) {
        Map<String, Long> counts = messageCounts(functionUnitId);
        List<String> imported = new ArrayList<>();
        request.getThreads().forEach((phase, messages) -> {
            if (!AiStudioPhase.isValid(phase) || messages == null || messages.isEmpty()
                    || counts.getOrDefault(phase, 0L) > 0) {
                return;
            }
            List<AiStudioThreadImportRequest.Message> tail =
                    messages.subList(Math.max(0, messages.size() - MESSAGES_PER_PHASE_CAP), messages.size());
            for (AiStudioThreadImportRequest.Message m : tail) {
                messageRepository.save(toImportedEntity(functionUnitId, phase, m, author));
            }
            imported.add(phase);
        });
        // 空进度不落库：否则第一个来导入的人（本地什么都没确认过）会占住"首次"，挡住后来者真实的本地进度
        if (!cleanPhases(request.getCompletedPhases()).isEmpty() && stateRepository.findById(functionUnitId).isEmpty()) {
            saveCompletedPhases(functionUnitId, request.getCompletedPhases(), author.userId());
        }
        if (!imported.isEmpty()) {
            imported.forEach(phase -> publish(AiStudioThreadEvent.message(AiStudioThreadEvent.MESSAGE_ADDED,
                    functionUnitId, phase, null, author.userId())));
            log.info("AI Studio threads imported from the browser: functionUnitId={}, phases={}, userId={}",
                    functionUnitId, imported, author.userId());
        }
        return imported;
    }

    private AiStudioMessage toImportedEntity(Long functionUnitId, String phase,
                                             AiStudioThreadImportRequest.Message m, Author author) {
        Map<String, Object> proposal = null;
        boolean applied = false;
        if (AiStudioMessage.ROLE_ASSISTANT.equals(m.getRole()) && m.getProposal() != null
                && m.getProposal().get("scope") instanceof String scope && !scope.isBlank()
                && m.getProposal().get("data") instanceof Map<?, ?> data) {
            proposal = new LinkedHashMap<>();
            proposal.put("scope", scope);
            proposal.put("data", data);
            proposal.put("preview", m.getProposal().get("preview") instanceof Map<?, ?> p ? p : null);
            applied = Boolean.TRUE.equals(m.getProposal().get("applied"));
        }
        Instant now = Instant.now();
        return AiStudioMessage.builder()
                .functionUnitId(functionUnitId)
                .phase(phase)
                .role(m.getRole())
                .content(PgText.clean(m.getContent()))
                .proposal(PgText.cleanJson(proposal))
                .appliedBy(applied ? author.userId() : null)
                .appliedByName(applied ? author.name() : null)
                .appliedAt(applied ? now : null)
                .authorUserId(author.userId())
                .authorName(author.name())
                .createdAt(now)
                .build();
    }

    private void publish(AiStudioThreadEvent event) {
        if (events != null) {
            events.publishEvent(event);
        }
    }

    // ---- 转换 ----

    @SuppressWarnings("unchecked")
    private AiStudioThreadMessageDTO toDto(AiStudioMessage m, String viewerUserId) {
        AiStudioThreadMessageDTO.Proposal proposal = null;
        Map<String, Object> docSync = null;
        if (m.getProposal() != null && m.getProposal().get(DOC_SYNC_KEY) instanceof Map<?, ?> sync) {
            docSync = (Map<String, Object>) sync;
        } else if (m.getProposal() != null) {
            Map<String, Object> p = m.getProposal();
            proposal = AiStudioThreadMessageDTO.Proposal.builder()
                    .scope(p.get("scope") instanceof String s ? s : null)
                    .data(p.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of())
                    .preview(p.get("preview") instanceof Map<?, ?> v ? (Map<String, Object>) v : null)
                    .applied(m.getAppliedBy() != null)
                    .appliedByName(m.getAppliedByName())
                    .appliedByMe(m.getAppliedBy() != null && m.getAppliedBy().equals(viewerUserId))
                    .appliedAt(m.getAppliedAt())
                    .build();
        }
        return AiStudioThreadMessageDTO.builder()
                .id(m.getId())
                .phase(m.getPhase())
                .role(m.getRole())
                .content(m.getContent())
                .authorName(m.getAuthorName())
                .mine(m.getAuthorUserId() != null && m.getAuthorUserId().equals(viewerUserId))
                .createdAt(m.getCreatedAt())
                .proposal(proposal)
                .docSync(docSync)
                .build();
    }

    static List<String> cleanPhases(List<String> phases) {
        if (phases == null) return new ArrayList<>();
        return new ArrayList<>(phases.stream().filter(AiStudioPhase::isValid).distinct().toList());
    }

    private static void requirePhase(String phase) {
        if (!AiStudioPhase.isValid(phase)) {
            throw new DeveloperBusinessException("AI_STUDIO_UNKNOWN_PHASE", "Unknown AI Studio phase: " + phase);
        }
    }
}
