package com.developer.service;

import com.developer.dto.AiStudioProposalJobResponse;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * AI Studio Copilot 提案作业注册表：把分钟级的模型调用从 HTTP 请求线程上摘下来。
 *
 * <p>模型调用与取消句柄在进程内（DW 只在 dev 单实例部署）；作业快照同时落库，
 * DW 重启后仍能查到终态结果，没跑完的作业被判为 {@code AI_STUDIO_PROPOSAL_INTERRUPTED}。</p>
 */
public interface AiStudioProposalJobService {

    /**
     * 提交参数。
     *
     * @param requestKey 请求内容的指纹（阶段 + 消息），用于识别重复提交
     * @param message    原始消息，落库供"重新发起"使用；可空
     * @param authorName 发起人展示名，队友的"正在生成"提示用；可空
     */
    record JobRequest(Long functionUnitId, String phase, String userId, String authorName,
                      String requestKey, String message) {
    }

    /**
     * 提交一份提案作业。同一 (functionUnitId, userId) 已有未完成的作业时：请求内容相同
     * （{@code requestKey} 一致，典型是刷新页面/重复点击）直接返回那一份，不重复烧模型；
     * 内容不同说明用户改了主意——取消旧作业，为新请求另起一份。
     *
     * @param work        真正的模型调用；在后台线程执行，抛出的 {@code AiGenerationException}
     *                    会以其 errorCode 记入作业，其他异常记为 {@code AI_STUDIO_PROPOSAL_FAILED}
     * @param onSucceeded 作业确实以 SUCCEEDED 落定后在后台线程回调一次（已取消/超时的迟到结果不会触发）；
     *                    回调抛错只记日志，不改变作业状态
     */
    default AiStudioProposalJobResponse submit(JobRequest request,
                                               Supplier<AiStudioChatService.StudioChatResult> work,
                                               Consumer<AiStudioChatService.StudioChatResult> onSucceeded) {
        return submit(request, work, null, onSucceeded);
    }

    /**
     * 带"落定前提交"步骤的作业（一键生成的自动 Apply）。
     *
     * @param commit 模型结果回来后、作业落定为 SUCCEEDED 之前在后台线程执行，返回值取代模型结果成为作业结果；
     *               与取消互斥：作业已被取消/超时则不会执行，一旦开始执行，取消请求要等它结束
     *               （此时作业已是 SUCCEEDED，取消幂等返回）——不会出现"用户点了 Stop、设计却被改掉"。
     *               抛出的异常与 {@code work} 同样记为作业失败。可空
     */
    AiStudioProposalJobResponse submit(JobRequest request,
                                       Supplier<AiStudioChatService.StudioChatResult> work,
                                       UnaryOperator<AiStudioChatService.StudioChatResult> commit,
                                       Consumer<AiStudioChatService.StudioChatResult> onSucceeded);

    /** 无原始消息、无完成回调的提交。 */
    default AiStudioProposalJobResponse submit(Long functionUnitId, String phase, String userId, String requestKey,
                                               Supplier<AiStudioChatService.StudioChatResult> work) {
        return submit(new JobRequest(functionUnitId, phase, userId, null, requestKey, null), work, null);
    }

    /** 无原始消息的提交。 */
    default AiStudioProposalJobResponse submit(Long functionUnitId, String phase, String userId, String requestKey,
                                               Supplier<AiStudioChatService.StudioChatResult> work,
                                               Consumer<AiStudioChatService.StudioChatResult> onSucceeded) {
        return submit(new JobRequest(functionUnitId, phase, userId, null, requestKey, null), work, onSucceeded);
    }

    /**
     * 取消作业：未完成的置为 {@code CANCELLED} 并中断后台线程（模型结果随后被丢弃）；
     * 已终态的幂等返回当前快照。不存在或不属于该用户 → {@code AI_STUDIO_PROPOSAL_NOT_FOUND}。
     */
    AiStudioProposalJobResponse cancel(String jobId, String userId);

    /**
     * 查询作业快照（内存里没有时查库）。不存在、已过期或不属于该用户时以
     * {@code AI_STUDIO_PROPOSAL_NOT_FOUND} 失败（不区分"别人的"与"没有"，避免枚举）。
     */
    AiStudioProposalJobResponse get(String jobId, String userId);

    /** 本人在该功能单元上正在跑的作业（换浏览器接着等用）。 */
    Optional<AiStudioProposalJobResponse> findActive(Long functionUnitId, String userId);

    /** 正在跑的作业及其发起人 id（推送时换算成每个订阅者的"是不是我"，不下发原值）。 */
    record ActiveJob(AiStudioProposalJobResponse snapshot, String userId) {
    }

    /** 该功能单元上所有人正在跑的作业（新订阅者据此显示"某某正在生成"）。 */
    List<ActiveJob> activeJobs(Long functionUnitId);
}
