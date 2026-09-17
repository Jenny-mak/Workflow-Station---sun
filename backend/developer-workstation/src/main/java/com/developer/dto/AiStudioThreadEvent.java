package com.developer.dto;

/**
 * AI Studio 共享线程的变化通知（Spring 应用事件，事务提交后推给订阅了该功能单元的浏览器）。
 *
 * <p>只带 id 与少量展示信息，不带消息内容：内容里有按人计算的字段（是不是我、我能不能撤销），
 * 客户端收到后按 id 再拉一次。</p>
 *
 * @param type      事件类型，见常量
 * @param messageId MESSAGE_ADDED / MESSAGE_UPDATED 时的消息 id
 * @param jobId     PROPOSAL_STARTED / PROPOSAL_FINISHED 时的作业 id
 * @param userId    触发者（推送时换算成每个订阅者的 mine，不下发原值）
 */
public record AiStudioThreadEvent(String type, Long functionUnitId, String phase, Long messageId,
                                  String jobId, String userId, String authorName) {

    public static final String MESSAGE_ADDED = "MESSAGE_ADDED";
    public static final String MESSAGE_UPDATED = "MESSAGE_UPDATED";
    public static final String PROGRESS_UPDATED = "PROGRESS_UPDATED";
    public static final String PROPOSAL_STARTED = "PROPOSAL_STARTED";
    public static final String PROPOSAL_FINISHED = "PROPOSAL_FINISHED";
    public static final String DOC_SYNC_STARTED = "DOC_SYNC_STARTED";
    public static final String DOC_SYNC_FINISHED = "DOC_SYNC_FINISHED";

    public static AiStudioThreadEvent message(String type, Long functionUnitId, String phase, Long messageId,
                                              String userId) {
        return new AiStudioThreadEvent(type, functionUnitId, phase, messageId, null, userId, null);
    }

    public static AiStudioThreadEvent progress(Long functionUnitId, String userId) {
        return new AiStudioThreadEvent(PROGRESS_UPDATED, functionUnitId, null, null, null, userId, null);
    }

    public static AiStudioThreadEvent docSync(String type, Long functionUnitId, String phase, String userId,
                                              String authorName) {
        return new AiStudioThreadEvent(type, functionUnitId, phase, null, null, userId, authorName);
    }

    public static AiStudioThreadEvent proposal(String type, Long functionUnitId, String phase, String jobId,
                                               String userId, String authorName) {
        return new AiStudioThreadEvent(type, functionUnitId, phase, null, jobId, userId, authorName);
    }
}
