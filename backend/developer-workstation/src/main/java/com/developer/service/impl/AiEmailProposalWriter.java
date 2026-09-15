package com.developer.service.impl;

import com.developer.component.EmailConnectionComponent;
import com.developer.component.EmailMonitorRuleComponent;
import com.developer.component.EmailTemplateComponent;
import com.developer.dto.AiGeneratedData;
import com.developer.dto.EmailConnectionRequest;
import com.developer.dto.EmailMonitorRuleRequest;
import com.developer.dto.EmailTemplateRequest;
import com.developer.entity.EmailConnection;
import com.developer.entity.EmailMonitorRule;
import com.developer.entity.EmailTemplate;
import com.developer.entity.FormDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.enums.ConnectionType;
import com.developer.enums.EmailConnectionDirection;
import com.developer.enums.EmailMonitorActionType;
import com.developer.exception.AiGenerationException;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.FormDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Studio 邮件三阶段提案的落库协作类（EMAIL_TEMPLATES / CONNECTIONS / EMAIL_MONITORS）。
 *
 * <p>与 {@link AiWriteServiceImpl} 其余切片的"清空再写"不同，这三类是按业务名 upsert：
 * 只新增/更新提案里点名的对象，从不删除未提及的对象。写入一律经由设计器同一套
 * Component（命名冲突、系统 SMTP/IMAP 端点解析、监控连接规则、绑定副本同步都在那里），
 * 不走导入器的裸 save。</p>
 *
 * <p>安全边界：连接提案永远不携带凭证——新建连接 username/password 留空（由用户在
 * Connection 设计器补齐），已有连接只改显示字段、不触碰凭证列。</p>
 */
@Slf4j
@Component
public class AiEmailProposalWriter {

    private final EmailTemplateComponent emailTemplateComponent;
    private final EmailConnectionComponent emailConnectionComponent;
    private final EmailMonitorRuleComponent emailMonitorRuleComponent;
    private final EmailTemplateRepository emailTemplateRepository;
    private final EmailConnectionRepository emailConnectionRepository;
    private final EmailMonitorRuleRepository emailMonitorRuleRepository;
    private final FormDefinitionRepository formDefinitionRepository;

    public AiEmailProposalWriter(EmailTemplateComponent emailTemplateComponent,
                                 EmailConnectionComponent emailConnectionComponent,
                                 EmailMonitorRuleComponent emailMonitorRuleComponent,
                                 EmailTemplateRepository emailTemplateRepository,
                                 EmailConnectionRepository emailConnectionRepository,
                                 EmailMonitorRuleRepository emailMonitorRuleRepository,
                                 FormDefinitionRepository formDefinitionRepository) {
        this.emailTemplateComponent = emailTemplateComponent;
        this.emailConnectionComponent = emailConnectionComponent;
        this.emailMonitorRuleComponent = emailMonitorRuleComponent;
        this.emailTemplateRepository = emailTemplateRepository;
        this.emailConnectionRepository = emailConnectionRepository;
        this.emailMonitorRuleRepository = emailMonitorRuleRepository;
        this.formDefinitionRepository = formDefinitionRepository;
    }

    /** 提案里是否带了任一邮件切片（供调用方决定要不要走本类）。 */
    static boolean hasEmailSlices(AiGeneratedData data) {
        return data != null && (notEmpty(data.getEmailTemplates())
                || notEmpty(data.getEmailConnections())
                || notEmpty(data.getEmailMonitorRules()));
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    /** 写入顺序：模板 → 连接 → 监控模板（监控引用连接名，连接必须先落库）。 */
    public void write(FunctionUnit functionUnit, AiGeneratedData data) {
        Long functionUnitId = functionUnit.getId();
        writeTemplates(functionUnitId, data.getEmailTemplates());
        writeConnections(functionUnitId, data.getEmailConnections());
        writeMonitorRules(functionUnitId, data.getEmailMonitorRules());
    }

    private void writeTemplates(Long functionUnitId, List<Map<String, Object>> templates) {
        if (!notEmpty(templates)) return;
        Map<String, EmailTemplate> existing = new HashMap<>();
        for (EmailTemplate t : emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId)) {
            existing.put(t.getName(), t);
        }
        int created = 0;
        int updated = 0;
        for (Map<String, Object> data : templates) {
            EmailTemplateRequest request = new EmailTemplateRequest();
            request.setName(str(data.get("name")).trim());
            request.setSubject(str(data.get("subject")));
            request.setBodyHtml(str(data.get("bodyHtml")));
            request.setEnabled(data.get("enabled") instanceof Boolean b ? b : Boolean.TRUE);
            EmailTemplate current = existing.get(request.getName());
            if (current != null) {
                emailTemplateComponent.update(functionUnitId, current.getId(), request);
                updated++;
            } else {
                emailTemplateComponent.create(functionUnitId, request);
                created++;
            }
        }
        log.info("AI Studio email templates applied: functionUnitId={}, created={}, updated={}",
                functionUnitId, created, updated);
    }

    private void writeConnections(Long functionUnitId, List<Map<String, Object>> connections) {
        if (!notEmpty(connections)) return;
        Map<String, EmailConnection> existing = new HashMap<>();
        for (EmailConnection c : emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId)) {
            existing.put(connectionKey(c.getName(), c.getDirection()), c);
        }
        int created = 0;
        int updated = 0;
        for (Map<String, Object> data : connections) {
            String name = str(data.get("name")).trim();
            EmailConnectionDirection direction = data.get("direction") instanceof String d && !d.isBlank()
                    ? EmailConnectionDirection.valueOf(d) : EmailConnectionDirection.OUTBOUND;
            ConnectionType type = data.get("connectionType") instanceof String ct && !ct.isBlank()
                    ? ConnectionType.valueOf(ct) : ConnectionType.GMAIL;
            String fromName = str(data.get("fromName"));
            String mailboxAddress = str(data.get("mailboxAddress"));
            Boolean enabled = data.get("enabled") instanceof Boolean b ? b : Boolean.TRUE;

            EmailConnection current = existing.get(connectionKey(name, direction));
            if (current != null) {
                // 已有连接：只改显示字段。不走 component.update——它在 username 为空时会清空凭证，
                // 而 AI 提案永远不带凭证，走过去等于删掉用户配好的密码。
                current.setConnectionType(type);
                current.setFromName(fromName);
                current.setMailboxAddress(mailboxAddress);
                current.setEnabled(enabled);
                emailConnectionRepository.save(current);
                updated++;
            } else {
                EmailConnectionRequest request = new EmailConnectionRequest();
                request.setName(name);
                request.setConnectionType(type);
                request.setDirection(direction);
                request.setFromName(fromName);
                request.setMailboxAddress(mailboxAddress);
                request.setEnabled(enabled);
                // username / password 刻意留空：凭证只能由用户在 Connection 设计器里填写
                request.setUsername(null);
                request.setPassword(null);
                emailConnectionComponent.create(functionUnitId, request);
                created++;
            }
        }
        log.info("AI Studio email connections applied: functionUnitId={}, created={}, updated={}",
                functionUnitId, created, updated);
    }

    private void writeMonitorRules(Long functionUnitId, List<Map<String, Object>> rules) {
        if (!notEmpty(rules)) return;
        Map<String, EmailMonitorRule> existing = new HashMap<>();
        for (EmailMonitorRule r : emailMonitorRuleRepository
                .findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(functionUnitId)) {
            existing.put(r.getName(), r);
        }
        Map<String, String> connectionUidByName = new HashMap<>();
        for (EmailConnection c : emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId)) {
            // 同名连接可能出站/入站各一条；监控只认入站，入站优先覆盖
            if (c.getDirection() == EmailConnectionDirection.INBOUND || !connectionUidByName.containsKey(c.getName())) {
                connectionUidByName.put(c.getName(), c.getConnectionUid());
            }
        }
        Map<String, Long> formIdByName = new HashMap<>();
        for (FormDefinition f : formDefinitionRepository.findByFunctionUnitId(functionUnitId)) {
            formIdByName.put(f.getFormName(), f.getId());
        }

        int created = 0;
        int updated = 0;
        for (Map<String, Object> data : rules) {
            String connectionName = str(data.get("connectionName")).trim();
            String connectionUid = connectionUidByName.get(connectionName);
            if (connectionUid == null) {
                // 引用校验器已挡过一遍；这里是写入前的最后一道，不能静默写空
                throw new AiGenerationException("AI_STUDIO_MONITOR_CONNECTION_NOT_FOUND",
                        "Email monitor rule references unknown connection: " + connectionName);
            }
            Long targetFormId = null;
            if (data.get("targetFormName") instanceof String formName && !formName.isBlank()) {
                targetFormId = formIdByName.get(formName.trim());
                if (targetFormId == null) {
                    throw new AiGenerationException("AI_STUDIO_MONITOR_FORM_NOT_FOUND",
                            "Email monitor rule references unknown form: " + formName);
                }
            }

            EmailMonitorRuleRequest request = new EmailMonitorRuleRequest();
            request.setName(str(data.get("name")).trim());
            request.setEnabled(data.get("enabled") instanceof Boolean b ? b : Boolean.TRUE);
            request.setConnectionUid(connectionUid);
            request.setFolderLabel(data.get("folderLabel") instanceof String fl && !fl.isBlank() ? fl : "INBOX");
            request.setActionType(data.get("actionType") instanceof String at && !at.isBlank()
                    ? EmailMonitorActionType.valueOf(at) : EmailMonitorActionType.START_PROCESS);
            request.setTargetFormId(targetFormId);
            request.setTargetBindingId(null);
            request.setSystemInitiatorUserId(null);
            request.setExtractionRules(asMap(data.get("extractionRules")));
            request.setCorrelation(asMap(data.get("correlation")));
            request.setPollIntervalSeconds(data.get("pollIntervalSeconds") instanceof Number n && n.intValue() > 0
                    ? n.intValue() : 60);
            request.setReviewOnMissing(data.get("reviewOnMissing") instanceof Boolean b ? b : Boolean.TRUE);

            EmailMonitorRule current = existing.get(request.getName());
            if (current != null) {
                emailMonitorRuleComponent.update(functionUnitId, current.getId(), request);
                updated++;
            } else {
                emailMonitorRuleComponent.create(functionUnitId, request);
                created++;
            }
        }
        log.info("AI Studio email monitor rules applied: functionUnitId={}, created={}, updated={}",
                functionUnitId, created, updated);
    }

    private static String connectionKey(String name, EmailConnectionDirection direction) {
        return (name == null ? "" : name.trim()) + "|" + (direction == null ? "OUTBOUND" : direction.name());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
