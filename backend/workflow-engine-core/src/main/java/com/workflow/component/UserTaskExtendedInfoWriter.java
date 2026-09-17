package com.workflow.component;

import com.workflow.entity.ExtendedTaskInfo;
import com.workflow.enums.AssignmentType;
import com.workflow.repository.ExtendedTaskInfoRepository;
import com.workflow.util.FlowableCandidateUsers;
import org.flowable.task.api.Task;
import org.flowable.engine.TaskService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Ensures {@code wf_extended_task_info} exists for ordinary (non-MI) user tasks.
 * Does not overwrite an existing row, so multi-instance JSON is preserved.
 */
@Component
public class UserTaskExtendedInfoWriter {

    private final ExtendedTaskInfoRepository extendedTaskInfoRepository;
    private final TaskService taskService;

    public UserTaskExtendedInfoWriter(ExtendedTaskInfoRepository extendedTaskInfoRepository,
                                      TaskService taskService) {
        this.extendedTaskInfoRepository = extendedTaskInfoRepository;
        this.taskService = taskService;
    }

    public ExtendedTaskInfo ensureFromFlowable(Task task) {
        if (task == null || !StringUtils.hasText(task.getId())) {
            return null;
        }
        Optional<ExtendedTaskInfo> existing = extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(task.getId());
        if (existing != null && existing.isPresent()) {
            return existing.get();
        }
        if (StringUtils.hasText(task.getAssignee())) {
            return persistIfAbsent(extendedTaskInfoRepository, task, AssignmentType.USER, task.getAssignee().trim());
        }
        List<String> candidates = FlowableCandidateUsers.userIds(taskService, task.getId());
        return persistIfAbsent(extendedTaskInfoRepository, task, AssignmentType.CANDIDATE_USERS,
                String.join(",", candidates));
    }

    public static ExtendedTaskInfo persistIfAbsent(ExtendedTaskInfoRepository repository, Task task,
                                                   AssignmentType type, String target) {
        if (repository == null || task == null || !StringUtils.hasText(task.getId()) || !StringUtils.hasText(target)) {
            return null;
        }
        Optional<ExtendedTaskInfo> existing = repository.findByTaskIdAndIsDeletedFalse(task.getId());
        if (existing != null && existing.isPresent()) {
            return existing.get();
        }
        ExtendedTaskInfo created = ExtendedTaskInfo.builder()
                .taskId(task.getId())
                .processInstanceId(nonBlank(task.getProcessInstanceId(), task.getId()))
                .processDefinitionId(nonBlank(task.getProcessDefinitionId(), task.getId()))
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .taskName(task.getName())
                .assignmentType(type)
                .assignmentTarget(target.trim())
                .status("ASSIGNED")
                .createdTime(LocalDateTime.now())
                .isDeleted(false)
                .version(0L)
                .build();
        return repository.save(created);
    }

    private static String nonBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
