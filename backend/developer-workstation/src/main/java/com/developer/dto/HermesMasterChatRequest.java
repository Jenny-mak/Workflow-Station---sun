package com.developer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Hermes Master（HM，DW 全局助手）对话请求。
 *
 * <p>与 {@link AiStudioChatRequest} 分开：HM 常驻每个 DW 页面，不一定有功能单元、更没有阶段；
 * 对话只活在浏览器里，不进共享线程，所以这里没有 phase，functionUnitId 也可空。</p>
 */
@Data
public class HermesMasterChatRequest {

    @NotBlank
    @Size(max = 4000)
    private String message;

    /** 用户当前所在页面的功能单元；有则附带设计摘要，让设计建议落到真实的表/表单/节点名上。 */
    private Long functionUnitId;

    /** 用户当前所在页面（前端路由名），只用于让操作引导从"你现在在哪"说起。 */
    @Size(max = 64)
    @Pattern(regexp = "[A-Za-z0-9_-]*")
    private String page;

    /** 近期对话历史（旧→新），服务端还会按字符预算再截断一次。 */
    @Valid
    @Size(max = 20)
    private List<HistoryMessage> history;

    @Data
    public static class HistoryMessage {

        @NotBlank
        @Pattern(regexp = "USER|ASSISTANT")
        private String role;

        @NotBlank
        @Size(max = 4000)
        private String content;
    }
}
