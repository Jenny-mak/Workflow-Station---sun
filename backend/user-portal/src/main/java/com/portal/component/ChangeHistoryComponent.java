package com.portal.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.client.WorkflowEngineClient;
import com.portal.dto.ChangeHistoryContext;
import com.portal.dto.ChangeHistoryRecord;
import com.portal.dto.SubTableChange;
import com.portal.dto.UserPortalAuditQueryRequest;
import com.portal.dto.UserPortalAuditRecord;
import com.portal.entity.ChangeHistory;
import com.portal.entity.ProcessInstance;
import com.portal.enums.ChangeType;
import com.portal.repository.ChangeHistoryRepository;
import com.portal.repository.ProcessInstanceRepository;
import com.platform.common.audit.SystemAuditFields;
import com.platform.common.jdbc.SubTableRowIdentity;
import com.platform.common.jdbc.SubTableRowKeySupport;
import com.platform.security.entity.User;
import com.platform.security.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Change History component
 * Records field-level change history with a "best-effort" strategy; failures do
 * not block the main flow.
 */
@Slf4j
@Component
public class ChangeHistoryComponent {
    /**
     * Stable compatibility quarantine for internal records written before
     * submission filtering.
     */
    static final Set<String> INTERNAL_FIELD_BLACKLIST = Set.of(
            "__subTables__", "subTableName", "foreignKey", "assigneeField",
            "mainRecordId", "activeBusinessUnitId", "activeRoleId",
            "requestItemsHasHighValue", "totalPrice", "maxItemPrice", "itemCount",
            "initiator", "participant_assigner_user_id", "id", "currentUserId");
    /**
     * Form {@code config_json} for one function unit, already parsed.
     *
     * <p>{@code resolveEditableFormFields} / {@code resolveFieldLabels} / {@code resolveFieldOrders}
     * each ran the same query and the same Jackson parse, three times per change-history request
     * (and again for the sibling sensitive-masks request). Design-time data, so a short TTL is safe:
     * a form edit shows up within {@link #FORM_CONFIG_TTL_MS}.
     */
    private static final long FORM_CONFIG_TTL_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(60);
    private static final int FORM_CONFIG_CACHE_MAX = 128;

    private record CachedFormConfigs(List<Map<String, Object>> configs, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > FORM_CONFIG_TTL_MS;
        }
    }

    private final Map<String, CachedFormConfigs> formConfigCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<String, CachedFormConfigs>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedFormConfigs> eldest) {
                    return size() > FORM_CONFIG_CACHE_MAX;
                }
            });

    private final ChangeHistoryRepository changeHistoryRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final UserRepository userRepository;
    private final WorkflowEngineClient workflowEngineClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final UserPortalAuditEnricher userPortalAuditEnricher;
    private final UserPortalAuditProcessInstanceMatcher processInstanceMatcher;
    /**
     * Isolated commits for history writes — failures roll back only this slice
     * (never outer txn).
     */
    private final TransactionTemplate requiresNewTx;

    public ChangeHistoryComponent(
            ChangeHistoryRepository changeHistoryRepository,
            ProcessInstanceRepository processInstanceRepository,
            UserRepository userRepository,
            WorkflowEngineClient workflowEngineClient,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            UserPortalAuditEnricher userPortalAuditEnricher,
            UserPortalAuditProcessInstanceMatcher processInstanceMatcher,
            PlatformTransactionManager transactionManager) {
        this.changeHistoryRepository = changeHistoryRepository;
        this.processInstanceRepository = processInstanceRepository;
        this.userRepository = userRepository;
        this.workflowEngineClient = workflowEngineClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.userPortalAuditEnricher = userPortalAuditEnricher;
        this.processInstanceMatcher = processInstanceMatcher;
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tt;
    }

    private static final Set<String> SUB_TABLE_ROW_METADATA_FIELDS = Set.of(
            "id", "rowid", "rowkey", "ididw",
            "createdat", "createdby", "updatedat", "updatedby", "caserowid",
            "taskcurrentnode", "subtaskcurrentnode", "taskstatus", "subtaskstatus",
            "subtables");
    /**
     * Read-only quarantine for records written by older MI implementations. New
     * writes are governed by
     * {@link ChangeHistorySubmissionFilter}, so Function Unit field names never
     * become a global policy.
     */
    private static final Set<String> LEGACY_SYSTEM_FIELD_ALIASES = Set.of(
            "mainid", "subtaskid", "participantid", "meetingparticipantid", "parentid",
            "assigneedisplayname", "taskid", "taskdefinitionkey");
    private static final String RECORD_NOTE_FIELD_NAME = "__record_note__";
    private static final Set<String> ASSIGNEE_VALUE_FIELDS = Set.of(
            "assignee", "assigneeuserid", "assigneeid");

    /**
     * Record field changes.
     * Compare oldValues and newValues, and create a ChangeHistory record for each
     * changed field.
     */
    public void recordFieldChanges(ChangeHistoryContext context,
            Map<String, Object> oldValues,
            Map<String, Object> newValues) {
        Instant now = Instant.now();
        List<ChangeHistory> records = new ArrayList<>();
        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            String fieldName = entry.getKey();
            if (isInternalField(fieldName)) {
                continue;
            }
            Object newValue = entry.getValue();
            Object oldValue = oldValues.get(fieldName);
            if (!semanticallyEqual(fieldName, oldValue, newValue)) {
                ChangeHistory record = ChangeHistory.builder()
                        .processInstanceId(context.getProcessInstanceId())
                        .taskInstanceId(context.getTaskInstanceId())
                        .stageId(context.getStageId())
                        .userId(context.getUserId())
                        .timestamp(now)
                        .fieldName(fieldName)
                        .oldValue(toDisplayString(oldValue))
                        .newValue(toDisplayString(newValue))
                        .changeType(ChangeType.FIELD_UPDATE)
                        .build();
                records.add(record);
            }
        }
        if (records.isEmpty()) {
            return;
        }
        requiresNewTx.executeWithoutResult(status -> {
            try {
                changeHistoryRepository.saveAll(records);
                log.debug("Recorded {} field change(s) for process {}", records.size(), context.getProcessInstanceId());
            } catch (Exception e) {
                log.warn("Failed to record field changes for process {}: {}",
                        context.getProcessInstanceId(), e.getMessage());
                status.setRollbackOnly();
            }
        });
    }

    private static boolean isInternalField(String fieldName) {
        if (fieldName == null)
            return true;
        // Platform audit columns: single decision via SystemAuditFields (case-insensitive).
        if (SystemAuditFields.isAuditField(fieldName))
            return true;
        return INTERNAL_FIELD_BLACKLIST.contains(fieldName)
                || fieldName.startsWith("_snapshot_")
                || fieldName.startsWith("_baseline_")
                || fieldName.startsWith("__");
    }

    /**
     * Record sub-table changes with a pre-normalized table name (bypasses
     * normalization
     * so that numeric binding IDs can be persisted when text-key aliases are
     * absent).
     * Used by task completion to record consolidated change history.
     */
    public void recordSubTableChangesWithName(ChangeHistoryContext context,
            String normalizedName,
            List<SubTableChange> changes) {
        if (normalizedName == null || normalizedName.isBlank()) {
            return;
        }
        recordNormalizedSubTableChanges(context, normalizedName, changes);
    }

    /**
     * Record sub-table changes.
     * <p>
     * Normalizes sub-table names: skips pure-numeric binding-ID keys and lowercases
     * to merge case variants
     * (e.g. "People" and "people" are treated as the same table). Per-call
     * deduplication prevents the same
     * field change from being recorded multiple times when the same sub-table data
     * is submitted under several
     * key aliases in {@code __subTables__}.
     */
    public void recordSubTableChanges(ChangeHistoryContext context,
            String subTableName,
            List<SubTableChange> changes) {
        // Normalize: skip pure-numeric binding-ID keys; lowercase to merge
        // "People"/"people"
        String normalizedName = normalizeSubTableNameForHistory(subTableName);
        if (normalizedName == null) {
            return;
        }
        recordNormalizedSubTableChanges(context, normalizedName, changes);
    }

    /**
     * Persists one physical audit record for each changed sub-table field. This
     * keeps the
     * audit table atomic and prevents a JSON object containing several field
     * changes from
     * being stored in a single history row.
     */
    private void recordNormalizedSubTableChanges(ChangeHistoryContext context,
            String normalizedName,
            List<SubTableChange> changes) {
        Instant now = Instant.now();
        List<ChangeHistory> records = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SubTableChange change : changes) {
            ChangeType changeType = mapSubTableChangeType(change.getChangeType());
            for (String fieldName : changedSubTableFieldNames(change)) {
                String dedupKey = context.getProcessInstanceId() + "|" + normalizedName + "|"
                        + change.getChangeType() + "|" + change.getRowIdentifier() + "|" + fieldName;
                if (!seen.add(dedupKey)) {
                    log.debug("Skipping duplicate sub-table field change record (in-batch): {}", dedupKey);
                    continue;
                }
                String oldVal = toDisplayString(valueForField(change.getOldValues(), fieldName));
                String newVal = toDisplayString(valueForField(change.getNewValues(), fieldName));
                if (semanticallyEqual(fieldName,
                        valueForField(change.getOldValues(), fieldName),
                        valueForField(change.getNewValues(), fieldName))) {
                    continue;
                }
                ChangeHistory lastRecord = changeHistoryRepository
                        .findTopByProcessInstanceIdAndSubTableNameAndRowIdentifierAndFieldNameAndChangeTypeOrderByTimestampDesc(
                                context.getProcessInstanceId(), normalizedName, change.getRowIdentifier(),
                                fieldName, changeType);
                if (lastRecord != null
                        && Objects.equals(lastRecord.getOldValue(), oldVal)
                        && Objects.equals(lastRecord.getNewValue(), newVal)) {
                    log.debug(
                            "Skipping duplicate sub-table field change record (cross-save): process={}, table={}, row={}, field={}, type={}",
                            context.getProcessInstanceId(), normalizedName, change.getRowIdentifier(), fieldName,
                            changeType);
                    continue;
                }
                records.add(ChangeHistory.builder()
                        .processInstanceId(context.getProcessInstanceId())
                        .taskInstanceId(context.getTaskInstanceId())
                        .stageId(context.getStageId())
                        .userId(context.getUserId())
                        .timestamp(now)
                        .fieldName(fieldName)
                        .oldValue(oldVal)
                        .newValue(newVal)
                        .changeType(changeType)
                        .subTableName(normalizedName)
                        .rowIdentifier(change.getRowIdentifier())
                        .build());
            }
        }
        if (records.isEmpty()) {
            return;
        }
        requiresNewTx.executeWithoutResult(status -> {
            try {
                changeHistoryRepository.saveAll(records);
                log.debug("Recorded {} sub-table field change(s) for process {}, table {}",
                        records.size(), context.getProcessInstanceId(), normalizedName);
            } catch (Exception e) {
                log.warn("Failed to record sub-table changes for process {}, table {}: {}",
                        context.getProcessInstanceId(), normalizedName, e.getMessage());
                status.setRollbackOnly();
            }
        });
    }

    private static Set<String> changedSubTableFieldNames(SubTableChange change) {
        Set<String> fieldNames = new TreeSet<>();
        if (change.getOldValues() != null) {
            fieldNames.addAll(change.getOldValues().keySet());
        }
        if (change.getNewValues() != null) {
            fieldNames.addAll(change.getNewValues().keySet());
        }
        fieldNames.removeIf(ChangeHistoryComponent::isSubTableRowMetadataField);
        return fieldNames;
    }

    private static Object valueForField(Map<String, Object> values, String fieldName) {
        return values == null ? null : values.get(fieldName);
    }

    /**
     * Record a Record Note (comment / attachment) mutation in the instance's change history.
     *
     * Notes live outside the form payload, so they never surface through
     * {@link #recordFieldChanges}; this is the only path that audits them.
     * {@code rowIdentifier} carries the sub-table row id for RECORD-scope notes so the
     * multi-instance row filter in {@link #getChangeHistory(String, String)} keeps the entry
     * with its own row; TABLE-scope notes pass {@code null} (process-wide stream).
     * Best-effort like every other writer here: a failure never breaks the note operation.
     */
    public void recordNoteChange(String processInstanceId, String userId, ChangeType changeType,
                                 String rowIdentifier, String oldValue, String newValue) {
        if (processInstanceId == null || processInstanceId.isBlank()
                || userId == null || userId.isBlank() || changeType == null) {
            return;
        }
        ChangeHistory record = ChangeHistory.builder()
                .processInstanceId(processInstanceId)
                .userId(userId)
                .timestamp(Instant.now())
                .fieldName(RECORD_NOTE_FIELD_NAME)
                .oldValue(oldValue)
                .newValue(newValue)
                .changeType(changeType)
                .rowIdentifier(rowIdentifier)
                .build();
        requiresNewTx.executeWithoutResult(status -> {
            try {
                changeHistoryRepository.save(record);
            } catch (Exception e) {
                log.warn("Failed to record note change for process {}: {}", processInstanceId, e.getMessage());
                status.setRollbackOnly();
            }
        });
    }

    /**
     * Query change history, optionally filtered to a specific sub-table row.
     */
    public List<ChangeHistoryRecord> getChangeHistory(String processInstanceId, String rowIdentifier) {
        List<ChangeHistory> entities = changeHistoryRepository
                .findByProcessInstanceIdOrderByTimestampAsc(processInstanceId);
        // Filter out internal fields that were recorded before the blacklist was in
        // place
        entities = entities.stream()
                .filter(e -> !isInternalField(e.getFieldName()))
                .filter(e -> e.getSubTableName() == null || !isSubTableRowMetadataField(e.getFieldName()))
                .filter(e -> !semanticallyEqual(e.getFieldName(), e.getOldValue(), e.getNewValue()))
                .toList();
        // Multi-instance To Do passes the current collection row id so *that table's*
        // other participants stay hidden. Shared sub-tables (Correspondence, etc.) keep
        // their own row ids and must still appear — binding_link_mode is the authority.
        entities = ChangeHistoryMiRowFilter.retainForMiTask(
                jdbcTemplate, entities, processInstanceId, rowIdentifier);
        HistoryFieldMetadata fieldMetadata = resolveHistoryFieldMetadata(processInstanceId);
        entities = entities.stream()
                .filter(entity -> !isLegacySystemFieldAlias(entity.getFieldName())
                        || fieldMetadata.isExplicitlyConfigured(
                                entity.getSubTableName(), entity.getFieldName()))
                .toList();
        entities = ChangeHistoryDisplayCollapser.collapse(entities);
        UserDisplayMaps userDisplays = resolveUserDisplayNames(entities);
        UserPortalAuditEnricher.StageNameMaps stageNames = resolveStageNames(processInstanceId);
        return entities.stream()
                .flatMap(e -> toRecords(e, userDisplays, stageNames, fieldMetadata).stream())
                .filter(record -> !isLegacySystemFieldAlias(record.getFieldName())
                        || fieldMetadata.isExplicitlyConfigured(record))
                .filter(record -> fieldMetadata.isUserVisible(record))
                .toList();
    }

    /**
     * Query change history, optionally filtered by task ID.
     * For multi-instance sub-tasks, resolves the specific row identifier from task
     * variables.
     */
    public List<ChangeHistoryRecord> getChangeHistory(String processInstanceId, String rowIdentifier, String taskId) {
        String resolvedRowId = rowIdentifier;
        if ((resolvedRowId == null || resolvedRowId.isBlank()) && taskId != null && !taskId.isBlank()) {
            resolvedRowId = resolveRowIdFromTask(taskId);
        }
        return getChangeHistory(processInstanceId, resolvedRowId);
    }

    /** Query change history for the whole process (no row filter). */
    public List<ChangeHistoryRecord> getChangeHistory(String processInstanceId) {
        return getChangeHistory(processInstanceId, null, null);
    }

    /**
     * Resolve the row identifier from a multi-instance task's variables via the
     * workflow engine.
     */
    private String resolveRowIdFromTask(String taskId) {
        try {
            return workflowEngineClient.getTaskById(taskId)
                    .map(task -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> variables = (Map<String, Object>) task.get("variables");
                        if (variables != null) {
                            Object currentItem = variables.get("_currentItem");
                            if (currentItem instanceof Map<?, ?> item) {
                                Object rowId = item.get("rowId");
                                return rowId != null ? rowId.toString() : null;
                            }
                        }
                        return null;
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Could not resolve rowId from task {}: {}", taskId, e.getMessage());
        }
        return null;
    }

    /**
     * Record concurrent modification warning.
     */
    public void recordConcurrentModificationWarning(String processInstanceId,
            String fieldName,
            String userId1,
            String userId2) {
        requiresNewTx.executeWithoutResult(status -> {
            try {
                ChangeHistory record = ChangeHistory.builder()
                        .processInstanceId(processInstanceId)
                        .userId(userId2)
                        .timestamp(Instant.now())
                        .fieldName(fieldName)
                        .changeType(ChangeType.FIELD_UPDATE)
                        .isConcurrent(true)
                        .build();
                changeHistoryRepository.save(record);
                log.warn("Concurrent modification detected on process {}, field {}, users: {} and {}",
                        processInstanceId, fieldName, userId1, userId2);
            } catch (Exception e) {
                log.warn("Failed to record concurrent modification warning for process {}: {}",
                        processInstanceId, e.getMessage());
                status.setRollbackOnly();
            }
        });
    }

    private ChangeType mapSubTableChangeType(String changeType) {
        return switch (changeType) {
            case "ROW_ADD" -> ChangeType.SUB_TABLE_ROW_ADD;
            case "ROW_UPDATE" -> ChangeType.SUB_TABLE_ROW_UPDATE;
            case "ROW_DELETE" -> ChangeType.SUB_TABLE_ROW_DELETE;
            default -> ChangeType.SUB_TABLE_ROW_UPDATE;
        };
    }

    /**
     * Splits legacy sub-table JSON payloads into field-level response rows. New
     * writes are
     * already atomic; this compatibility path keeps historical records readable in
     * the same
     * one-field-per-row table without mutating audit evidence.
     */
    private List<ChangeHistoryRecord> toRecords(ChangeHistory entity,
            UserDisplayMaps userDisplays,
            UserPortalAuditEnricher.StageNameMaps stageNames,
            HistoryFieldMetadata fieldMetadata) {
        if (!isLegacySubTablePayload(entity)) {
            return List.of(toRecord(entity, userDisplays, stageNames, fieldMetadata,
                    entity.getFieldName(), entity.getOldValue(), entity.getNewValue()));
        }
        Map<String, Object> oldFields = parseJsonObject(entity.getOldValue());
        Map<String, Object> newFields = parseJsonObject(entity.getNewValue());
        Set<String> fieldNames = new TreeSet<>();
        fieldNames.addAll(oldFields.keySet());
        fieldNames.addAll(newFields.keySet());
        fieldNames.removeIf(ChangeHistoryComponent::isSubTableRowMetadataField);
        if (fieldNames.isEmpty()) {
            return List.of(toRecord(entity, userDisplays, stageNames, fieldMetadata,
                    entity.getFieldName(), entity.getOldValue(), entity.getNewValue()));
        }
        return fieldNames.stream()
                .filter(fieldName -> !isSubTableRowMetadataField(fieldName))
                .filter(fieldName -> !semanticallyEqual(fieldName,
                        oldFields.get(fieldName), newFields.get(fieldName)))
                .map(fieldName -> toRecord(entity, userDisplays, stageNames, fieldMetadata, fieldName,
                        toDisplayString(oldFields.get(fieldName)), toDisplayString(newFields.get(fieldName))))
                .toList();
    }

    /**
     * The prior writer stored an entire sub-table row payload as JSON and used the
     * table
     * name as {@code fieldName}. New audit rows retain the actual field name, so
     * this guard
     * prevents structured values (such as a selector object) from being expanded
     * incorrectly.
     */
    private static boolean isLegacySubTablePayload(ChangeHistory entity) {
        return entity.getSubTableName() != null
                && !entity.getSubTableName().isBlank()
                && entity.getSubTableName().equals(entity.getFieldName());
    }

    private Map<String, Object> parseJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private ChangeHistoryRecord toRecord(ChangeHistory entity,
            UserDisplayMaps userDisplays,
            UserPortalAuditEnricher.StageNameMaps stageNames,
            HistoryFieldMetadata fieldMetadata,
            String fieldName,
            String oldValue,
            String newValue) {
        String userName = userDisplays.standard().get(entity.getUserId());
        String stageName = null;
        if (entity.getTaskInstanceId() != null && !entity.getTaskInstanceId().isBlank()) {
            stageName = stageNames.taskInstanceIdToName().get(entity.getTaskInstanceId());
        }
        if (stageName == null && entity.getStageId() != null && !entity.getStageId().isBlank()) {
            stageName = stageNames.taskDefinitionKeyToName().get(entity.getStageId());
        }
        FieldMetadata field = fieldMetadata.resolve(entity.getSubTableName(), fieldName);
        String fieldLabel = field != null ? field.label() : null;
        Integer fieldOrder = field != null ? field.order() : null;
        return ChangeHistoryRecord.builder()
                .id(entity.getId())
                .processInstanceId(entity.getProcessInstanceId())
                .taskInstanceId(entity.getTaskInstanceId())
                .stageId(entity.getStageId())
                .stageName(stageName)
                .userId(entity.getUserId())
                .userName(userName)
                .timestamp(entity.getTimestamp())
                .fieldName(fieldName)
                .fieldLabel(fieldLabel)
                .fieldOrder(fieldOrder)
                .oldValue(displayHistoryValue(fieldName, oldValue, userDisplays))
                .newValue(displayHistoryValue(fieldName, newValue, userDisplays))
                .changeType(entity.getChangeType().name())
                .subTableName(entity.getSubTableName())
                .rowIdentifier(entity.getRowIdentifier())
                .concurrent(Boolean.TRUE.equals(entity.getIsConcurrent()))
                .build();
    }

    private UserDisplayMaps resolveUserDisplayNames(List<ChangeHistory> entities) {
        Set<String> ids = entities.stream()
                .flatMap(entity -> java.util.stream.Stream.of(
                        entity.getUserId(), entity.getOldValue(), entity.getNewValue()))
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(this::isPotentialUserId)
                .collect(Collectors.toCollection(HashSet::new));
        entities.stream()
                .filter(entity -> isAssigneeValueField(entity.getFieldName()))
                .flatMap(entity -> java.util.stream.Stream.of(entity.getOldValue(), entity.getNewValue()))
                .map(this::extractAssigneeUserId)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        entities.stream()
                .filter(ChangeHistoryComponent::isLegacySubTablePayload)
                .forEach(entity -> {
                    Map<String, Object> oldFields = parseJsonObject(entity.getOldValue());
                    Map<String, Object> newFields = parseJsonObject(entity.getNewValue());
                    String oldAssignee = extractAssigneeUserId(oldFields.get("assignee"));
                    String newAssignee = extractAssigneeUserId(newFields.get("assignee"));
                    if (oldAssignee != null)
                        ids.add(oldAssignee);
                    if (newAssignee != null)
                        ids.add(newAssignee);
                });
        if (ids.isEmpty()) {
            return new UserDisplayMaps(Map.of(), Map.of());
        }
        Map<String, String> standard = new HashMap<>();
        Map<String, String> assignee = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            if (u != null && u.getId() != null) {
                standard.put(u.getId(), displayNameForUser(u));
                assignee.put(u.getId(), canonicalAssigneeDisplayName(u));
            }
        }
        return new UserDisplayMaps(standard, assignee);
    }

    private boolean isPotentialUserId(String value) {
        return !value.isEmpty() && value.length() <= 64 && !value.startsWith("{") && !value.startsWith("[");
    }

    private static String displayValueForKnownUser(String value, Map<String, String> userDisplayById) {
        if (value == null || userDisplayById == null) {
            return value;
        }
        return userDisplayById.getOrDefault(value, value);
    }

    private String displayHistoryValue(String fieldName, String value, UserDisplayMaps userDisplays) {
        if (!isAssigneeValueField(fieldName) || value == null) {
            return displayValueForKnownUser(value, userDisplays.standard());
        }
        String userId = extractAssigneeUserId(value);
        if (userId != null) {
            String canonical = userDisplays.assignee().get(userId);
            if (canonical != null) {
                return canonical;
            }
        }
        Map<String, Object> userValue = parseJsonObject(value);
        String embedded = canonicalAssigneeDisplayName(userValue);
        return embedded != null ? embedded : displayValueForKnownUser(value, userDisplays.standard());
    }

    private static String displayNameForUser(User u) {
        if (u.getFullName() != null && !u.getFullName().isBlank()) {
            return u.getFullName().trim();
        }
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) {
            return u.getDisplayName().trim();
        }
        return u.getUsername();
    }

    private static String canonicalAssigneeDisplayName(User user) {
        String name = firstNonBlank(user.getFullName(), user.getDisplayName(), user.getUsername());
        String username = trimToNull(user.getUsername());
        if (name == null)
            return username;
        return username != null && !name.equals(username) ? name + " (" + username + ")" : name;
    }

    private static String canonicalAssigneeDisplayName(Map<String, Object> value) {
        if (value == null || value.isEmpty())
            return null;
        String name = firstNonBlank(
                stringOrNull(value.get("full_name")), stringOrNull(value.get("fullName")),
                stringOrNull(value.get("display_name")), stringOrNull(value.get("displayName")),
                stringOrNull(value.get("username")));
        String username = trimToNull(stringOrNull(value.get("username")));
        if (name == null)
            return username;
        return username != null && !name.equals(username) ? name + " (" + username + ")" : name;
    }

    private boolean semanticallyEqual(String fieldName, Object oldValue, Object newValue) {
        if (!isAssigneeValueField(fieldName)) {
            return Objects.equals(oldValue, newValue);
        }
        String oldUserId = extractAssigneeUserId(oldValue);
        String newUserId = extractAssigneeUserId(newValue);
        if (oldUserId != null || newUserId != null) {
            return Objects.equals(oldUserId, newUserId);
        }
        return Objects.equals(oldValue, newValue);
    }

    private String extractAssigneeUserId(Object value) {
        if (value == null)
            return null;
        if (value instanceof Map<?, ?> map) {
            return firstNonBlank(
                    stringOrNull(map.get("id")), stringOrNull(map.get("userId")),
                    stringOrNull(map.get("user_id")), stringOrNull(map.get("value")));
        }
        String raw = trimToNull(String.valueOf(value));
        if (raw == null)
            return null;
        if (raw.startsWith("{") && raw.endsWith("}")) {
            Map<String, Object> parsed = parseJsonObject(raw);
            if (!parsed.isEmpty())
                return extractAssigneeUserId(parsed);
        }
        return raw;
    }

    static boolean isAssigneeValueField(String fieldName) {
        return fieldName != null
                && ASSIGNEE_VALUE_FIELDS.contains(normalizeFieldKey(fieldName));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null)
                return normalized;
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null)
            return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record UserDisplayMaps(Map<String, String> standard,
            Map<String, String> assignee) {
    }

    private UserPortalAuditEnricher.StageNameMaps resolveStageNames(String processInstanceId) {
        Map<String, String> byTaskId = new HashMap<>();
        Map<String, String> byDefKey = new HashMap<>();
        try {
            workflowEngineClient.getTaskHistory(processInstanceId).ifPresent(tasks -> {
                for (Map<String, Object> task : tasks) {
                    String name = firstNonBlank(
                            stringOrNull(task.get("name")),
                            stringOrNull(task.get("activityName")),
                            stringOrNull(task.get("taskName")));
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    String taskInstanceId = firstNonBlank(
                            stringOrNull(task.get("id")),
                            stringOrNull(task.get("taskId")));
                    if (taskInstanceId != null && !taskInstanceId.isBlank()) {
                        byTaskId.putIfAbsent(taskInstanceId, name.trim());
                    }
                    String defKey = firstNonBlank(
                            stringOrNull(task.get("activityId")),
                            stringOrNull(task.get("taskDefinitionKey")));
                    if (defKey != null && !defKey.isBlank()) {
                        byDefKey.putIfAbsent(defKey, name.trim());
                    }
                }
            });
        } catch (Exception e) {
            log.debug("Could not enrich change history stage names for {}: {}", processInstanceId, e.getMessage());
        }
        // Engine history often 403s for service-to-service calls; Flowable tables are shared.
        if (byTaskId.isEmpty() && byDefKey.isEmpty() && processInstanceId != null && !processInstanceId.isBlank()) {
            Map<String, UserPortalAuditEnricher.StageNameMaps> fromDb =
                    userPortalAuditEnricher.resolveStageNamesFromDb(Set.of(processInstanceId));
            if (fromDb == null) {
                fromDb = Map.of();
            }
            return fromDb.getOrDefault(
                    processInstanceId,
                    new UserPortalAuditEnricher.StageNameMaps(Map.of(), Map.of()));
        }
        return new UserPortalAuditEnricher.StageNameMaps(byTaskId, byDefKey);
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * Builds the audit display contract from the form definition and the
     * relation-table
     * field definitions belonging to the process function unit. This deliberately
     * uses
     * configured metadata rather than inferring relationships from field-name
     * conventions.
     */
    private HistoryFieldMetadata resolveHistoryFieldMetadata(String processInstanceId) {
        Map<String, FieldMetadata> topLevelFields = new HashMap<>();
        Set<String> editableFormFields = resolveEditableFormFields(processInstanceId);
        Map<String, String> labels = resolveFieldLabels(processInstanceId);
        Map<String, Integer> orders = resolveFieldOrders(processInstanceId);
        Set<String> formFields = new HashSet<>();
        formFields.addAll(labels.keySet());
        formFields.addAll(orders.keySet());
        for (String fieldName : formFields) {
            topLevelFields.put(fieldName, new FieldMetadata(labels.get(fieldName), orders.get(fieldName)));
        }
        Map<String, Map<String, FieldMetadata>> subTableFields = new HashMap<>();
        try {
            ProcessInstance processInstance = processInstanceRepository.findById(processInstanceId).orElse(null);
            if (processInstance == null || processInstance.getProcessDefinitionKey() == null) {
                return new HistoryFieldMetadata(topLevelFields, subTableFields, editableFormFields);
            }
            List<SubTableFieldMetadataRow> rows = jdbcTemplate.query(
                    """
                            SELECT td.table_name, fd.field_name,
                                   COALESCE(NULLIF(BTRIM(fd.display_name), ''), fd.field_name) AS display_name,
                                   fd.sort_order
                            FROM dw_table_definitions td
                            INNER JOIN dw_field_definitions fd ON fd.table_id = td.id
                            INNER JOIN dw_function_units fu ON fu.id = td.function_unit_id
                            WHERE fu.code = ? AND td.table_type = 'SUB'
                            """,
                    (rs, rowNum) -> new SubTableFieldMetadataRow(
                            rs.getString("table_name"),
                            rs.getString("field_name"),
                            rs.getString("display_name"),
                            rs.getInt("sort_order")),
                    processInstance.getProcessDefinitionKey().trim());
            for (SubTableFieldMetadataRow row : rows) {
                String tableName = normalizeSubTableNameForHistory(row.tableName());
                if (tableName == null || row.fieldName() == null || row.fieldName().isBlank()) {
                    continue;
                }
                subTableFields.computeIfAbsent(tableName, ignored -> new HashMap<>())
                        .putIfAbsent(row.fieldName(), new FieldMetadata(row.displayName(), row.sortOrder()));
            }
        } catch (Exception e) {
            log.debug("Could not resolve configured sub-table fields for {}: {}", processInstanceId, e.getMessage());
        }
        return new HistoryFieldMetadata(topLevelFields, subTableFields, editableFormFields);
    }

    private record FieldMetadata(String label, Integer order) {
    }

    private record SubTableFieldMetadataRow(String tableName, String fieldName,
            String displayName, int sortOrder) {
    }

    private record HistoryFieldMetadata(Map<String, FieldMetadata> topLevelFields,
            Map<String, Map<String, FieldMetadata>> subTableFields,
            Set<String> editableFormFields) {
        FieldMetadata resolve(String subTableName, String fieldName) {
            if (fieldName == null || fieldName.isBlank()) {
                return null;
            }
            String normalizedTableName = normalizeSubTableNameForHistory(subTableName);
            if (normalizedTableName != null) {
                Map<String, FieldMetadata> tableFields = subTableFields.get(normalizedTableName);
                if (tableFields != null) {
                    return tableFields.get(fieldName);
                }
            }
            return topLevelFields.get(fieldName);
        }

        boolean isUserVisible(ChangeHistoryRecord record) {
            String fieldName = record.getFieldName();
            if (fieldName == null || fieldName.isBlank()) {
                return false;
            }
            String normalizedTableName = normalizeSubTableNameForHistory(record.getSubTableName());
            if (normalizedTableName != null) {
                Map<String, FieldMetadata> tableFields = subTableFields.get(normalizedTableName);
                return tableFields == null || tableFields.containsKey(fieldName);
            }
            return topLevelFields.isEmpty() || topLevelFields.containsKey(fieldName);
        }

        boolean isExplicitlyConfigured(ChangeHistoryRecord record) {
            return isExplicitlyConfigured(record.getSubTableName(), record.getFieldName());
        }

        boolean isExplicitlyConfigured(String subTableName, String fieldName) {
            return editableFormFields.contains(editableFieldKey(subTableName, fieldName));
        }
    }

    private Set<String> resolveEditableFormFields(String processInstanceId) {
        Set<String> fields = new HashSet<>();
        try {
            ProcessInstance pi = processInstanceRepository.findById(processInstanceId).orElse(null);
            if (pi == null || pi.getProcessDefinitionKey() == null)
                return fields;
            List<FormConfigRow> configs = jdbcTemplate.query(
                    """
                            SELECT fd.id, fd.config_json::text AS config_json
                            FROM dw_form_definitions fd
                            INNER JOIN dw_function_units fu ON fu.id = fd.function_unit_id
                            WHERE fu.code = ?
                            """,
                    (rs, rowNum) -> new FormConfigRow(
                            rs.getLong("id"), rs.getString("config_json")),
                    pi.getProcessDefinitionKey().trim());
            Map<String, String> bindingHistoryNames = resolveBindingHistoryNames(
                    pi.getProcessDefinitionKey().trim());
            for (FormConfigRow form : configs) {
                String raw = form.configJson();
                if (raw == null || raw.isBlank())
                    continue;
                Map<String, Object> config = objectMapper.readValue(raw, new TypeReference<>() {
                });
                collectEditableHistoryFields(config, null, bindingHistoryNames, fields);
            }
        } catch (Exception e) {
            log.debug("Could not resolve editable history fields for {}: {}", processInstanceId, e.getMessage());
        }
        return fields;
    }

    private Map<String, String> resolveBindingHistoryNames(String functionUnitCode) {
        Map<String, String> names = new HashMap<>();
        List<BindingHistoryNameRow> rows = jdbcTemplate.query(
                """
                        SELECT binding.id,
                            COALESCE(td.table_name, rt.table_name) AS table_name,
                            COALESCE(td.table_display_name, rt.display_name) AS table_display_name
                        FROM dw_form_table_bindings binding
                        INNER JOIN dw_form_definitions form ON form.id = binding.form_id
                        INNER JOIN dw_function_units fu ON fu.id = form.function_unit_id
                        LEFT JOIN dw_table_definitions td ON td.id = binding.table_id
                        LEFT JOIN rt_table_definitions rt ON rt.id = binding.relation_table_id
                        WHERE fu.code = ?
                        """,
                (rs, rowNum) -> new BindingHistoryNameRow(
                        rs.getString("id"), rs.getString("table_name"),
                        rs.getString("table_display_name")),
                functionUnitCode);
        for (BindingHistoryNameRow row : rows) {
            String historyName = normalizeSubTableNameForHistory(row.tableName());
            if (historyName == null)
                continue;
            registerHistoryName(names, row.bindingId(), historyName);
            registerHistoryName(names, row.tableName(), historyName);
            registerHistoryName(names, row.tableDisplayName(), historyName);
        }
        return names;
    }

    private static void registerHistoryName(Map<String, String> names, String alias, String historyName) {
        String normalizedAlias = normalizeSubTableNameForHistory(alias);
        if (normalizedAlias != null)
            names.putIfAbsent(normalizedAlias, historyName);
    }

    @SuppressWarnings("unchecked")
    private void collectEditableHistoryFields(Map<String, Object> config,
            String subTableName,
            Map<String, String> bindingHistoryNames,
            Set<String> fields) {
        Object rulesValue = config.get("rule");
        if (rulesValue instanceof List<?> rules) {
            for (Object value : rules) {
                if (!(value instanceof Map<?, ?> rule))
                    continue;
                Object field = rule.get("field");
                boolean readonly = Boolean.TRUE.equals(rule.get("readonly"))
                        || Boolean.TRUE.equals(rule.get("disabled"))
                        || (rule.get("props") instanceof Map<?, ?> props
                                && (Boolean.TRUE.equals(props.get("readonly"))
                                        || Boolean.TRUE.equals(props.get("disabled"))));
                if (!readonly && field instanceof String name && !name.isBlank()) {
                    fields.add(editableFieldKey(subTableName, name));
                }
                Object children = rule.get("children");
                if (children instanceof List<?>) {
                    collectEditableHistoryFields(
                            Map.of("rule", children), subTableName, bindingHistoryNames, fields);
                }
            }
        }
        Object subForms = config.get("subForms");
        if (subForms instanceof Map<?, ?> forms) {
            for (Map.Entry<?, ?> entry : forms.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> form) {
                    String rawAlias = normalizeSubTableNameForHistory(String.valueOf(entry.getKey()));
                    String historyName = bindingHistoryNames.getOrDefault(rawAlias, rawAlias);
                    collectEditableHistoryFields(
                            (Map<String, Object>) form, historyName, bindingHistoryNames, fields);
                }
            }
        }
    }

    private static String editableFieldKey(String subTableName, String fieldName) {
        String tableName = normalizeSubTableNameForHistory(subTableName);
        return (tableName != null ? tableName : "") + "\u0000" + fieldName;
    }

    private record FormConfigRow(Long formId, String configJson) {
    }

    private record BindingHistoryNameRow(String bindingId, String tableName, String tableDisplayName) {
    }

    /**
     * Parsed form configs for a function unit code, memoised for {@link #FORM_CONFIG_TTL_MS}.
     * Returns the same content the callers previously parsed inline.
     */
    private List<Map<String, Object>> loadFormConfigs(String functionUnitCode) {
        String key = functionUnitCode.trim();
        CachedFormConfigs cached = formConfigCache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.configs();
        }
        List<String> configJsonStrings = jdbcTemplate.query(
                """
                        SELECT fd.config_json::text AS config_json
                        FROM dw_form_definitions fd
                        INNER JOIN dw_function_units fu ON fu.id = fd.function_unit_id
                        WHERE fu.code = ?
                        """,
                (rs, rowNum) -> rs.getString("config_json"),
                key);
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (String raw : configJsonStrings) {
            if (raw == null || raw.isBlank())
                continue;
            try {
                parsed.add(objectMapper.readValue(raw, new TypeReference<>() {
                }));
            } catch (Exception e) {
                log.debug("Could not parse configJson: {}", e.getMessage());
            }
        }
        formConfigCache.put(key, new CachedFormConfigs(parsed, System.currentTimeMillis()));
        return parsed;
    }

    /**
     * Resolve field labels from every form configJson for the function unit.
     * Walks the designer {@code rule} tree including nested layout {@code children}
     * (elCard / fcRow / col) and sub-form fields. A shallow top-level walk misses
     * the actual business fields and then {@link HistoryFieldMetadata#isUserVisible}
     * treats the incomplete map as an allow-list, hiding every remaining history row.
     */
    private Map<String, String> resolveFieldLabels(String processInstanceId) {
        Map<String, String> labels = new HashMap<>();
        try {
            ProcessInstance pi = processInstanceRepository.findById(processInstanceId).orElse(null);
            if (pi == null || pi.getProcessDefinitionKey() == null) {
                return labels;
            }
            for (Map<String, Object> config : loadFormConfigs(pi.getProcessDefinitionKey())) {
                try {
                    ChangeHistoryFormFieldLabelExtractor.walk(config, labels, null);
                } catch (Exception e) {
                    log.debug("Could not read configJson for field labels: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve field labels for {}: {}", processInstanceId, e.getMessage());
        }
        return labels;
    }

    private Map<String, Integer> resolveFieldOrders(String processInstanceId) {
        Map<String, Integer> orders = new HashMap<>();
        try {
            ProcessInstance pi = processInstanceRepository.findById(processInstanceId).orElse(null);
            if (pi == null || pi.getProcessDefinitionKey() == null) {
                return orders;
            }
            int[] counter = { 0 };
            for (Map<String, Object> config : loadFormConfigs(pi.getProcessDefinitionKey())) {
                try {
                    ChangeHistoryFormFieldLabelExtractor.walk(
                            config, null, new FieldMetaCollector(orders, counter)::accept);
                } catch (Exception e) {
                    log.debug("Could not read configJson for field orders: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve field orders for {}: {}", processInstanceId, e.getMessage());
        }
        return orders;
    }

    private record FieldMetaCollector(Map<String, Integer> fieldOrders, int[] counter) {
        void accept(String fieldName) {
            fieldOrders.putIfAbsent(fieldName, counter[0]++);
        }
    }

    // ==================== display helpers ====================
    /**
     * Converts a value to a user-friendly display string for change history.
     * Maps and Lists are serialized as JSON; scalars use {@code toString()}.
     */
    private String toDisplayString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        // Maps, Lists, and other complex objects → compact JSON
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return value.toString();
        }
    }

    /**
     * Normalizes a sub-table name from {@code __subTables__} for change history
     * recording.
     * Returns {@code null} when the key should be skipped (pure-numeric binding
     * IDs).
     * Otherwise returns the trimmed, lowercased name so that "People" and "people"
     * merge.
     */
    static String normalizeSubTableNameForHistory(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String trimmed = rawName.trim();
        // Skip pure-numeric keys in the general path — these are internal binding IDs
        // that
        // duplicate text-key aliases. Callers that need numeric-key support must bypass
        // this.
        if (trimmed.matches("\\d+")) {
            return null;
        }
        return trimmed.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    @SuppressWarnings("unchecked")
    static Map<String, List<Map<String, Object>>> normalizeSubTableRowsByHistoryName(Object subTablesObj) {
        if (!(subTablesObj instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, List<Map<String, Object>>> rowsByName = new LinkedHashMap<>();
        Map<String, Set<String>> seenRowIdsByTable = new HashMap<>();
        // Sort keys so old and new snapshots assign the same row to the same
        // normalized-name group regardless of HashMap iteration order.
        List<Map.Entry<?, ?>> sortedEntries = new ArrayList<>(rawMap.entrySet());
        sortedEntries.sort((a, b) -> {
            String ka = a.getKey() != null ? a.getKey().toString() : "";
            String kb = b.getKey() != null ? b.getKey().toString() : "";
            return ka.compareTo(kb);
        });
        for (Map.Entry<?, ?> entry : sortedEntries) {
            String normalizedName = normalizeSubTableNameForHistory(stringOrNull(entry.getKey()));
            if (normalizedName == null) {
                continue;
            }
            if (!(entry.getValue() instanceof List<?> rawRows)) {
                continue;
            }
            Set<String> seenRowIds = seenRowIdsByTable.computeIfAbsent(
                    normalizedName, ignored -> new HashSet<>());
            List<Map<String, Object>> rows = rowsByName.computeIfAbsent(normalizedName, ignored -> new ArrayList<>());
            for (Object rawRow : rawRows) {
                if (rawRow instanceof Map<?, ?> row) {
                    Map<String, Object> typedRow = (Map<String, Object>) row;
                    // Cross-key dedup: the same row can appear under multiple
                    // key aliases (binding ID, table name, normalized name).
                    // Keep only the first occurrence so diff output doesn't
                    // contain mirror records for each alias.
                    String rowId = resolveRowIdentifier(typedRow);
                    if (rowId != null && !seenRowIds.add(rowId)) {
                        continue;
                    }
                    rows.add(typedRow);
                }
            }
        }
        return rowsByName;
    }

    /**
     * Resolves the stable row identifier from a sub-table row map.
     * Delegates to {@link SubTableRowIdentity} so audit matching uses the same
     * priority as persist-time identity ({@code row_id} before {@code id}).
     * Rows with no identity return {@code null}; callers must not invent a key
     * from business field values.
     *
     * @param row the sub-table row map (never null)
     * @return a displayable row identifier, or {@code null}
     */
    public static String resolveRowIdentifier(Map<String, Object> row) {
        String field = SubTableRowIdentity.identityFieldOf(row);
        if (field == null) {
            return null;
        }
        Object value = SubTableRowKeySupport.getRowValueIgnoreCase(row, field);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    static boolean isSubTableRowMetadataField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return true;
        }
        return SUB_TABLE_ROW_METADATA_FIELDS.contains(normalizeFieldKey(fieldName));
    }

    private static boolean isLegacySystemFieldAlias(String fieldName) {
        return fieldName != null && LEGACY_SYSTEM_FIELD_ALIASES.contains(normalizeFieldKey(fieldName));
    }

    private static String normalizeFieldKey(String fieldName) {
        return fieldName.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    // ==================== global cross-process audit query ====================

    /**
     * Returns distinct FU codes that have at least one change history record,
     * with display names resolved from the admin-center FU catalog.
     * Each entry is a map with "code" and "name" keys.
     */
    public List<Map<String, String>> getDistinctFunctionUnitCodes() {
        List<String> codes = changeHistoryRepository.findDistinctFunctionUnitCodes();
        Set<String> codeSet = new HashSet<>(codes);
        Map<String, String> nameByCode = userPortalAuditEnricher.resolveFunctionUnitNames(codeSet);
        return codes.stream()
                .map(code -> {
                    Map<String, String> entry = new HashMap<>();
                    entry.put("code", code);
                    entry.put("name", nameByCode.get(code));
                    return entry;
                })
                .collect(Collectors.toList());
    }

    /**
     * Cross-process global audit log query with server-side pagination.
     * Does NOT resolve per-form field labels/orders — this is a summary view
     * designed for the admin audit list.  For per-field label resolution see
     * {@link #getChangeHistory(String)}.
     */
    public Page<UserPortalAuditRecord> queryGlobalAuditLogs(UserPortalAuditQueryRequest request) {
        int page = Math.max(request.getPage(), 0);
        int size = Math.min(Math.max(request.getSize(), 1), 200);
        Sort sort = buildAuditSort(request.getSortField(), request.getSortOrder());
        Pageable pageable = PageRequest.of(page, size, sort);

        // Resolve PI keyword once (outside Spec) so count+content do not double-query.
        List<String> matchingPiIds = resolveProcessInstanceFilter(request);
        Specification<ChangeHistory> spec = buildAuditSpecification(request, matchingPiIds);
        Page<ChangeHistory> entityPage = changeHistoryRepository.findAll(spec, pageable);
        List<ChangeHistory> entities = entityPage.getContent();

        Set<String> processInstanceIds = entities.stream()
                .map(ChangeHistory::getProcessInstanceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, ProcessInstance> piMap = new HashMap<>();
        if (!processInstanceIds.isEmpty()) {
            processInstanceRepository.findAllById(processInstanceIds)
                    .forEach(pi -> piMap.put(pi.getId(), pi));
        }

        List<UserPortalAuditRecord> records = userPortalAuditEnricher.toAuditRecords(entities, piMap);
        return new org.springframework.data.domain.PageImpl<>(
                records, pageable, entityPage.getTotalElements());
    }

    private List<String> resolveProcessInstanceFilter(UserPortalAuditQueryRequest request) {
        if (request.getProcessInstanceId() == null || request.getProcessInstanceId().isBlank()) {
            return null;
        }
        Instant start = parseIsoInstant(request.getStartTime());
        Instant end = parseIsoInstant(request.getEndTime());
        return processInstanceMatcher.resolveMatchingProcessInstanceIds(
                request.getProcessInstanceId(), start, end);
    }

    private Specification<ChangeHistory> buildAuditSpecification(
            UserPortalAuditQueryRequest request, List<String> preResolvedPiIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Always exclude internal fields + platform audit columns
            Predicate notInternal = cb.not(root.get("fieldName").in(INTERNAL_FIELD_BLACKLIST));
            Predicate notAudit = cb.not(root.get("fieldName").in(SystemAuditFields.ALL));
            predicates.add(cb.and(notInternal, notAudit));

            if (request.getUserId() != null && !request.getUserId().isBlank()) {
                predicates.add(cb.equal(root.get("userId"), request.getUserId().trim()));
            }

            if (request.getChangeType() != null && !request.getChangeType().isBlank()) {
                try {
                    ChangeType ct = ChangeType.valueOf(request.getChangeType().trim());
                    predicates.add(cb.equal(root.get("changeType"), ct));
                } catch (IllegalArgumentException ignored) {
                    // Invalid change type → empty result (do not silently drop the filter)
                    predicates.add(cb.disjunction());
                }
            }

            if (request.getStartTime() != null && !request.getStartTime().isBlank()) {
                Instant start = parseIsoInstant(request.getStartTime());
                if (start != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), start));
                }
            }

            if (request.getEndTime() != null && !request.getEndTime().isBlank()) {
                Instant end = parseIsoInstant(request.getEndTime());
                if (end != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), end));
                }
            }

            if (preResolvedPiIds != null) {
                if (preResolvedPiIds.isEmpty()) {
                    predicates.add(cb.disjunction()); // no match → empty result
                } else {
                    predicates.add(root.get("processInstanceId").in(preResolvedPiIds));
                }
            }

            // functionUnitCode filter requires sub-select on process_instance_id
            if (request.getFunctionUnitCode() != null && !request.getFunctionUnitCode().isBlank()) {
                List<String> matchingPiIds = jdbcTemplate.query(
                        "SELECT id FROM up_process_instance WHERE function_unit_code = ?",
                        (rs, rowNum) -> rs.getString("id"),
                        request.getFunctionUnitCode().trim());
                if (matchingPiIds.isEmpty()) {
                    predicates.add(cb.disjunction()); // no match → empty result
                } else {
                    predicates.add(root.get("processInstanceId").in(matchingPiIds));
                }
            }

            // username filter: resolve userIds from sys_users, then filter
            if (request.getUsername() != null && !request.getUsername().isBlank()) {
                String keyword = request.getUsername().trim().toLowerCase();
                String like = "%" + UserPortalAuditProcessInstanceMatcher.escapeLike(keyword) + "%";
                List<String> matchingUserIds = jdbcTemplate.query(
                        "SELECT id FROM sys_users WHERE LOWER(username) LIKE ? ESCAPE '\\' "
                                + "OR LOWER(full_name) LIKE ? ESCAPE '\\'",
                        (rs, rowNum) -> rs.getString("id"),
                        like, like);
                if (matchingUserIds.isEmpty()) {
                    predicates.add(cb.disjunction());
                } else {
                    predicates.add(root.get("userId").in(matchingUserIds));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Sort buildAuditSort(String sortField, String sortOrder) {
        String field = sortField != null && !sortField.isBlank() ? sortField : "timestamp";
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        // Map frontend sort field names to entity field names
        String entityField = switch (field) {
            case "createdAt", "timestamp" -> "timestamp";
            case "username", "userName", "userId" -> "userId";
            case "changeType" -> "changeType";
            default -> "timestamp";
        };
        return asc ? Sort.by(Sort.Direction.ASC, entityField)
                   : Sort.by(Sort.Direction.DESC, entityField);
    }

    private static Instant parseIsoInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(value));
        } catch (Exception e) {
            try {
                // Try without timezone, assume UTC
                return java.time.LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        .atZone(java.time.ZoneOffset.UTC)
                        .toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }
}