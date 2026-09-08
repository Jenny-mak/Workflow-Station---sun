package com.portal.component;

import com.portal.dto.TaskInfo;
import com.portal.entity.ChangeHistory;
import com.portal.enums.AssignmentChangeTypes;
import com.portal.enums.ChangeType;
import com.portal.repository.ChangeHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Writes claim / unclaim / force-unclaim / reassign rows to {@code up_change_history}.
 * Best-effort: a failure never blocks the assignment itself.
 */
@Slf4j
@Component
public class TaskAssignmentHistoryRecorder {

    private final ChangeHistoryRepository changeHistoryRepository;
    private final TransactionTemplate requiresNewTx;

    public TaskAssignmentHistoryRecorder(
            ChangeHistoryRepository changeHistoryRepository,
            PlatformTransactionManager transactionManager) {
        this.changeHistoryRepository = changeHistoryRepository;
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tt;
    }

    public void record(TaskInfo task, String actorUserId, ChangeType changeType, String newHolderUserId) {
        if (task == null || task.getProcessInstanceId() == null || task.getProcessInstanceId().isBlank()
                || actorUserId == null || actorUserId.isBlank()
                || !AssignmentChangeTypes.isAssignmentAction(changeType)) {
            return;
        }
        ChangeHistory record = ChangeHistory.builder()
                .processInstanceId(task.getProcessInstanceId())
                .taskInstanceId(blankToNull(task.getTaskId()))
                .stageId(blankToNull(task.getTaskDefinitionKey()))
                .userId(actorUserId)
                .timestamp(Instant.now())
                .fieldName(AssignmentChangeTypes.FIELD_NAME)
                .oldValue(blankToNull(task.getAssignee()))
                .newValue(blankToNull(newHolderUserId))
                .changeType(changeType)
                .build();
        requiresNewTx.executeWithoutResult(status -> {
            try {
                changeHistoryRepository.save(record);
            } catch (Exception e) {
                log.warn("Failed to record {} for process {}: {}",
                        changeType, task.getProcessInstanceId(), e.getMessage());
                status.setRollbackOnly();
            }
        });
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
