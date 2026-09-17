package com.developer.service;

import com.developer.dto.AiStudioProposalJobResponse;

import java.util.function.Supplier;

/**
 * AI Studio Copilot 提案作业注册表：把分钟级的模型调用从 HTTP 请求线程上摘下来。
 *
 * <p>进程内内存实现（DW 只在 dev 单实例部署，见 CLAUDE.md），重启即丢；前端轮询到 404 时
 * 提示用户重新发起，不做持久化。</p>
 */
public interface AiStudioProposalJobService {

    /**
     * 提交一份提案作业。同一 (functionUnitId, userId) 已有未完成的作业时：请求内容相同
     * （{@code requestKey} 一致，典型是刷新页面/重复点击）直接返回那一份，不重复烧模型；
     * 内容不同说明用户改了主意——取消旧作业，为新请求另起一份。
     *
     * @param requestKey 请求内容的指纹（阶段 + 消息），用于识别重复提交
     * @param work       真正的模型调用；在后台线程执行，抛出的 {@code AiGenerationException}
     *                   会以其 errorCode 记入作业，其他异常记为 {@code AI_STUDIO_PROPOSAL_FAILED}
     */
    AiStudioProposalJobResponse submit(Long functionUnitId, String phase, String userId, String requestKey,
                                       Supplier<AiStudioChatService.StudioChatResult> work);

    /**
     * 取消作业：未完成的置为 {@code CANCELLED} 并中断后台线程（模型结果随后被丢弃）；
     * 已终态的幂等返回当前快照。不存在或不属于该用户 → {@code AI_STUDIO_PROPOSAL_NOT_FOUND}。
     */
    AiStudioProposalJobResponse cancel(String jobId, String userId);

    /**
     * 查询作业快照。不存在、已过期或不属于该用户时以 {@code AI_STUDIO_PROPOSAL_NOT_FOUND} 失败
     * （不区分"别人的"与"没有"，避免枚举）。
     */
    AiStudioProposalJobResponse get(String jobId, String userId);
}
