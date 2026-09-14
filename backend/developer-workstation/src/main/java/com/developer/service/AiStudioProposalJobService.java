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
     * 提交一份提案作业。同一 (functionUnitId, userId) 已有未完成的作业时直接返回那一份，
     * 不重复烧模型——用户刷新页面或重复点击都只会拿到同一个 jobId。
     *
     * @param work 真正的模型调用；在后台线程执行，抛出的 {@code AiGenerationException}
     *             会以其 errorCode 记入作业，其他异常记为 {@code AI_STUDIO_PROPOSAL_FAILED}
     */
    AiStudioProposalJobResponse submit(Long functionUnitId, String phase, String userId,
                                       Supplier<AiStudioChatService.StudioChatResult> work);

    /**
     * 查询作业快照。不存在、已过期或不属于该用户时以 {@code AI_STUDIO_PROPOSAL_NOT_FOUND} 失败
     * （不区分"别人的"与"没有"，避免枚举）。
     */
    AiStudioProposalJobResponse get(String jobId, String userId);
}
