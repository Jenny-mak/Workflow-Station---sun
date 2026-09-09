package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.entity.ProcessInstance;
import com.portal.exception.PortalException;
import com.portal.repository.ProcessInstanceRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the local portal {@link ProcessInstance} snapshot in sync with the workflow engine after task
 * lifecycle operations (current assignee/candidates/node) and guards generic catch blocks against
 * committing a transaction that is already marked rollback-only.
 * Extracted from {@link TaskProcessComponent}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessInstanceSyncComponent {

    private final WorkflowEngineClient workflowEngineClient;
    private final ProcessInstanceRepository processInstanceRepository;
    private final OwnerFieldComponent ownerFieldComponent;
    private final MiOuterStepResolver miOuterStepResolver;

    /**
     * Generic catch blocks must not swallow exceptions that already poisoned the Spring transaction;
     * committing afterward surfaces as UnexpectedRollbackException.
     */
    private static boolean isTransactionRollbackOnly() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
    }

    static void rethrowIfRollbackOnlyAfterCatch(Exception e, String taskId) {
        if (!isTransactionRollbackOnly()) {
            return;
        }
        if (e instanceof RuntimeException re) {
            throw re;
        }
        throw new PortalException("500",
                "Portal data could not be persisted (context: " + taskId + "); please retry or refresh.", e);
    }

    /**
     * Updates the process instance current assignee
     */
    void updateProcessInstanceAssignee(String processInstanceId, String assigneeUserId,
                                       String candidateUserIds, String currentNode) {
        updateProcessInstanceAssignee(processInstanceId, assigneeUserId, candidateUserIds, currentNode, null);
    }

    void updateProcessInstanceAssignee(String processInstanceId, String assigneeUserId,
                                       String candidateUserIds, String currentNode,
                                       Map<String, Object> taskCurrentItem) {
        if (processInstanceId == null) {
            return;
        }

        try {
            Optional<ProcessInstance> optInstance = processInstanceRepository.findById(processInstanceId);
            if (optInstance.isPresent()) {
                ProcessInstance instance = optInstance.get();
                instance.setCurrentAssignee(assigneeUserId);
                instance.setCandidateUsers(candidateUserIds);
                if (currentNode != null) {
                    instance.setCurrentNode(currentNode);
                }
                Map<String, Object> vars = instance.getVariables() == null
                        ? new HashMap<>()
                        : new HashMap<>(instance.getVariables());
                if (taskCurrentItem != null && !taskCurrentItem.isEmpty()) {
                    vars.put(OwnerFieldComponent.CURRENT_ITEM_KEY, taskCurrentItem);
                }
                String nodeForMi = currentNode != null ? currentNode : instance.getCurrentNode();
                ownerFieldComponent.applyAssigneeSnapshot(
                        instance.getFunctionUnitCode(), vars, assigneeUserId, candidateUserIds,
                        instance.getStatus(),
                        miOuterStepResolver.lookup(instance.getProcessDefinitionKey(), nodeForMi));
                OwnerFieldComponent.stripProcessWideCurrentItem(vars);
                instance.setVariables(vars);
                processInstanceRepository.save(instance);
                log.info("Updated process instance {} with currentAssignee={}, candidateUsers={}, currentNode={}",
                        processInstanceId, assigneeUserId, candidateUserIds, currentNode);
            }
        } catch (PortalException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("Failed to update process instance assignee (data access) for {}: {}",
                    processInstanceId, e.getMessage());
            throw e;
        } catch (OptimisticLockException e) {
            log.warn("Failed to update process instance assignee (optimistic lock) for {}: {}",
                    processInstanceId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("Failed to update process instance assignee for {}: {}", processInstanceId, e.getMessage());
            rethrowIfRollbackOnlyAfterCatch(e,
                    "UA-RBONLY updateProcessInstanceAssignee " + processInstanceId);
        }
    }

    /**
     * Returns the current activity node for a process instance
     */
    Optional<Map<String, Object>> getCurrentActivity(String processInstanceId) {
        try {
            if (!workflowEngineClient.isAvailable()) {
                return Optional.empty();
            }

            // Call workflow-engine for current activity
            return workflowEngineClient.getCurrentActivity(processInstanceId);
        } catch (Exception e) {
            log.warn("Failed to get current activity for process {}: {}", processInstanceId, e.getMessage());
            return Optional.empty();
        }
    }
}
