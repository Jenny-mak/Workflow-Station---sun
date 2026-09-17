package com.developer.service.impl;

import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiStudioProposalPreview;
import com.developer.entity.AiStudioProposalJob;
import com.developer.repository.AiStudioProposalJobRepository;
import com.developer.util.PgText;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提案作业快照的落库层：作业注册表每次状态变化写一次，DW 重启后据此回答轮询。
 *
 * <p>作业里的模型调用无法跨进程续跑（需要发起人的网关 token，而 token 从不落库），
 * 所以启动时把上个进程没跑完的作业统一判为 {@link #INTERRUPTED}，由用户一键重新发起。</p>
 */
@Slf4j
@Service
public class AiStudioProposalJobStore {

    public static final String INTERRUPTED = "AI_STUDIO_PROPOSAL_INTERRUPTED";

    static final List<String> UNFINISHED = List.of("PENDING", "RUNNING");

    private final AiStudioProposalJobRepository repository;
    private final ObjectMapper objectMapper;

    public AiStudioProposalJobStore(AiStudioProposalJobRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void markInterruptedOnStartup() {
        int n = repository.markUnfinishedAsInterrupted(INTERRUPTED,
                "The server restarted before this proposal finished; send it again", Instant.now());
        if (n > 0) {
            log.warn("AI Studio proposal jobs interrupted by a restart: {}", n);
        }
    }

    /** 整份快照覆盖写（作业状态变化时调用）。 */
    @Transactional
    public void save(AiStudioProposalJobResponse snapshot, String userId, String requestKey) {
        repository.save(AiStudioProposalJob.builder()
                .jobId(snapshot.getJobId())
                .functionUnitId(snapshot.getFunctionUnitId())
                .phase(snapshot.getPhase())
                .userId(userId)
                .authorName(PgText.clean(snapshot.getAuthorName()))
                .requestKey(PgText.clean(requestKey))
                .message(PgText.clean(snapshot.getMessage()))
                .status(snapshot.getStatus().name())
                .errorCode(PgText.clean(snapshot.getErrorCode()))
                .errorMessage(PgText.clean(snapshot.getErrorMessage()))
                .reply(PgText.clean(snapshot.getReply()))
                .proposal(PgText.cleanJson(snapshot.getProposal()))
                .proposalScope(snapshot.getProposalScope())
                .preview(snapshot.getPreview() != null
                        ? PgText.cleanJson(objectMapper.convertValue(snapshot.getPreview(), Map.class)) : null)
                .submittedAt(snapshot.getSubmittedAt())
                .startedAt(snapshot.getStartedAt())
                .finishedAt(snapshot.getFinishedAt())
                .build());
    }

    /** 本人的作业快照；别人的与不存在的一样返回空。 */
    @Transactional(readOnly = true)
    public Optional<AiStudioProposalJobResponse> find(String jobId, String userId) {
        return repository.findById(jobId)
                .filter(j -> j.getUserId().equals(userId))
                .map(this::toSnapshot);
    }

    /** 本人在该功能单元上最新的未完成作业（内存里找不到时的兜底）。 */
    @Transactional(readOnly = true)
    public Optional<AiStudioProposalJobResponse> findUnfinished(Long functionUnitId, String userId) {
        return repository.findFirstByFunctionUnitIdAndUserIdAndStatusInOrderBySubmittedAtDesc(
                functionUnitId, userId, UNFINISHED).map(this::toSnapshot);
    }

    @Transactional
    public int purgeFinishedBefore(Instant before) {
        return repository.purgeFinishedBefore(before);
    }

    AiStudioProposalJobResponse toSnapshot(AiStudioProposalJob j) {
        return AiStudioProposalJobResponse.builder()
                .jobId(j.getJobId())
                .functionUnitId(j.getFunctionUnitId())
                .phase(j.getPhase())
                .message(j.getMessage())
                .authorName(j.getAuthorName())
                .status(AiStudioProposalJobResponse.Status.valueOf(j.getStatus()))
                .submittedAt(j.getSubmittedAt())
                .startedAt(j.getStartedAt())
                .finishedAt(j.getFinishedAt())
                .reply(j.getReply())
                .proposal(j.getProposal())
                .proposalScope(j.getProposalScope())
                .preview(j.getPreview() != null ? objectMapper.convertValue(j.getPreview(), AiStudioProposalPreview.class) : null)
                .errorCode(j.getErrorCode())
                .errorMessage(j.getErrorMessage())
                .build();
    }
}
