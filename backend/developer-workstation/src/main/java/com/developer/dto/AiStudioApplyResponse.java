package com.developer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Apply 的结果：可撤销的 scope 会带回一个撤销令牌。
 *
 * <p>只有能被<b>精确逆操作</b>的 scope 才发 token——upsert 五类（删掉新增的、把更新的还原）与
 * PROCESS / TABLE_RELATIONS（整片还原，无跨切片 id 依赖）。清空重建类的 scope（表/表单/动作/决策）
 * 撤销会打断跨切片的 id 引用，那里改用 Apply 前的二次确认，见 {@code AiStudioUndoService}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStudioApplyResponse {

    /** 撤销令牌；scope 不可撤销或快照失败时为 null */
    private String undoToken;

    /** 撤销窗口截止时间；undoToken 为空时为 null */
    private Instant undoableUntil;

    /** 撤销的结果明细（仅 undo 接口返回）：每条说明某个对象被还原、删除还是跳过；文案由前端按 outcome 走 i18n */
    @Builder.Default
    private List<UndoNote> undoNotes = new ArrayList<>();

    /** 单个对象的撤销结果 */
    public enum UndoOutcome {
        /** 还原成 Apply 前的样子（整片还原时 name 为 null） */
        RESTORED,
        /** Apply 新建的，已删除 */
        DELETED,
        /** Apply 新建的连接，但已被邮件监控引用，保留 */
        KEPT_REFERENCED,
        /** 对象已不存在，跳过 */
        SKIPPED_GONE,
        /** 流程定义已不存在，绑定无从还原 */
        SKIPPED_NO_PROCESS,
        /** Apply 前未绑定，已解绑 */
        UNBOUND,
        /** 重新绑回 Apply 前的 flow，detail = flowKey */
        REBOUND,
        /** 该项还原失败，detail = 后端原因 */
        FAILED,
        /** 不支持的切片 */
        UNSUPPORTED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UndoNote {
        /** generatedData 的切片 key，如 emailTemplates / processDefinition */
        private String slice;
        /** 业务名（模板名 / 连接名 / "表 / 视图" / 任务 id）；整片还原时为 null */
        private String name;
        private UndoOutcome outcome;
        /** 附加信息：REBOUND 的 flowKey、FAILED 的原因；其余为 null */
        private String detail;
    }
}
