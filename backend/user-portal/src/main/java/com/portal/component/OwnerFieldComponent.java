package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.i18n.I18nService;
import com.portal.exception.PortalException;
import com.portal.service.UserDisplayNameResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server-side authority for Owner fields ({@code type:"owner"}, see
 * {@code docs/design/owner-field-component.md} §3.2/§3.3/§6.2/§6.3).
 *
 * <p>{@code CREATOR}: empty → first Save actor; a non-empty person is never
 * overwritten. {@code CASE_HANDLER} (legacy {@code CURRENT_ASSIGNEE}): in-progress
 * assignee / {@code step:} on MAIN during MI; Complete writes the actual actor.</p>
 */
@Slf4j
@Component
public class OwnerFieldComponent {

    static final String SUB_TABLES_KEY = "__subTables__";
    static final String CURRENT_ITEM_KEY = "_currentItem";
    static final String CURRENT_ITEM_ALIAS = "currentItem";
    public static final String DISPLAY_SUFFIX = "__display";
    static final String USER_PREFIX = OwnerCaseHandlerCalculator.USER_PREFIX;
    static final String STEP_PREFIX = OwnerCaseHandlerCalculator.STEP_PREFIX;
    static final String GROUP_PREFIX = "group:";
    static final String GROUP_SEPARATOR = "|";
    static final String GROUP_DISPLAY_SEPARATOR = " / ";
    static final String SOURCE_CREATOR = OwnerCaseHandlerCalculator.SOURCE_CREATOR;
    static final String SOURCE_CASE_HANDLER = OwnerCaseHandlerCalculator.SOURCE_CASE_HANDLER;

    private final JdbcTemplate jdbcTemplate;
    private final UserDisplayNameResolver userDisplayNameResolver;
    private final I18nService i18nService;
    private final OwnerFieldMetadataCatalog metadataCatalog;

    public OwnerFieldComponent(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                               UserDisplayNameResolver userDisplayNameResolver, I18nService i18nService,
                               PortalPrimaryKeyAllocationComponent portalPrimaryKeyAllocationComponent) {
        this.jdbcTemplate = jdbcTemplate;
        this.userDisplayNameResolver = userDisplayNameResolver;
        this.i18nService = i18nService;
        this.metadataCatalog = new OwnerFieldMetadataCatalog(
                jdbcTemplate, objectMapper, i18nService, portalPrimaryKeyAllocationComponent);
    }

    /**
     * Submit-path context. {@code previousVariables} is the map before client merge
     * (null on process start) so Creator ignores client person changes.
     */
    public record OwnerWriteContext(
            String actorUserId,
            String startUserId,
            String assigneeUserId,
            String candidateUserIds,
            Map<String, Object> previousVariables,
            MiOuterStepResolver.OuterLookup miOuterLookup
    ) {
        public OwnerWriteContext(String actorUserId, String startUserId, String assigneeUserId,
                                 String candidateUserIds, Map<String, Object> previousVariables) {
            this(actorUserId, startUserId, assigneeUserId, candidateUserIds, previousVariables,
                    MiOuterStepResolver.OuterLookup.known(null));
        }
    }

    /** MAIN-table Owner field names grouped by source, for View live projection. */
    public record OwnerViewHints(Set<String> creatorFields, Set<String> caseHandlerFields) {
        public static final OwnerViewHints EMPTY = new OwnerViewHints(Set.of(), Set.of());

        public boolean isEmpty() {
            return creatorFields.isEmpty() && caseHandlerFields.isEmpty();
        }
    }

    /**
     * Validates and fills Owner fields in {@code variables} (mutated in place).
     */
    public void applyOnSubmit(String functionUnitIdOrCode, OwnerWriteContext ctx, Map<String, Object> variables) {
        FuOwnerFields metadata = metadataCatalog.loadIfPresent(functionUnitIdOrCode, variables);
        if (metadata.isEmpty() || ctx == null) {
            return;
        }
        for (OwnerFieldMeta meta : metadata.mainFields()) {
            applyToRecord(variables, meta, ctx, true, ctx.previousVariables());
        }
        applyToSubTableRows(variables, metadata, ctx);
    }

    /**
     * Write-path for task lifecycle: overwrite MAIN {@code CASE_HANDLER} from the
     * in-progress rule (people or {@code step:}). Matched MI sub-rows only.
     * Does not touch Creator columns.
     */
    public void applyAssigneeSnapshot(String functionUnitIdOrCode, Map<String, Object> variables,
                                      String assigneeUserId, String candidateUserIds) {
        applyAssigneeSnapshot(functionUnitIdOrCode, variables, assigneeUserId, candidateUserIds,
                null, MiOuterStepResolver.OuterLookup.known(null));
    }

    public void applyAssigneeSnapshot(String functionUnitIdOrCode, Map<String, Object> variables,
                                      String assigneeUserId, String candidateUserIds,
                                      String processStatus, String miOuterName) {
        applyAssigneeSnapshot(functionUnitIdOrCode, variables, assigneeUserId, candidateUserIds,
                processStatus, MiOuterStepResolver.OuterLookup.known(miOuterName));
    }

    public void applyAssigneeSnapshot(String functionUnitIdOrCode, Map<String, Object> variables,
                                      String assigneeUserId, String candidateUserIds,
                                      String processStatus, MiOuterStepResolver.OuterLookup lookup) {
        FuOwnerFields metadata = metadataCatalog.loadIfPresent(functionUnitIdOrCode, variables);
        if (metadata.isEmpty()) {
            return;
        }
        OwnerWriteContext ctx = new OwnerWriteContext(null, null, assigneeUserId, candidateUserIds, null);
        if (lookup == null || lookup.resolved()) {
            String miOuter = lookup == null ? null : lookup.outerName();
            String mainValue = OwnerCaseHandlerCalculator.mainInProgress(
                    processStatus, miOuter, assigneeUserId, candidateUserIds);
            for (OwnerFieldMeta meta : metadata.mainFields()) {
                if (OwnerCaseHandlerCalculator.isCaseHandler(meta.source())) {
                    writeStored(variables, meta, mainValue);
                }
            }
        }
        applyHandlerToMatchedSubRows(variables, metadata, ctx, false);
    }

    /** Complete: ordinary MAIN writes the actor; inner MI MAIN stays {@code step:}. */
    public void applyOnComplete(String functionUnitIdOrCode, Map<String, Object> variables, String actorUserId) {
        applyOnComplete(functionUnitIdOrCode, variables, actorUserId,
                MiOuterStepResolver.OuterLookup.known(null));
    }

    public void applyOnComplete(String functionUnitIdOrCode, Map<String, Object> variables,
                                String actorUserId, String miOuterName) {
        applyOnComplete(functionUnitIdOrCode, variables, actorUserId,
                MiOuterStepResolver.OuterLookup.known(miOuterName));
    }

    public void applyOnComplete(String functionUnitIdOrCode, Map<String, Object> variables,
                                String actorUserId, MiOuterStepResolver.OuterLookup lookup) {
        FuOwnerFields metadata = metadataCatalog.loadIfPresent(functionUnitIdOrCode, variables);
        if (metadata.isEmpty() || actorUserId == null || actorUserId.isBlank()) {
            return;
        }
        OwnerWriteContext ctx = new OwnerWriteContext(actorUserId, null, actorUserId, null, null);
        if (lookup == null || lookup.resolved()) {
            String miOuter = lookup == null ? null : lookup.outerName();
            String mainValue = OwnerCaseHandlerCalculator.mainOnComplete(miOuter, actorUserId);
            for (OwnerFieldMeta meta : metadata.mainFields()) {
                if (OwnerCaseHandlerCalculator.isCaseHandler(meta.source())) {
                    writeStored(variables, meta, mainValue);
                }
            }
        }
        applyHandlerToMatchedSubRows(variables, metadata, ctx, true);
    }

    /** Terminal / no further user task: clear MAIN Case Handler only. */
    public void clearMainCaseHandler(String functionUnitIdOrCode, Map<String, Object> variables) {
        FuOwnerFields metadata = metadataCatalog.loadIfPresent(functionUnitIdOrCode, variables);
        if (metadata.isEmpty()) {
            return;
        }
        for (OwnerFieldMeta meta : metadata.mainFields()) {
            if (OwnerCaseHandlerCalculator.isCaseHandler(meta.source())) {
                writeStored(variables, meta, "");
            }
        }
    }

    /** Copy Owner columns (and {@code __display}) into a Complete snapshot subset. */
    public void copyOwnerValuesIntoSnapshot(String functionUnitIdOrCode,
                                            Map<String, Object> source, Map<String, Object> snapshotFields) {
        FuOwnerFields metadata = metadataCatalog.loadIfPresent(functionUnitIdOrCode, source);
        if (metadata.isEmpty() || source == null || snapshotFields == null) {
            return;
        }
        for (OwnerFieldMeta meta : metadata.mainFields()) {
            OwnerFieldRowSupport.copyIfPresent(source, snapshotFields, meta.field());
            OwnerFieldRowSupport.copyIfPresent(source, snapshotFields, meta.field() + DISPLAY_SUFFIX);
        }
        if (source.get(SUB_TABLES_KEY) instanceof Map<?, ?> slices) {
            snapshotFields.putIfAbsent(SUB_TABLES_KEY, slices);
        }
    }

    /**
     * Read-path: refresh {@code __display} from this Owner column's stored IDs.
     * Does not overlay system Current Assignee columns. Does not persist.
     */
    public void projectForRead(String functionUnitIdOrCode, OwnerWriteContext ctx, Map<String, Object> variables) {
        FuOwnerFields metadata = metadataCatalog.loadIfPresent(functionUnitIdOrCode, variables);
        if (metadata.isEmpty() || ctx == null) {
            return;
        }
        for (OwnerFieldMeta meta : metadata.mainFields()) {
            refreshStoredDisplay(variables, meta);
        }
        projectSubRowDisplays(variables, metadata);
    }

    /** Execution-scoped MI loop variable; never persist on process-wide variables. */
    public static Object taskScopedCurrentItem(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Object item = source.get(CURRENT_ITEM_KEY);
        if (!(item instanceof Map)) {
            item = source.get(CURRENT_ITEM_ALIAS);
        }
        return item instanceof Map ? item : null;
    }

    public static void stripProcessWideCurrentItem(Map<String, Object> variables) {
        if (variables == null) {
            return;
        }
        variables.remove(CURRENT_ITEM_KEY);
        variables.remove(CURRENT_ITEM_ALIAS);
    }

    public OwnerViewHints viewHints(String functionUnitIdOrCode) {
        FuOwnerFields metadata = metadataCatalog.loadIfPresent(functionUnitIdOrCode);
        if (metadata.isEmpty()) {
            return OwnerViewHints.EMPTY;
        }
        Set<String> creator = new LinkedHashSet<>();
        Set<String> handlers = new LinkedHashSet<>();
        for (OwnerFieldMeta meta : metadata.mainFields()) {
            if (OwnerCaseHandlerCalculator.isCaseHandler(meta.source())) {
                handlers.add(meta.field());
            } else {
                creator.add(meta.field());
            }
        }
        return new OwnerViewHints(Set.copyOf(creator), Set.copyOf(handlers));
    }

    @SuppressWarnings("unchecked")
    private void applyToSubTableRows(Map<String, Object> variables, FuOwnerFields metadata, OwnerWriteContext ctx) {
        if (metadata.subFieldsBySliceKey().isEmpty()
                || !(variables.get(SUB_TABLES_KEY) instanceof Map<?, ?> rawSlices)) {
            return;
        }
        Map<String, Object> previousSlices = OwnerFieldRowSupport.previousSubSlices(ctx.previousVariables());
        for (Map.Entry<String, Object> slice : ((Map<String, Object>) rawSlices).entrySet()) {
            List<OwnerFieldMeta> metas = metadata.identify(slice.getKey());
            if (metas == null || metas.isEmpty() || !(slice.getValue() instanceof List<?> rows)) {
                continue;
            }
            List<Map<String, Object>> previousRows = OwnerFieldRowSupport.previousSliceRows(previousSlices, slice.getKey());
            List<String> pkFields = metadataCatalog.designerPrimaryKeyFieldsForSlice(slice.getKey());
            for (Object row : rows) {
                if (row instanceof Map<?, ?> rowMap) {
                    Map<String, Object> typed = (Map<String, Object>) rowMap;
                    Map<String, Object> previousRow = OwnerFieldRowSupport.matchPreviousRow(previousRows, typed, pkFields);
                    for (OwnerFieldMeta meta : metas) {
                        if (OwnerCaseHandlerCalculator.isCaseHandler(meta.source())) {
                            if (OwnerFieldRowSupport.rowMatchesCurrentItem(typed, variables, pkFields)) {
                                writeAssignee(typed, meta, ctx);
                            }
                            continue;
                        }
                        applyToRecord(typed, meta, ctx, false, previousRow);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyHandlerToMatchedSubRows(Map<String, Object> variables, FuOwnerFields metadata,
                                              OwnerWriteContext ctx, boolean complete) {
        if (metadata.subFieldsBySliceKey().isEmpty()
                || !(variables.get(SUB_TABLES_KEY) instanceof Map<?, ?> rawSlices)) {
            return;
        }
        String stored = complete
                ? OwnerCaseHandlerCalculator.actorValue(ctx.actorUserId())
                : OwnerCaseHandlerCalculator.peopleValue(ctx.assigneeUserId(), ctx.candidateUserIds());
        if (stored.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> slice : ((Map<String, Object>) rawSlices).entrySet()) {
            List<OwnerFieldMeta> metas = metadata.identify(slice.getKey());
            if (metas == null || metas.isEmpty() || !(slice.getValue() instanceof List<?> rows)) {
                continue;
            }
            List<String> pkFields = metadataCatalog.designerPrimaryKeyFieldsForSlice(slice.getKey());
            for (Object row : rows) {
                if (row instanceof Map<?, ?> rowMap) {
                    Map<String, Object> typed = (Map<String, Object>) rowMap;
                    if (!OwnerFieldRowSupport.rowMatchesCurrentItem(typed, variables, pkFields)) {
                        continue;
                    }
                    for (OwnerFieldMeta meta : metas) {
                        if (OwnerCaseHandlerCalculator.isCaseHandler(meta.source())) {
                            writeStored(typed, meta, stored);
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void projectSubRowDisplays(Map<String, Object> variables, FuOwnerFields metadata) {
        if (metadata.subFieldsBySliceKey().isEmpty()
                || !(variables.get(SUB_TABLES_KEY) instanceof Map<?, ?> rawSlices)) {
            return;
        }
        for (Map.Entry<String, Object> slice : ((Map<String, Object>) rawSlices).entrySet()) {
            List<OwnerFieldMeta> metas = metadata.identify(slice.getKey());
            if (metas == null || metas.isEmpty() || !(slice.getValue() instanceof List<?> rows)) {
                continue;
            }
            for (Object row : rows) {
                if (row instanceof Map<?, ?> rowMap) {
                    for (OwnerFieldMeta meta : metas) {
                        refreshStoredDisplay((Map<String, Object>) rowMap, meta);
                    }
                }
            }
        }
    }

    private void applyToRecord(Map<String, Object> record, OwnerFieldMeta meta, OwnerWriteContext ctx,
                               boolean mainTable, Map<String, Object> previousRecord) {
        if (OwnerCaseHandlerCalculator.isCaseHandler(meta.source())) {
            if (mainTable) {
                applyMainHandlerOnSubmit(record, meta, ctx, previousRecord);
                return;
            }
            writeAssignee(record, meta, ctx);
            return;
        }
        writeCreator(record, meta, ctx.actorUserId(), previousRecord);
    }

    /**
     * Submit is not the MAIN Case Handler write point: keep previous / MI {@code step:},
     * never the current-task assignee (that would paint an inner MI person onto MAIN).
     */
    private void applyMainHandlerOnSubmit(Map<String, Object> record, OwnerFieldMeta meta,
                                          OwnerWriteContext ctx, Map<String, Object> previousRecord) {
        MiOuterStepResolver.OuterLookup lookup = ctx.miOuterLookup() == null
                ? MiOuterStepResolver.OuterLookup.known(null)
                : ctx.miOuterLookup();
        if (lookup.isInner()) {
            writeStored(record, meta, OwnerCaseHandlerCalculator.stepValue(lookup.outerName()));
            return;
        }
        String previous = OwnerFieldRowSupport.scalar(previousRecord, meta.field());
        if (OwnerCaseHandlerCalculator.isStepValue(previous)
                || OwnerFieldRowSupport.isStoredOwnerValue(previous)) {
            record.put(meta.field(), previous);
            refreshStoredDisplay(record, meta);
            return;
        }
        if (lookup.isUnknown()
                && OwnerCaseHandlerCalculator.isStepValue(OwnerFieldRowSupport.scalar(record, meta.field()))) {
            refreshStoredDisplay(record, meta);
            return;
        }
        writeStored(record, meta, "");
    }

    private void writeCreator(Map<String, Object> record, OwnerFieldMeta meta, String fillUserId,
                              Map<String, Object> previousRecord) {
        String previous = OwnerFieldRowSupport.scalar(previousRecord, meta.field());
        if (OwnerFieldRowSupport.isStoredOwnerValue(previous)) {
            record.put(meta.field(), previous);
            record.put(meta.field() + DISPLAY_SUFFIX, displayForStored(meta, previous));
            return;
        }
        if (fillUserId == null || fillUserId.isBlank()) {
            return;
        }
        String display = userDisplayNameResolver.resolveIfExists(fillUserId)
                .orElseThrow(() -> ownerError("portal.owner.user_not_found", fillUserId));
        record.put(meta.field(), USER_PREFIX + fillUserId);
        record.put(meta.field() + DISPLAY_SUFFIX, display);
    }

    private void refreshStoredDisplay(Map<String, Object> record, OwnerFieldMeta meta) {
        String value = OwnerFieldRowSupport.scalar(record, meta.field());
        if (OwnerCaseHandlerCalculator.isStepValue(value)) {
            record.put(meta.field() + DISPLAY_SUFFIX, value.substring(STEP_PREFIX.length()));
            return;
        }
        if (value.startsWith(GROUP_PREFIX)) {
            record.put(meta.field() + DISPLAY_SUFFIX, displayForLeftoverGroup(value));
            return;
        }
        List<String> ids = parseStoredUserIds(value);
        if (ids.isEmpty()) {
            return;
        }
        Map<String, String> cache = userDisplayNameResolver.resolveBatch(Set.copyOf(ids));
        String display = namesForUserIds(ids, cache);
        if (!display.isBlank()) {
            record.put(meta.field() + DISPLAY_SUFFIX, display);
        }
    }

    private void writeAssignee(Map<String, Object> record, OwnerFieldMeta meta, OwnerWriteContext ctx) {
        writeStored(record, meta, OwnerCaseHandlerCalculator.peopleValue(
                ctx.assigneeUserId(), ctx.candidateUserIds()));
    }

    private void writeStored(Map<String, Object> record, OwnerFieldMeta meta, String stored) {
        if (stored == null || stored.isBlank()) {
            record.put(meta.field(), "");
            record.remove(meta.field() + DISPLAY_SUFFIX);
            return;
        }
        record.put(meta.field(), stored);
        if (OwnerCaseHandlerCalculator.isStepValue(stored)) {
            record.put(meta.field() + DISPLAY_SUFFIX, stored.substring(STEP_PREFIX.length()));
            return;
        }
        List<String> ids = parseStoredUserIds(stored);
        if (ids.isEmpty()) {
            record.remove(meta.field() + DISPLAY_SUFFIX);
            return;
        }
        Map<String, String> cache = userDisplayNameResolver.resolveBatch(Set.copyOf(ids));
        String display = namesForUserIds(ids, cache);
        if (!display.isBlank()) {
            record.put(meta.field() + DISPLAY_SUFFIX, display);
        } else {
            record.remove(meta.field() + DISPLAY_SUFFIX);
        }
    }

    private String namesForUserIds(List<String> ids, Map<String, String> cache) {
        return ids.stream()
                .map(id -> userDisplayNameResolver.resolveCached(id, cache))
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(UserDisplayNameResolver.MULTI_ASSIGNEE_DISPLAY_SEPARATOR));
    }

    static String joinStoredUserValues(List<String> ids) {
        return OwnerFieldRowSupport.joinStoredUserValues(ids);
    }

    /**
     * Parses {@code user:<id>} or {@code user:<id1>,user:<id2>} into user ids.
     * Group leftovers and blank values return an empty list.
     */
    public static List<String> parseStoredUserIds(String value) {
        return OwnerFieldRowSupport.parseStoredUserIds(value);
    }

    private String displayForStored(OwnerFieldMeta meta, String value) {
        if (value.startsWith(GROUP_PREFIX)) {
            return displayForLeftoverGroup(value);
        }
        List<String> ids = parseStoredUserIds(value);
        if (ids.isEmpty()) {
            throw ownerError("portal.owner.invalid_format", meta.field());
        }
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            names.add(userDisplayNameResolver.resolveIfExists(id)
                    .orElseThrow(() -> ownerError("portal.owner.user_not_found", id)));
        }
        return String.join(UserDisplayNameResolver.MULTI_ASSIGNEE_DISPLAY_SEPARATOR, names);
    }

    private String displayForLeftoverGroup(String value) {
        String rest = value.substring(GROUP_PREFIX.length());
        int sep = rest.indexOf(GROUP_SEPARATOR);
        if (sep <= 0 || sep != rest.lastIndexOf(GROUP_SEPARATOR) || sep == rest.length() - 1) {
            return value;
        }
        String buName = queryNameByCode("sys_business_units", rest.substring(0, sep).trim());
        String roleName = queryNameByCode("sys_roles", rest.substring(sep + 1).trim());
        if (buName == null || roleName == null) {
            return value;
        }
        return buName + GROUP_DISPLAY_SEPARATOR + roleName;
    }

    private String queryNameByCode(String table, String code) {
        List<String> names = jdbcTemplate.query(
                "SELECT name FROM " + table + " WHERE code = ?",
                (rs, rowNum) -> rs.getString(1),
                code);
        return names.isEmpty() ? null : names.get(0);
    }

    private PortalException ownerError(String messageKey, String arg) {
        return new PortalException("400", i18nService.getMessage(messageKey, arg));
    }
}
