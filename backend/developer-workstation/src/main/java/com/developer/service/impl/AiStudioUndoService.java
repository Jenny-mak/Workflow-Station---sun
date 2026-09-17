package com.developer.service.impl;

import com.developer.component.EmailConnectionComponent;
import com.developer.component.EmailMonitorRuleComponent;
import com.developer.component.EmailTemplateComponent;
import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiStudioApplyResponse.UndoNote;
import com.developer.dto.AiStudioApplyResponse.UndoOutcome;
import com.developer.dto.EmailMonitorRuleRequest;
import com.developer.dto.EmailTemplateRequest;
import com.developer.dto.MainTableViewDtos.MainTableViewAccessRuleDTO;
import com.developer.dto.MainTableViewDtos.MainTableViewDTO;
import com.developer.dto.MainTableViewDtos.MainTableViewFieldDTO;
import com.developer.dto.MainTableViewDtos.UpdateMainTableViewRequest;
import com.developer.entity.EmailConnection;
import com.developer.entity.EmailMonitorRule;
import com.developer.entity.EmailTemplate;
import com.developer.entity.MainTableViewConfig;
import com.developer.entity.ProcessDefinition;
import com.developer.entity.TableDefinition;
import com.developer.entity.TableRelation;
import com.developer.enums.EmailConnectionDirection;
import com.developer.enums.EmailMonitorActionType;
import com.developer.exception.AiGenerationException;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;

import com.developer.repository.MainTableViewConfigRepository;
import com.developer.repository.ProcessDefinitionRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.service.AiWriteService;
import com.developer.service.MainTableViewService;
import com.developer.util.BpmnServiceTaskBindingPatcher;
import com.developer.util.BpmnServiceTaskScanner;
import com.developer.util.XmlEncodingUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AI Studio 的 Apply 撤销：在写入前记下"会被动到的东西的原样"，撤销时逐项逆操作。
 *
 * <p><b>为什么不是全量快照回放</b>：AI Generate 的 undo 拍 6 切片快照再以 {@code scope=ALL} 重放，
 * 那会清空整个功能单元重建；对"只改了一个邮件模板"来说既过重，也会把视图、绑定、邮件一起冲掉。
 * 这里改为精确逆操作，只动本次 Apply 真正写过的对象。</p>
 *
 * <p><b>为什么不覆盖全部 scope</b>：TABLES / FORMS / ACTIONS / DECISIONS 的写入是"清空再建"，
 * 重建后数据库 id 会变，而表单绑定、BPMN 里的 formId / actionIds / subTableId 都按 id 引用
 * （见 {@code AiWriteServiceImpl#clearTableGraph} 与 {@code AiBpmnFormBindingWriter}）。
 * 只还原其中一个切片会留下断链，比不撤销更糟，因此这些 scope 不发撤销令牌，改由前端在 Apply 前二次确认。</p>
 *
 * <p>快照存在进程内，带 TTL；DW 只在 dev 单实例部署，重启即丢，前端会拿到"窗口已过"。</p>
 */
@Slf4j
@Service
public class AiStudioUndoService {

    /** 可精确逆操作的 scope；其余 scope 不发令牌 */
    private static final java.util.Set<String> UNDOABLE_SCOPES = java.util.Set.of(
            "EMAIL_TEMPLATES", "CONNECTIONS", "EMAIL_MONITORS", "VIEWS", "SERVICE_TASK_BINDINGS",
            "PROCESS", "TABLE_RELATIONS");

    private final Map<String, UndoEntry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-studio-undo-cleanup");
        t.setDaemon(true);
        return t;
    });
    private final Duration ttl;

    private final EmailTemplateComponent emailTemplateComponent;
    private final EmailConnectionComponent emailConnectionComponent;
    private final EmailMonitorRuleComponent emailMonitorRuleComponent;
    private final MainTableViewService mainTableViewService;
    private final AiWriteService aiWriteService;
    private final EmailTemplateRepository emailTemplateRepository;
    private final EmailConnectionRepository emailConnectionRepository;
    private final EmailMonitorRuleRepository emailMonitorRuleRepository;
    private final MainTableViewConfigRepository mainTableViewConfigRepository;
    private final TableDefinitionRepository tableDefinitionRepository;
    private final TableRelationRepository tableRelationRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;

    public AiStudioUndoService(@Value("${ai-generation.studio.undo-ttl-minutes:10}") long ttlMinutes,
                               EmailTemplateComponent emailTemplateComponent,
                               EmailConnectionComponent emailConnectionComponent,
                               EmailMonitorRuleComponent emailMonitorRuleComponent,
                               MainTableViewService mainTableViewService,
                               AiWriteService aiWriteService,
                               EmailTemplateRepository emailTemplateRepository,
                               EmailConnectionRepository emailConnectionRepository,
                               EmailMonitorRuleRepository emailMonitorRuleRepository,
                               MainTableViewConfigRepository mainTableViewConfigRepository,
                               TableDefinitionRepository tableDefinitionRepository,
                               TableRelationRepository tableRelationRepository,
                               ProcessDefinitionRepository processDefinitionRepository) {
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.emailTemplateComponent = emailTemplateComponent;
        this.emailConnectionComponent = emailConnectionComponent;
        this.emailMonitorRuleComponent = emailMonitorRuleComponent;
        this.mainTableViewService = mainTableViewService;
        this.aiWriteService = aiWriteService;
        this.emailTemplateRepository = emailTemplateRepository;
        this.emailConnectionRepository = emailConnectionRepository;
        this.emailMonitorRuleRepository = emailMonitorRuleRepository;
        this.mainTableViewConfigRepository = mainTableViewConfigRepository;
        this.tableDefinitionRepository = tableDefinitionRepository;
        this.tableRelationRepository = tableRelationRepository;
        this.processDefinitionRepository = processDefinitionRepository;
    }

    @PreDestroy
    void shutdown() {
        cleanup.shutdownNow();
    }

    public static boolean isUndoable(String scope) {
        return UNDOABLE_SCOPES.contains(scope);
    }

    /** 整片还原的 scope → generatedData 切片 key（撤销说明用） */
    private static final Map<String, String> WHOLE_SLICE_BY_SCOPE = Map.of(
            "PROCESS", "processDefinition",
            "TABLE_RELATIONS", "tableRelations");

    /** 一条"被本次 Apply 动过的对象"的原样；{@code before} 为 null 表示这个对象原本不存在（撤销时删掉）。 */
    private record ItemUndo(String slice, String key, Map<String, Object> before) {}

    private record UndoEntry(String token, Long functionUnitId, String userId, String scope, Instant expiresAt,
                             List<ItemUndo> items, AiGeneratedData sliceBefore) {}

    /**
     * 写入<b>之前</b>调用：读下会被动到的对象的原样。返回 null 表示该 scope 不支持撤销。
     *
     * <p>快照失败不该挡住 Apply——记 warn 返回 null，用户只是没有撤销按钮。</p>
     */
    @Transactional(readOnly = true)
    public String capture(Long functionUnitId, String userId, String scope, AiGeneratedData data) {
        if (!isUndoable(scope)) {
            return null;
        }
        try {
            List<ItemUndo> items = new ArrayList<>();
            AiGeneratedData sliceBefore = null;
            switch (scope) {
                case "EMAIL_TEMPLATES" -> captureTemplates(functionUnitId, data, items);
                case "CONNECTIONS" -> captureConnections(functionUnitId, data, items);
                case "EMAIL_MONITORS" -> captureMonitors(functionUnitId, data, items);
                case "VIEWS" -> captureViews(functionUnitId, data, items);
                case "SERVICE_TASK_BINDINGS" -> captureBindings(functionUnitId, data, items);
                case "PROCESS" -> sliceBefore = AiGeneratedData.builder()
                        .processDefinition(currentProcess(functionUnitId)).build();
                case "TABLE_RELATIONS" -> sliceBefore = AiGeneratedData.builder()
                        .tableRelations(currentRelations(functionUnitId)).build();
                default -> { return null; }
            }
            if (items.isEmpty() && sliceBefore == null) {
                return null;
            }
            String token = UUID.randomUUID().toString();
            UndoEntry entry = new UndoEntry(token, functionUnitId, userId, scope,
                    Instant.now().plus(ttl), items, sliceBefore);
            // 同一个功能单元只留最近一次：旧令牌作废，避免撤销到更早的状态
            entries.values().removeIf(e -> e.functionUnitId().equals(functionUnitId));
            entries.put(token, entry);
            cleanup.schedule(() -> entries.remove(token), ttl.toMinutes(), TimeUnit.MINUTES);
            log.info("AI Studio undo captured: token={}, functionUnitId={}, scope={}, items={}",
                    token, functionUnitId, scope, items.size());
            return token;
        } catch (RuntimeException e) {
            log.warn("AI Studio undo snapshot failed for functionUnitId={} scope={}; apply continues without undo: {}",
                    functionUnitId, scope, e.getMessage(), e);
            return null;
        }
    }

    /** Apply 失败时丢弃刚拍的快照。 */
    public void discard(String token) {
        if (token != null) {
            entries.remove(token);
        }
    }

    /** 令牌对应的功能单元；不存在/非本人 → AI_STUDIO_UNDO_EXPIRED（不区分，避免枚举他人令牌）。 */
    public Long functionUnitOf(String token, String userId) {
        UndoEntry entry = token == null ? null : entries.get(token);
        if (entry == null || !entry.userId().equals(userId)) {
            throw new AiGenerationException("AI_STUDIO_UNDO_EXPIRED",
                    "The undo window for this proposal has expired");
        }
        return entry.functionUnitId();
    }

    public Instant expiryOf(String token) {
        UndoEntry entry = token == null ? null : entries.get(token);
        return entry != null ? entry.expiresAt() : null;
    }

    /**
     * 撤销：逐项逆操作。令牌用完即删（重复撤销 → 窗口已过）。
     *
     * @return 每项的处理结果（结构化，前端按 outcome 翻译），含被跳过的项与原因
     */
    @Transactional
    public List<UndoNote> undo(String token, String userId) {
        // 先校验再摘除：拿错身份调一次不该把令牌持有者的撤销机会一起销毁
        UndoEntry entry = token == null ? null : entries.get(token);
        if (entry == null || !entry.userId().equals(userId) || entry.expiresAt().isBefore(Instant.now())) {
            throw new AiGenerationException("AI_STUDIO_UNDO_EXPIRED",
                    "The undo window for this proposal has expired");
        }
        entries.remove(token);
        List<UndoNote> notes = new ArrayList<>();
        Long fuId = entry.functionUnitId();
        if (entry.sliceBefore() != null) {
            // 整片还原：scoped clear + 重写，与 Apply 走同一条写入路径
            aiWriteService.applyGeneratedData(fuId, entry.sliceBefore(), entry.scope());
            notes.add(note(WHOLE_SLICE_BY_SCOPE.getOrDefault(entry.scope(), entry.scope()), null, UndoOutcome.RESTORED, null));
        }
        for (ItemUndo item : entry.items()) {
            try {
                notes.add(undoItem(fuId, item));
            } catch (RuntimeException e) {
                // 单项失败不该让整次撤销回滚——其它项已经还原了，如实报告这一项
                log.warn("AI Studio undo item failed: functionUnitId={}, slice={}, key={}: {}",
                        fuId, item.slice(), item.key(), e.getMessage());
                notes.add(note(item.slice(), displayName(item), UndoOutcome.FAILED, e.getMessage()));
            }
        }
        log.info("AI Studio undo applied: token={}, functionUnitId={}, scope={}, notes={}",
                token, fuId, entry.scope(), notes.size());
        return notes;
    }

    private UndoNote undoItem(Long fuId, ItemUndo item) {
        return switch (item.slice()) {
            case "emailTemplates" -> undoTemplate(fuId, item);
            case "emailConnections" -> undoConnection(fuId, item);
            case "emailMonitorRules" -> undoMonitor(fuId, item);
            case "mainTableViews" -> undoView(fuId, item);
            case "serviceTaskBindings" -> undoBinding(fuId, item);
            default -> note(item.slice(), displayName(item), UndoOutcome.UNSUPPORTED, null);
        };
    }

    // ---- 邮件模板 ----

    private void captureTemplates(Long fuId, AiGeneratedData data, List<ItemUndo> items) {
        if (data.getEmailTemplates() == null) return;
        Map<String, EmailTemplate> existing = new HashMap<>();
        for (EmailTemplate t : emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(fuId)) {
            existing.put(t.getName(), t);
        }
        for (Map<String, Object> proposed : data.getEmailTemplates()) {
            String name = str(proposed.get("name"));
            EmailTemplate current = existing.get(name);
            Map<String, Object> before = null;
            if (current != null) {
                before = new LinkedHashMap<>();
                before.put("name", current.getName());
                before.put("subject", current.getSubject());
                before.put("bodyHtml", current.getBodyHtml());
                before.put("enabled", current.getEnabled());
            }
            items.add(new ItemUndo("emailTemplates", name, before));
        }
    }

    private UndoNote undoTemplate(Long fuId, ItemUndo item) {
        EmailTemplate current = emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(fuId).stream()
                .filter(t -> item.key().equals(t.getName())).findFirst().orElse(null);
        if (current == null) {
            return note(item, UndoOutcome.SKIPPED_GONE);
        }
        if (item.before() == null) {
            emailTemplateComponent.delete(fuId, current.getId());
            return note(item, UndoOutcome.DELETED);
        }
        EmailTemplateRequest request = new EmailTemplateRequest();
        request.setName(str(item.before().get("name")));
        request.setSubject(str(item.before().get("subject")));
        request.setBodyHtml(str(item.before().get("bodyHtml")));
        request.setEnabled(item.before().get("enabled") instanceof Boolean b ? b : Boolean.TRUE);
        emailTemplateComponent.update(fuId, current.getId(), request);
        return note(item, UndoOutcome.RESTORED);
    }

    // ---- 邮件连接 ----

    private void captureConnections(Long fuId, AiGeneratedData data, List<ItemUndo> items) {
        if (data.getEmailConnections() == null) return;
        Map<String, EmailConnection> existing = new HashMap<>();
        for (EmailConnection c : emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(fuId)) {
            existing.put(connectionKey(c.getName(), c.getDirection()), c);
        }
        for (Map<String, Object> proposed : data.getEmailConnections()) {
            String name = str(proposed.get("name"));
            EmailConnectionDirection direction = proposed.get("direction") instanceof String d && !d.isBlank()
                    ? EmailConnectionDirection.valueOf(d) : EmailConnectionDirection.OUTBOUND;
            EmailConnection current = existing.get(connectionKey(name, direction));
            Map<String, Object> before = null;
            if (current != null) {
                before = new LinkedHashMap<>();
                // 与 AiEmailProposalWriter 对称：Apply 只动这四个显示字段，撤销也只还原它们，绝不碰凭证
                before.put("connectionType", current.getConnectionType() != null ? current.getConnectionType().name() : null);
                before.put("fromName", current.getFromName());
                before.put("mailboxAddress", current.getMailboxAddress());
                before.put("enabled", current.getEnabled());
            }
            items.add(new ItemUndo("emailConnections", connectionKey(name, direction), before));
        }
    }

    private UndoNote undoConnection(Long fuId, ItemUndo item) {
        String[] parts = item.key().split("\\|", 2);
        String name = parts[0];
        EmailConnectionDirection direction = parts.length > 1 ? EmailConnectionDirection.valueOf(parts[1]) : null;
        EmailConnection current = emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(fuId).stream()
                .filter(c -> name.equals(c.getName()) && c.getDirection() == direction).findFirst().orElse(null);
        if (current == null) {
            return note(item, UndoOutcome.SKIPPED_GONE);
        }
        if (item.before() == null) {
            // 删掉它之前先看有没有监控模板在用——撤销不该制造悬空引用
            boolean referenced = emailMonitorRuleRepository.findByFunctionUnitIdOrderByNameAsc(fuId).stream()
                    .anyMatch(r -> current.getConnectionUid().equals(r.getConnectionUid()));
            if (referenced) {
                return note(item, UndoOutcome.KEPT_REFERENCED);
            }
            emailConnectionComponent.delete(fuId, current.getId());
            return note(item, UndoOutcome.DELETED);
        }
        current.setConnectionType(item.before().get("connectionType") instanceof String ct && !ct.isBlank()
                ? com.developer.enums.ConnectionType.valueOf(ct) : current.getConnectionType());
        current.setFromName(str(item.before().get("fromName")));
        current.setMailboxAddress(str(item.before().get("mailboxAddress")));
        current.setEnabled(item.before().get("enabled") instanceof Boolean b ? b : current.getEnabled());
        emailConnectionRepository.save(current);
        return note(item, UndoOutcome.RESTORED);
    }

    // ---- 邮件监控模板 ----

    private void captureMonitors(Long fuId, AiGeneratedData data, List<ItemUndo> items) {
        if (data.getEmailMonitorRules() == null) return;
        Map<String, EmailMonitorRule> existing = new HashMap<>();
        for (EmailMonitorRule r : emailMonitorRuleRepository
                .findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(fuId)) {
            existing.put(r.getName(), r);
        }
        Map<String, String> nameByUid = new HashMap<>();
        for (EmailConnection c : emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(fuId)) {
            nameByUid.put(c.getConnectionUid(), c.getName());
        }
        for (Map<String, Object> proposed : data.getEmailMonitorRules()) {
            String name = str(proposed.get("name"));
            EmailMonitorRule current = existing.get(name);
            Map<String, Object> before = null;
            if (current != null) {
                before = new LinkedHashMap<>();
                before.put("name", current.getName());
                before.put("enabled", current.getEnabled());
                before.put("connectionUid", current.getConnectionUid());
                before.put("folderLabel", current.getFolderLabel());
                before.put("actionType", current.getActionType() != null ? current.getActionType().name() : null);
                before.put("targetFormId", current.getTargetFormId());
                before.put("targetBindingId", current.getTargetBindingId());
                before.put("systemInitiatorUserId", current.getSystemInitiatorUserId());
                before.put("extractionRules", current.getExtractionRules());
                before.put("correlation", current.getCorrelation());
                before.put("pollIntervalSeconds", current.getPollIntervalSeconds());
                before.put("reviewOnMissing", current.getReviewOnMissing());
            }
            items.add(new ItemUndo("emailMonitorRules", name, before));
        }
    }

    @SuppressWarnings("unchecked")
    private UndoNote undoMonitor(Long fuId, ItemUndo item) {
        EmailMonitorRule current = emailMonitorRuleRepository
                .findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(fuId).stream()
                .filter(r -> item.key().equals(r.getName())).findFirst().orElse(null);
        if (current == null) {
            return note(item, UndoOutcome.SKIPPED_GONE);
        }
        if (item.before() == null) {
            emailMonitorRuleComponent.delete(fuId, current.getId());
            return note(item, UndoOutcome.DELETED);
        }
        Map<String, Object> b = item.before();
        EmailMonitorRuleRequest request = new EmailMonitorRuleRequest();
        request.setName(str(b.get("name")));
        request.setEnabled(b.get("enabled") instanceof Boolean e ? e : Boolean.TRUE);
        request.setConnectionUid(str(b.get("connectionUid")));
        request.setFolderLabel(str(b.get("folderLabel")));
        request.setActionType(b.get("actionType") instanceof String a && !a.isBlank()
                ? EmailMonitorActionType.valueOf(a) : EmailMonitorActionType.START_PROCESS);
        request.setTargetFormId(b.get("targetFormId") instanceof Number n ? n.longValue() : null);
        request.setTargetBindingId(str(b.get("targetBindingId")));
        request.setSystemInitiatorUserId(str(b.get("systemInitiatorUserId")));
        request.setExtractionRules(b.get("extractionRules") instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
        request.setCorrelation(b.get("correlation") instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
        request.setPollIntervalSeconds(b.get("pollIntervalSeconds") instanceof Number n ? n.intValue() : 60);
        request.setReviewOnMissing(b.get("reviewOnMissing") instanceof Boolean r ? r : Boolean.TRUE);
        emailMonitorRuleComponent.update(fuId, current.getId(), request);
        return note(item, UndoOutcome.RESTORED);
    }

    // ---- 主表视图 ----

    private void captureViews(Long fuId, AiGeneratedData data, List<ItemUndo> items) {
        if (data.getMainTableViews() == null) return;
        Map<Long, String> tableNameById = new HashMap<>();
        for (TableDefinition t : tableDefinitionRepository.findByFunctionUnitId(fuId)) {
            tableNameById.put(t.getId(), t.getTableName());
        }
        Map<String, MainTableViewConfig> existing = new HashMap<>();
        for (MainTableViewConfig v : mainTableViewConfigRepository.findByFunctionUnitIdWithFields(fuId)) {
            existing.put(tableNameById.get(v.getMainTableId()) + "|" + v.getViewName(), v);
        }
        for (Map<String, Object> proposed : data.getMainTableViews()) {
            String key = str(proposed.get("mainTableName")) + "|" + str(proposed.get("viewName"));
            MainTableViewConfig current = existing.get(key);
            Map<String, Object> before = null;
            if (current != null) {
                // 用服务自己的读路径拿全量 DTO（含访问规则），避免在这里重复一遍装配逻辑
                MainTableViewDTO dto = mainTableViewService.getView(fuId, current.getId());
                before = new LinkedHashMap<>();
                before.put("viewName", dto.viewName());
                before.put("restrictToInvolvedUsers", dto.restrictToInvolvedUsers());
                before.put("detailFormId", dto.detailFormId());
                before.put("accessRules", dto.accessRules());
                before.put("sortConfig", dto.sortConfig());
                before.put("filterConfig", dto.filterConfig());
                before.put("fields", dto.fields());
            }
            items.add(new ItemUndo("mainTableViews", key, before));
        }
    }

    @SuppressWarnings("unchecked")
    private UndoNote undoView(Long fuId, ItemUndo item) {
        String[] parts = item.key().split("\\|", 2);
        String tableName = parts[0];
        String viewName = parts.length > 1 ? parts[1] : "";
        Map<Long, String> tableNameById = new HashMap<>();
        for (TableDefinition t : tableDefinitionRepository.findByFunctionUnitId(fuId)) {
            tableNameById.put(t.getId(), t.getTableName());
        }
        MainTableViewConfig current = mainTableViewConfigRepository.findByFunctionUnitIdWithFields(fuId).stream()
                .filter(v -> viewName.equals(v.getViewName()) && tableName.equals(tableNameById.get(v.getMainTableId())))
                .findFirst().orElse(null);
        if (current == null) {
            return note(item, UndoOutcome.SKIPPED_GONE);
        }
        if (item.before() == null) {
            mainTableViewService.deleteView(fuId, current.getId());
            return note(item, UndoOutcome.DELETED);
        }
        Map<String, Object> b = item.before();
        mainTableViewService.updateView(fuId, current.getId(), new UpdateMainTableViewRequest(
                str(b.get("viewName")),
                b.get("restrictToInvolvedUsers") instanceof Boolean r ? r : null,
                b.get("detailFormId") instanceof Number n ? n.longValue() : null,
                (List<MainTableViewAccessRuleDTO>) b.get("accessRules"),
                (List<Map<String, Object>>) b.get("sortConfig"),
                (Map<String, Object>) b.get("filterConfig"),
                (List<MainTableViewFieldDTO>) b.get("fields")));
        return note(item, UndoOutcome.RESTORED);
    }

    // ---- service task 绑定 ----

    private void captureBindings(Long fuId, AiGeneratedData data, List<ItemUndo> items) {
        if (data.getServiceTaskBindings() == null) return;
        ProcessDefinition pd = processDefinitionRepository.findByFunctionUnitId(fuId).orElse(null);
        Map<String, String> flowKeyByTask = new HashMap<>();
        if (pd != null && pd.getBpmnXml() != null) {
            for (BpmnServiceTaskScanner.ServiceTaskInfo t : BpmnServiceTaskScanner.scan(pd.getBpmnXml())) {
                if (t.flowKey() != null) flowKeyByTask.put(t.id(), t.flowKey());
            }
        }
        for (Map<String, Object> proposed : data.getServiceTaskBindings()) {
            String taskId = str(proposed.get("serviceTaskId"));
            String previous = flowKeyByTask.get(taskId);
            Map<String, Object> before = previous != null ? Map.of("flowKey", previous) : null;
            items.add(new ItemUndo("serviceTaskBindings", taskId, before));
        }
    }

    private UndoNote undoBinding(Long fuId, ItemUndo item) {
        ProcessDefinition pd = processDefinitionRepository.findByFunctionUnitId(fuId).orElse(null);
        if (pd == null || pd.getBpmnXml() == null) {
            return note(item, UndoOutcome.SKIPPED_NO_PROCESS);
        }
        String xml = XmlEncodingUtil.smartDecode(pd.getBpmnXml());
        String patched = item.before() == null
                ? BpmnServiceTaskBindingPatcher.unbind(xml, List.of(item.key()))
                : BpmnServiceTaskBindingPatcher.bind(xml, Map.of(item.key(), str(item.before().get("flowKey"))));
        pd.setBpmnXml(XmlEncodingUtil.encode(patched));
        processDefinitionRepository.save(pd);
        return item.before() == null
                ? note(item, UndoOutcome.UNBOUND)
                : note(item.slice(), displayName(item), UndoOutcome.REBOUND, str(item.before().get("flowKey")));
    }

    // ---- 整片快照 ----

    private Map<String, Object> currentProcess(Long fuId) {
        ProcessDefinition pd = processDefinitionRepository.findByFunctionUnitId(fuId).orElse(null);
        if (pd == null || pd.getBpmnXml() == null || pd.getBpmnXml().isBlank()) {
            return null;
        }
        return Map.of("bpmnXml", XmlEncodingUtil.smartDecode(pd.getBpmnXml()));
    }

    private List<Map<String, Object>> currentRelations(Long fuId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<Long, String> tableNameById = new HashMap<>();
        for (TableDefinition t : tableDefinitionRepository.findByFunctionUnitId(fuId)) {
            tableNameById.put(t.getId(), t.getTableName());
        }
        for (TableRelation r : tableRelationRepository.findByFunctionUnitId(fuId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceTableName", tableNameById.get(r.getSourceTableId()));
            m.put("sourceFieldName", r.getSourceFieldName());
            m.put("relationType", r.getRelationType());
            m.put("targetTableName", tableNameById.get(r.getTargetTableId()));
            m.put("targetFieldName", r.getTargetFieldName());
            out.add(m);
        }
        return out;
    }

    private static UndoNote note(ItemUndo item, UndoOutcome outcome) {
        return note(item.slice(), displayName(item), outcome, null);
    }

    private static UndoNote note(String slice, String name, UndoOutcome outcome, String detail) {
        return UndoNote.builder().slice(slice).name(name).outcome(outcome).detail(detail).build();
    }

    /** 内部键 → 展示名：连接键 "name|DIRECTION" 只留名字，视图键 "表|视图" 显示成 "表 / 视图" */
    private static String displayName(ItemUndo item) {
        if (item.key() == null) {
            return null;
        }
        String[] parts = item.key().split("\\|", 2);
        return switch (item.slice()) {
            case "emailConnections" -> parts[0];
            case "mainTableViews" -> parts.length > 1 ? parts[0] + " / " + parts[1] : parts[0];
            default -> item.key();
        };
    }

    private static String connectionKey(String name, EmailConnectionDirection direction) {
        return name + "|" + (direction != null ? direction.name() : EmailConnectionDirection.OUTBOUND.name());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
