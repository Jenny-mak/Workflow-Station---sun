package com.developer.component;

import com.developer.dto.AiStudioThreadImportRequest;
import com.developer.dto.AiStudioThreadMessageDTO;
import com.developer.dto.AiStudioThreadResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI Studio 共享线程与进度（按功能单元共享给同组成员）。读需要 VIEW，写需要 MODIFY。
 */
public interface AiStudioThreadComponent {

    AiStudioThreadResponse getThread(Long functionUnitId);

    /** @param afterId 为空时返回该阶段最新 50 条；否则只返回 id 更大的消息 */
    List<AiStudioThreadMessageDTO> getMessages(Long functionUnitId, String phase, Long afterId);

    AiStudioThreadMessageDTO getMessage(Long functionUnitId, Long messageId);

    /** 订阅该功能单元的线程变化推送（VIEW）。 */
    SseEmitter subscribe(Long functionUnitId);

    /**
     * 覆盖写已确认阶段；新增的阶段触发文档同步（后台）。
     *
     * @param amToken 模型凭证，只随文档同步作业在内存里流转
     */
    List<String> saveCompletedPhases(Long functionUnitId, List<String> completedPhases, String amToken);

    /** "立即检查"：对两份文档做一次全量核对，结果写进 {@code phase} 的线程。 */
    void checkDocuments(Long functionUnitId, String phase, String amToken);

    AiStudioThreadMessageDTO markApplied(Long functionUnitId, Long messageId, boolean applied);

    /** @return 实际导入的阶段 */
    List<String> importThreads(Long functionUnitId, AiStudioThreadImportRequest request);
}
