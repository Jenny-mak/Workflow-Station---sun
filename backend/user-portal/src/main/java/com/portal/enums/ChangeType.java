package com.portal.enums;

/**
 * 变更历史类型枚举
 */
public enum ChangeType {
    /** 字段更新 */
    FIELD_UPDATE,
    /** 子表行新增 */
    SUB_TABLE_ROW_ADD,
    /** 子表行更新 */
    SUB_TABLE_ROW_UPDATE,
    /** 子表行删除 */
    SUB_TABLE_ROW_DELETE,
    /** 流程发起 */
    PROCESS_INITIATION,
    /** Record Note 新增（评论 / 附件） */
    RECORD_NOTE_ADD,
    /** Record Note 编辑 */
    RECORD_NOTE_UPDATE,
    /** Record Note 删除 */
    RECORD_NOTE_DELETE,
    /** 用户认领 BU Role 池任务 */
    CLAIM,
    /** 持有人释放认领 */
    UNCLAIM,
    /** Leader / Approver / SYS_ADMIN 强制释放他人认领 */
    FORCE_UNCLAIM,
    /** Leader / Approver / SYS_ADMIN 将认领池任务指定给同池成员 */
    REASSIGN
}
