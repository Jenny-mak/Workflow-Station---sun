package com.developer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 提案卡预览：生成完成时就算好的"会改什么"与"Apply 会不会被拒"。
 *
 * <p>{@code items} 按写入器同款业务键与库里现状比对得出 NEW / UPDATE（upsert 切片）、
 * BIND / REBIND（service task 绑定）或 REPLACE（清空再写的切片，附 {@code replacesExisting}）；
 * {@code issues} 是与 Apply 完全相同的两道校验（结构 + FU 感知引用）的结果，ERROR 会让 Apply 失败，
 * WARNING 只提示。预览失败不影响提案本体——Apply 时仍会再校一遍。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStudioProposalPreview {

    public enum Action { NEW, UPDATE, BIND, REBIND, REPLACE }

    public enum Severity { ERROR, WARNING }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        /** generatedData 的切片 key，如 emailTemplates / tableDefinitions */
        private String slice;
        /** 业务名（表名 / 表单名 / 模板名 / 视图名 / 任务 id …） */
        private String name;
        private Action action;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SliceReplacement {
        private String slice;
        /** Apply 会清掉的现有对象数 */
        private int replacesExisting;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Issue {
        private Severity severity;
        private String errorType;
        private String fieldPath;
        private String description;
    }

    @Builder.Default
    private List<Item> items = new ArrayList<>();

    /** 只对 REPLACE 类切片给出；没有现有对象时也列出（replacesExisting=0），前端据此决定是否警示 */
    @Builder.Default
    private List<SliceReplacement> replacements = new ArrayList<>();

    @Builder.Default
    private List<Issue> issues = new ArrayList<>();

    /** 预校验本身没跑成（异常）：前端据此不把"无问题"当成保证 */
    private boolean checked;

    /**
     * Apply 后能否撤销（与 {@code AiStudioUndoService#isUndoable} 同源）。false 时前端在 Apply 前二次确认；
     * 前端不再自己维护"哪些 scope 不可撤销"的列表。
     */
    private boolean undoable;
}
