package com.developer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 首次迁移：把浏览器 localStorage 里的旧线程上传到共享线程。
 * 只导入后端还没有消息的阶段；已有内容的阶段整段跳过，避免把别人的讨论打乱。
 */
@Data
public class AiStudioThreadImportRequest {

    /** 阶段 → 旧线程（旧→新）；每阶段只取最新 50 条 */
    @NotNull
    @Size(max = 11)
    private Map<String, @Valid List<Message>> threads;

    /** 本地已确认阶段；后端还没有共享进度时一并写入 */
    @Size(max = 11)
    private List<String> completedPhases;

    @Data
    public static class Message {

        @NotBlank
        @Pattern(regexp = "USER|ASSISTANT")
        private String role;

        @NotBlank
        @Size(max = 20000)
        private String content;

        /** {scope, data, preview, applied}；可空 */
        private Map<String, Object> proposal;
    }
}
