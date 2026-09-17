package com.developer.service.impl;

import com.developer.exception.AiGenerationException;
import com.developer.util.AiBpmnSemanticGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.springframework.stereotype.Component;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 AI gateway 的 OpenAI 兼容响应解析成 DW 内部契约
 * {@code {reply, document, documentType, phaseComplete, generatedData}}。
 *
 * <p>本类是 {@code GenAI/parse_response.md}（原 Activepieces flow 的 "Parse Response" 步骤）的 Java 移植，
 * 逐条保留了那边踩出来的规整规则：length/precision/scale/defaultValue 一律转字符串（
 * {@code AiWriteService} 按 String 解析）、configJson 字符串反序列化且非法即丢、
 * BPMN 的 Base64 解码与确定性兜底 XML，以及"兜底 XML 无任务节点则拒绝"这条 fail-loud 规则。</p>
 *
 * <p>与 JS 版的两处有意差异（均为修正，不是行为漂移）：
 * <ul>
 *   <li>JS 的 {@code ```json?} 正则实际匹配的是 {@code ```jso} + 可选 n，纯 {@code ```} 围栏漏网；这里改为 {@code ```(?:json)?}。</li>
 *   <li>JS 在 choices 为空且 HTTP&lt;400 时会把整个响应信封 {@code JSON.stringify} 当成回答塞进对话；
 *       这里显式抛 {@code AI_GATEWAY_EMPTY_RESPONSE}。</li>
 * </ul></p>
 */
@Slf4j
@Component
public class AiResponseParser {

    private static final Pattern REQUIREMENTS_DOC =
            Pattern.compile("---REQUIREMENTS_DOC_START---(.*?)---REQUIREMENTS_DOC_END---", Pattern.DOTALL);
    private static final Pattern DESIGN_DOC =
            Pattern.compile("---DESIGN_DOC_START---(.*?)---DESIGN_DOC_END---", Pattern.DOTALL);
    private static final Pattern GENERATED_DATA =
            Pattern.compile("---GENERATED_DATA_START---(.*?)(?:---GENERATED_DATA_END---|$)", Pattern.DOTALL);
    private static final Pattern JSON_FENCE =
            Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern BASE64_ONLY = Pattern.compile("^[A-Za-z0-9+/=\\s]+$");
    private static final Pattern BPMN_DEFINITIONS = Pattern.compile("<(?:bpmn:)?definitions(?:\\s|>)");
    /** definitions 开标签（含全部属性）——补命名空间声明时就地改写这一段。 */
    private static final Pattern DEFINITIONS_OPEN_TAG =
            Pattern.compile("<(?:[A-Za-z0-9_.-]+:)?definitions[^>]*>");
    /** 不构成实体引用的裸 {@code &}（后面没跟 {@code name;} / {@code #123;} / {@code #x1F;}）。 */
    private static final Pattern BARE_AMPERSAND =
            Pattern.compile("&(?!(?:[A-Za-z][A-Za-z0-9]*|#[0-9]+|#[xX][0-9A-Fa-f]+);)");
    /** 规范固定 URI 的前缀：缺声明时补法唯一，属确定性修复。 */
    private static final Map<String, String> WELL_KNOWN_NAMESPACES = Map.of(
            "xsi", "http://www.w3.org/2001/XMLSchema-instance",
            "bpmndi", "http://www.omg.org/spec/BPMN/20100524/DI",
            "dc", "http://www.omg.org/spec/DD/20100524/DC",
            "di", "http://www.omg.org/spec/DD/20100524/DI");
    private static final Pattern BPMN_PROCESS = Pattern.compile("<(?:bpmn:)?process(?:\\s|>)");
    private static final Pattern BPMN_START_EVENT = Pattern.compile("<(?:bpmn:)?startEvent(?:\\s|>)");
    private static final Pattern BPMN_END_EVENT = Pattern.compile("<(?:bpmn:)?endEvent(?:\\s|>)");
    private static final Pattern BPMN_WORK_NODE = Pattern.compile(
            "<(?:bpmn:)?(?:task|userTask|serviceTask|manualTask|scriptTask|businessRuleTask|sendTask|receiveTask)(?:\\s|>)");
    private static final String BPMN_MODEL_NAMESPACE = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final Set<String> BPMN_FLOW_NODE_NAMES = Set.of(
            "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent",
            "task", "userTask", "serviceTask", "manualTask", "scriptTask",
            "businessRuleTask", "sendTask", "receiveTask", "callActivity", "subProcess",
            "exclusiveGateway", "inclusiveGateway", "parallelGateway", "eventBasedGateway", "complexGateway");
    private static final Set<String> AI_ASSIGNEE_TYPES = Set.of(
            "PROCESS_INITIATOR", "ENTITY_MANAGER", "FUNCTIONAL_MANAGER", "HIERARCHY_ROLE",
            "BU_ROLE", "MANUAL_ASSIGN", "ASSIGNEE_FROM_VARIABLE", "ELEMENT_VARIABLE");
    /** 设计文档里"这一格没有内容"的常见写法。 */
    private static final Set<String> DESIGN_EMPTY_CELL_WORDS = Set.of(
            "n/a", "na", "none", "no", "nil", "null", "not applicable", "-", "无", "不适用");
    /** 确定不能承载 stage 绑定的 BPMN 类型（gateway 另按后缀判定）。 */
    private static final Set<String> DESIGN_NON_STAGE_NODE_TYPES = Set.of(
            "start", "startevent", "end", "endevent", "sequenceflow",
            "servicetask", "scripttask", "businessruletask", "manualtask", "sendtask", "receivetask",
            "intermediatecatchevent", "intermediatethrowevent", "callactivity", "subprocess");
    private static final String PHASE_COMPLETE_MARKER = "---PHASE_COMPLETE---";
    private static final char BOM = '\uFEFF';

    private final ObjectMapper objectMapper;

    public AiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param httpResult {@link AiGatewayClient#chat} 返回的 {@code {status, body}}
     * @return DW 内部契约 map（value 可为 null，故用 LinkedHashMap）
     */
    public Map<String, Object> parse(Map<String, Object> httpResult) {
        String reply = extractAssistantText(httpResult);

        String document = null;
        String documentType = null;

        Matcher req = REQUIREMENTS_DOC.matcher(reply);
        if (req.find()) {
            document = req.group(1).trim();
            documentType = "REQUIREMENTS";
        }
        Matcher design = DESIGN_DOC.matcher(reply);
        if (design.find()) {
            document = design.group(1).trim();
            documentType = "DESIGN";
            validateDesignDocument(document);
        }

        boolean phaseComplete = reply.contains(PHASE_COMPLETE_MARKER);

        Map<String, Object> generatedData = null;
        List<String> semanticRepairs = List.of();
        Matcher gen = GENERATED_DATA.matcher(reply);
        if (gen.find()) {
            generatedData = parseGeneratedData(gen.group(1).trim());
        }
        if (generatedData != null) {
            normalizeFieldMetadata(generatedData);
            normalizeConfigJson(generatedData);
            semanticRepairs = normalizeBpmn(generatedData);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", stripMarkers(reply));
        result.put("document", document);
        result.put("documentType", documentType);
        result.put("phaseComplete", phaseComplete);
        result.put("generatedData", generatedData);
        // 平台替模型改过的地方。日志已经 warn 过一遍，这里再回传一份，让调用方有机会摆到用户面前——
        // "存进去的流程和模型给的不完全一样"不该只活在服务端日志里。
        result.put("semanticRepairs", semanticRepairs);
        return result;
    }

    // ==================== 响应信封 ====================

    /**
     * 只取助手回复原文（不解析文档/数据块）。给自带输出格式的调用方用——例如 AI Studio 文档同步
     * 一次输出两份文档，而 {@link #parse} 只保留最后一个文档块，并按旧设计文档格式校验。
     */
    public String assistantText(Map<String, Object> httpResult) {
        return extractAssistantText(httpResult);
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantText(Map<String, Object> httpResult) {
        int status = httpResult.get("status") instanceof Number n ? n.intValue() : 0;
        Object bodyObj = httpResult.get("body");
        Map<String, Object> body = bodyObj instanceof Map ? (Map<String, Object>) bodyObj : Map.of();

        Object choicesObj = body.get("choices");
        List<Object> choices = choicesObj instanceof List ? (List<Object>) choicesObj : List.of();

        String content = null;
        if (!choices.isEmpty() && choices.get(0) instanceof Map<?, ?> first
                && first.get("message") instanceof Map<?, ?> message
                && message.get("content") instanceof String text) {
            content = text;
        }

        if (content != null && !content.isBlank()) {
            return content;
        }
        if (status >= 400) {
            throw new AiGenerationException("AI_GATEWAY_HTTP_ERROR",
                    "AI gateway request failed with HTTP " + status + errorDetail(body));
        }
        throw new AiGenerationException("AI_GATEWAY_EMPTY_RESPONSE",
                "AI gateway returned an empty assistant response (HTTP " + status + ")");
    }

    /** 从 gateway 错误体里挖出可读文案：先 message，再 error.message / error.code。 */
    private String errorDetail(Map<String, Object> body) {
        if (body.get("message") instanceof String m && !m.isBlank()) {
            return ": " + m;
        }
        if (body.get("error") instanceof Map<?, ?> error) {
            if (error.get("message") instanceof String em && !em.isBlank()) {
                return ": " + em;
            }
            if (error.get("code") instanceof String ec && !ec.isBlank()) {
                return ": " + ec;
            }
        }
        return "";
    }

    // ==================== DESIGN 文档 ====================

    /**
     * 在 DESIGN 阶段就拦掉两类必然让 GENERATION 生不出合法 BPMN 的设计缺陷:
     * 把动作/表单挂在非 userTask 节点上,以及 userTask 自环。
     *
     * <p>这两条以前只有生成阶段才发现,而那时模型是在"照做非法设计"和"遵守平台约束"之间随机折中,
     * 于是同一份设计每重试一次报一个新错。设计文档是自然语言,所以这里只在能确信读懂表格时才判定:
     * 认不出列头、认不出节点类型一律跳过(记 warn),宁可漏判也不误伤一份合法设计。</p>
     */
    private void validateDesignDocument(String document) {
        List<Map<String, String>> nodeRows = parseMatrix(document, "Process Node Matrix");
        for (Map<String, String> row : nodeRows) {
            String nodeId = cell(row, "node", "id");
            String type = cell(row, "type");
            if (!isNonStageNodeType(type)) {
                continue;
            }
            if (!isBlankCell(cell(row, "action"))) {
                throw invalidDesignBinding("node '" + nodeId + "' is a " + type
                        + " but the Process Node Matrix gives it actions; actions may only be bound to a userTask"
                        + " (put the submit action on the first userTask)");
            }
            if (!isBlankCell(cell(row, "form"))) {
                throw invalidDesignBinding("node '" + nodeId + "' is a " + type
                        + " but the Process Node Matrix binds a form to it; only a userTask can carry a TASK form");
            }
        }

        List<Map<String, String>> flowRows = parseMatrix(document, "Sequence Flow Matrix");
        Set<String> endpoints = new HashSet<>();
        for (Map<String, String> row : flowRows) {
            String source = cell(row, "source");
            String target = cell(row, "target");
            if (isBlankCell(source) || isBlankCell(target)) {
                continue;
            }
            if (source.equals(target)) {
                throw new AiGenerationException("AI_DESIGN_SELF_LOOP",
                        "AI designed a self-loop on '" + source + "'. A failed check keeps the process on the current"
                        + " user task and produces no sequence flow; model rework and rollback as runtime actions");
            }
            if (!endpoints.add(source + " " + target)) {
                throw new AiGenerationException("AI_DESIGN_DUPLICATE_FLOW",
                        "AI designed more than one sequence flow from '" + source + "' to '" + target
                        + "'. Route the branch through one exclusive gateway instead");
            }
        }
    }

    /**
     * 抽出 {@code ### <title>} 小节下的第一张 Markdown 表格,每行返回 {规范化列头 → 单元格}。
     * 找不到小节、找不到表头或缺分隔行都返回空列表——调用方据此跳过校验。
     */
    private List<Map<String, String>> parseMatrix(String document, String title) {
        String[] lines = document.split("\\R");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#") && line.toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT))) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            log.warn("DESIGN document has no '{}' section — skipping that design check", title);
            return List.of();
        }

        List<String> header = null;
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#")) {
                break; // 下一节开始,本节表格已读完
            }
            if (!line.startsWith("|")) {
                if (header != null) {
                    break; // 表格结束
                }
                continue; // 表格前的说明文字
            }
            List<String> cells = splitRow(line);
            if (header == null) {
                header = cells;
                continue;
            }
            if (isSeparatorRow(cells)) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < cells.size(); c++) {
                row.put(header.get(c).toLowerCase(Locale.ROOT), cells.get(c));
            }
            rows.add(row);
        }
        if (header == null) {
            log.warn("DESIGN document '{}' section has no Markdown table — skipping that design check", title);
        }
        return rows;
    }

    private static List<String> splitRow(String line) {
        String trimmed = line.replaceAll("^\\|", "").replaceAll("\\|\\s*$", "");
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static boolean isSeparatorRow(List<String> cells) {
        for (String cell : cells) {
            if (!cell.matches(":?-{2,}:?")) {
                return false;
            }
        }
        return !cells.isEmpty();
    }

    /** 按列头关键词取单元格；关键词全部命中才算这一列，缺列返回空串。 */
    private static String cell(Map<String, String> row, String... keywords) {
        for (Map.Entry<String, String> entry : row.entrySet()) {
            boolean matches = true;
            for (String keyword : keywords) {
                if (!entry.getKey().contains(keyword)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return entry.getValue();
            }
        }
        return "";
    }

    /** 占位符也算空：模型用 -、N/A、None 表示"这一格没有内容"。 */
    private static boolean isBlankCell(String value) {
        String normalized = value.replaceAll("[`*_()\\[\\]]", "").trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty()
                || normalized.matches("[-–—]+")
                || DESIGN_EMPTY_CELL_WORDS.contains(normalized);
    }

    /**
     * 只认平台能确定"不是 userTask"的类型名；认不出的（例如模型写成 Human Task）返回 false 跳过，
     * 这条判定会直接拒掉整份设计文档，误伤的代价比漏判大得多。
     */
    private static boolean isNonStageNodeType(String type) {
        String normalized = type.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
        if (normalized.contains("usertask")) {
            return false;
        }
        return DESIGN_NON_STAGE_NODE_TYPES.contains(normalized) || normalized.endsWith("gateway");
    }

    private AiGenerationException invalidDesignBinding(String detail) {
        return new AiGenerationException("AI_DESIGN_STAGE_BINDING_INVALID",
                "AI design binds a stage to a node that is not a bpmn:userTask: " + detail);
    }

    // ==================== generatedData ====================

    /** 三级解析：整段 JSON → ```json 围栏 → 首尾大括号切片。三级都失败返回 null（前端表现为无预览数据）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGeneratedData(String raw) {
        Map<String, Object> direct = tryReadObject(raw);
        if (direct != null) {
            return direct;
        }
        Matcher fence = JSON_FENCE.matcher(raw);
        if (fence.find()) {
            Map<String, Object> fenced = tryReadObject(fence.group(1).trim());
            if (fenced != null) {
                return fenced;
            }
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start != -1 && end > start) {
            Map<String, Object> sliced = tryReadObject(raw.substring(start, end + 1));
            if (sliced != null) {
                return sliced;
            }
        }
        log.warn("GENERATED_DATA block present but not parseable as JSON ({} chars)", raw.length());
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryReadObject(String json) {
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * AiWriteService 按 String 解析字段的长度/精度/默认值元数据，
     * 而模型经常直接输出 JSON 数字——在进入预览/写入前统一转成字符串。
     */
    @SuppressWarnings("unchecked")
    private void normalizeFieldMetadata(Map<String, Object> generatedData) {
        if (!(generatedData.get("tableDefinitions") instanceof List<?> tables)) {
            return;
        }
        for (Object tableObj : tables) {
            if (!(tableObj instanceof Map<?, ?> table)
                    || !(table.get("fieldDefinitions") instanceof List<?> fields)) {
                continue;
            }
            for (Object fieldObj : fields) {
                if (!(fieldObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> field = (Map<String, Object>) fieldObj;
                for (String key : List.of("length", "precision", "scale")) {
                    Object value = field.get(key);
                    if (value != null && !(value instanceof String)) {
                        field.put(key, String.valueOf(value));
                    }
                }
                Object defaultValue = field.get("defaultValue");
                if (defaultValue != null && !(defaultValue instanceof String)) {
                    field.put("defaultValue", defaultValue instanceof Map || defaultValue instanceof List
                            ? writeJson(defaultValue)
                            : String.valueOf(defaultValue));
                }
            }
        }
    }

    /** configJson 允许模型以 JSON 字符串给出；非法或非对象一律丢成 null，避免下游 ClassCastException。 */
    @SuppressWarnings("unchecked")
    private void normalizeConfigJson(Map<String, Object> generatedData) {
        if (generatedData.get("formDefinitions") instanceof List<?> forms) {
            for (Object formObj : forms) {
                if (!(formObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> form = (Map<String, Object>) formObj;
                form.put("configJson", coerceConfigJson(form.get("configJson")));
            }
        }
        if (generatedData.get("actionDefinitions") instanceof List<?> actions) {
            for (Object actionObj : actions) {
                if (!(actionObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> action = (Map<String, Object>) actionObj;
                action.put("configJson", coerceConfigJson(action.get("configJson")));
            }
        }
    }

    private Object coerceConfigJson(Object value) {
        Object resolved = value;
        if (resolved instanceof String s) {
            resolved = tryReadObject(s);
        }
        return resolved instanceof Map ? resolved : null;
    }

    // ==================== BPMN ====================

    /**
     * 平台校验器要的是裸 BPMN XML。模型偶尔给 Base64 或伪 BPMN：能解就解，
     * 结构不合法就换成确定性的 Start→End 骨架；但骨架没有任务节点，最后一步会显式拒绝——
     * 宁可让这一轮生成失败重来，也不要写进去一条跑不动的流程。
     */
    private List<String> normalizeBpmn(Map<String, Object> generatedData) {
        if (!(generatedData.get("processDefinition") instanceof Map<?, ?> processDefinition)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> process = (Map<String, Object>) processDefinition;

        String xml = process.get("bpmnXml") instanceof String s ? s : null;
        if (xml != null) {
            xml = stripBom(xml).trim();
            if (!xml.isEmpty() && xml.charAt(0) != '<' && BASE64_ONLY.matcher(xml).matches()) {
                String decoded = tryDecodeBase64(xml);
                if (decoded != null && !decoded.isEmpty() && decoded.charAt(0) == '<') {
                    xml = decoded;
                }
            }
        }

        boolean structurallyValid = xml != null
                && xml.contains(BPMN_MODEL_NAMESPACE)
                && BPMN_DEFINITIONS.matcher(xml).find()
                && BPMN_PROCESS.matcher(xml).find()
                && BPMN_START_EVENT.matcher(xml).find()
                && BPMN_END_EVENT.matcher(xml).find();

        if (!structurallyValid) {
            String rawName = generatedData.get("name") instanceof String n && !n.isBlank()
                    ? n.trim() : "Generated Process";
            xml = buildFallbackBpmn(rawName);
        }

        if (!BPMN_WORK_NODE.matcher(xml).find()) {
            throw new AiGenerationException("AI_BPMN_NO_TASK_NODES",
                    "AI generated invalid BPMN with no task nodes. Regenerate the preview; "
                            + "empty Start-to-End fallback is rejected.");
        }

        // 命名空间补齐必须排在所有 XML 解析之前:模型经常写出 xsi:type="bpmn:tFormalExpression"
        // (这是 BPMN 条件表达式的标准写法)却忘了在 definitions 上声明 xmlns:xsi,于是解析器直接以
        // "The prefix xsi ... is not bound" 报废整轮生成。声明缺失是纯语法遗漏、补法唯一,
        // 属于确定性修复而非猜测,不该让用户去重试一次两分钟的生成。
        xml = declareWellKnownNamespaces(xml);
        xml = escapeBareAmpersands(xml);

        // 语义层守门排在结构校验之前:它会插入网关、改写条件、补动作,产物必须再过一遍
        // 连通性/DI/阶段绑定,否则修复本身引入的破损就没人拦了。
        AiBpmnSemanticGuard.Result guarded = AiBpmnSemanticGuard.enforce(generatedData, xml);
        xml = guarded.bpmnXml();
        for (String repair : guarded.repairs()) {
            log.warn("AI output violated a platform process rule and was repaired before saving: {}", repair);
        }

        validateConnectedBpmn(xml);
        validateStageBindings(generatedData, xml);
        process.put("bpmnXml", xml);
        return guarded.repairs();
    }

    /**
     * 给 {@code definitions} 补上被用到却没声明的众所周知前缀。
     *
     * <p>只认死这四个（{@code xsi} / {@code bpmndi} / {@code dc} / {@code di}）：它们的 URI 由规范固定，
     * 补法唯一，不存在猜错的空间。其余未声明前缀一律不碰——那属于模型自造标签，应当由解析报错拦下，
     * 而不是被我们编一个 URI 蒙混过关。</p>
     */
    private String declareWellKnownNamespaces(String xml) {
        Matcher matcher = DEFINITIONS_OPEN_TAG.matcher(xml);
        if (!matcher.find()) {
            return xml;
        }
        String openTag = matcher.group();
        StringBuilder declarations = new StringBuilder();
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, String> entry : WELL_KNOWN_NAMESPACES.entrySet()) {
            String prefix = entry.getKey();
            if (openTag.contains("xmlns:" + prefix + "=")) {
                continue;
            }
            boolean used = Pattern.compile("[<\\s]" + prefix + ":[A-Za-z]").matcher(xml).find();
            if (!used) {
                continue;
            }
            declarations.append(" xmlns:").append(prefix).append("=\"").append(entry.getValue()).append('"');
            added.add(prefix);
        }
        if (added.isEmpty()) {
            return xml;
        }
        // 自动修复必须留痕（错误处理治理红线 1）：补了什么、补在哪，日志里要能直接看到。
        log.warn("AI generated BPMN used undeclared namespace prefixes {}; declared them on <definitions> "
                + "so the document can be parsed", added);
        String repaired = openTag.substring(0, openTag.length() - 1) + declarations + ">";
        return new StringBuilder(xml).replace(matcher.start(), matcher.end(), repaired).toString();
    }

    /**
     * 把不构成实体引用的裸 {@code &} 转义成 {@code &amp;}。
     *
     * <p>模型最常见的来源是网关条件里的 {@code ${a && b}}，以及名称里的 "R&D"。裸 {@code &} 让整份文档
     * 不是良构 XML，解析直接抛 "The entity name must immediately follow the '&'"——改动前必然报废整轮生成。
     * 已经是 {@code &amp;} / {@code &lt;} / {@code &#39;} 这类合法引用的一律不动，所以良构文档逐字不变。</p>
     */
    private String escapeBareAmpersands(String xml) {
        Matcher matcher = BARE_AMPERSAND.matcher(xml);
        if (!matcher.find()) {
            return xml;
        }
        String repaired = matcher.reset().replaceAll("&amp;");
        log.warn("AI generated BPMN contained unescaped '&' characters; escaped them so the document can be parsed");
        return repaired;
    }

    private void validateStageBindings(Map<String, Object> generatedData, String xml) {
        try {
            Document document = parseBpmnSecurely(xml);
            Map<String, String> userTasks = new LinkedHashMap<>();
            NodeList userTaskElements = document.getElementsByTagNameNS("*", "userTask");
            for (int i = 0; i < userTaskElements.getLength(); i++) {
                Element task = (Element) userTaskElements.item(i);
                String id = task.getAttribute("id");
                if (!id.isBlank()) {
                    userTasks.put(id, task.getAttribute("name"));
                }
            }

            // 分派与动作绑定只依赖本轮的 BPMN 自身：范围化重生成（只回 processDefinition）时同样必须校验，
            // 不能因为这一轮没重生成表单就整块跳过。
            validateUserTaskAssignments(userTaskElements);
            validateActionStageBindings(generatedData, userTasks.keySet());
            // 表单↔阶段是跨实体交叉校验，只有本轮确实产出 formDefinitions 才有比对对象。
            validateFormStageBindings(generatedData, userTasks);
        } catch (AiGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new AiGenerationException("AI_BPMN_INVALID_XML",
                    "AI generated BPMN XML could not be validated: " + e.getMessage());
        }
    }

    private void validateFormStageBindings(Map<String, Object> generatedData, Map<String, String> userTasks) {
        if (!(generatedData.get("formDefinitions") instanceof List<?> forms)) {
            return;
        }
        Set<String> boundStageIds = new HashSet<>();
        for (Object formObj : forms) {
            if (!(formObj instanceof Map<?, ?> form)) continue;
            boolean taskForm = "TASK".equals(form.get("formType"));
            Object bindingsObj = form.get("stageBindings");
            List<?> bindings = bindingsObj instanceof List<?> list ? list : List.of();
            if (taskForm && bindings.isEmpty()) {
                throw invalidFormBinding("TASK form '" + form.get("formName")
                        + "' has no stageBindings");
            }
            // PROCESS 表单绑阶段是允许的：平台侧没有任何 formType 限制（FormStageBindingController
            // 不判类型、AiBpmnFormBindingWriter 不筛类型、portal 按注入的 formId 直接取表单），
            // 发起人提交那步挂完整流程表单本身也说得通。提示词仍然要求用 TASK 表单——
            // 这里只是不再为"能跑但不够规整"烧掉一次两分钟的重生成。
            // ACTION 表单例外：它是动作弹窗的表单，绑到节点上会让 portal 把动作表单当任务表单打开。
            if (!taskForm && !bindings.isEmpty() && !"PROCESS".equals(form.get("formType"))) {
                // 报错必须指名道姓：排查时要能直接看出是哪张表单、模型给的 formType 是什么
                // （ACTION？还是干脆漏了这个字段）。
                throw invalidFormBinding("form '" + form.get("formName") + "' has formType "
                        + form.get("formType") + " but defines stageBindings; only TASK or PROCESS forms may");
            }
            for (Object bindingObj : bindings) {
                if (!(bindingObj instanceof Map<?, ?> binding)) {
                    throw invalidFormBinding("stageBindings entries must be JSON objects");
                }
                String stageId = binding.get("stageId") instanceof String id ? id.trim() : "";
                String stageName = binding.get("stageName") instanceof String name ? name : "";
                if (!userTasks.containsKey(stageId)) {
                    throw invalidFormBinding("stageId '" + stageId
                            + "' does not match a bpmn:userTask id");
                }
                if (!stageName.equals(userTasks.get(stageId))) {
                    throw invalidFormBinding("stageName for '" + stageId
                            + "' must exactly match the BPMN userTask name");
                }
                if (!boundStageIds.add(stageId)) {
                    throw invalidFormBinding("bpmn:userTask '" + stageId
                            + "' is bound by more than one TASK form");
                }
            }
        }

        Set<String> unboundTaskIds = new HashSet<>(userTasks.keySet());
        unboundTaskIds.removeAll(boundStageIds);
        if (!unboundTaskIds.isEmpty()) {
            throw invalidFormBinding("unbound bpmn:userTask nodes: " + unboundTaskIds);
        }
    }

    private void validateUserTaskAssignments(NodeList userTaskElements) {
        for (int i = 0; i < userTaskElements.getLength(); i++) {
            Element task = (Element) userTaskElements.item(i);
            String taskId = task.getAttribute("id");
            String assigneeType = customPropertyValue(task, "assigneeType");
            if (assigneeType == null || assigneeType.isBlank()) {
                throw new AiGenerationException("AI_TASK_ASSIGNEE_INVALID",
                        "AI generated bpmn:userTask '" + taskId + "' has no assigneeType extension property");
            }
            if (!AI_ASSIGNEE_TYPES.contains(assigneeType.trim())) {
                throw new AiGenerationException("AI_TASK_ASSIGNEE_INVALID",
                        "AI generated bpmn:userTask '" + taskId + "' has unsupported assigneeType '"
                                + assigneeType + "'");
            }
        }
    }

    private void validateActionStageBindings(Map<String, Object> generatedData, Set<String> userTaskIds) {
        if (!(generatedData.get("actionDefinitions") instanceof List<?> actions)) {
            return;
        }
        Set<String> actionNames = new HashSet<>();
        for (Object actionObj : actions) {
            if (!(actionObj instanceof Map<?, ?> action)) {
                throw invalidActionBinding("actionDefinitions entries must be JSON objects");
            }
            String actionName = action.get("actionName") instanceof String name ? name.trim() : "";
            if (actionName.isEmpty() || !actionNames.add(actionName)) {
                throw invalidActionBinding("actionName must be non-empty and unique: '" + actionName + "'");
            }
            if (!(action.get("stageIds") instanceof List<?> stageIds) || stageIds.isEmpty()) {
                throw invalidActionBinding("action '" + actionName + "' must have a non-empty stageIds array");
            }
            for (Object stageIdObj : stageIds) {
                String stageId = stageIdObj instanceof String id ? id.trim() : "";
                if (!userTaskIds.contains(stageId)) {
                    throw invalidActionBinding("action '" + actionName + "' references unknown userTask '"
                            + stageId + "'");
                }
            }
        }
    }

    private String customPropertyValue(Element owner, String propertyName) {
        NodeList descendants = owner.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < descendants.getLength(); i++) {
            Element element = (Element) descendants.item(i);
            String localName = localName(element);
            if (("property".equals(localName) || "values".equals(localName))
                    && propertyName.equals(element.getAttribute("name"))) {
                return element.getAttribute("value");
            }
        }
        return null;
    }

    private void validateConnectedBpmn(String xml) {
        try {

        Document document = parseBpmnSecurely(xml);
        Map<String, Element> flowNodes = new LinkedHashMap<>();
        Set<String> startIds = new HashSet<>();
        Set<String> endIds = new HashSet<>();
        Set<String> sequenceFlowIds = new HashSet<>();
        Set<String> sequenceFlowEndpoints = new HashSet<>();
        Map<String, Set<String>> outgoing = new HashMap<>();
        Map<String, Set<String>> incoming = new HashMap<>();

        NodeList elements = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String localName = localName(element);
            String id = element.getAttribute("id");
            if (BPMN_FLOW_NODE_NAMES.contains(localName) && !id.isBlank()) {
                flowNodes.put(id, element);
                if ("startEvent".equals(localName)) startIds.add(id);
                if ("endEvent".equals(localName)) endIds.add(id);
            }
        }

        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (!"sequenceFlow".equals(localName(element))) continue;
            String id = element.getAttribute("id");
            String sourceRef = element.getAttribute("sourceRef");
            String targetRef = element.getAttribute("targetRef");
            if (id.isBlank() || !flowNodes.containsKey(sourceRef) || !flowNodes.containsKey(targetRef)) {
                throw disconnected("sequenceFlow '" + id + "' has a missing or invalid sourceRef/targetRef");
            }
            if (!sequenceFlowEndpoints.add(sourceRef + "\u0000" + targetRef)) {
                throw disconnected("duplicate sequenceFlow endpoints '" + sourceRef + "' -> '"
                        + targetRef + "'");
            }
            sequenceFlowIds.add(id);
            outgoing.computeIfAbsent(sourceRef, ignored -> new HashSet<>()).add(targetRef);
            incoming.computeIfAbsent(targetRef, ignored -> new HashSet<>()).add(sourceRef);
        }

        if (sequenceFlowIds.isEmpty()) {
            throw disconnected("the process contains no sequenceFlow elements");
        }

        Set<String> reachableFromStart = traverse(startIds, outgoing);
        Set<String> canReachEnd = traverse(endIds, incoming);
        Set<String> disconnectedIds = new HashSet<>(flowNodes.keySet());
        disconnectedIds.removeIf(id -> reachableFromStart.contains(id) && canReachEnd.contains(id));
        if (!disconnectedIds.isEmpty()) {
            throw disconnected("orphan or dead-end flow nodes: " + disconnectedIds);
        }

        Set<String> shapedElements = new HashSet<>();
        Set<String> edgedElements = new HashSet<>();

        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String localName = localName(element);
            if ("BPMNShape".equals(localName)) {
                if (element.getElementsByTagNameNS("*", "Bounds").getLength() > 0) {
                    shapedElements.add(element.getAttribute("bpmnElement"));
                }
            } else if ("BPMNEdge".equals(localName)) {
                NodeList waypoints = element.getElementsByTagNameNS("*", "waypoint");
                if (waypoints.getLength() >= 2) {
                    Element first = (Element) waypoints.item(0);
                    Element last = (Element) waypoints.item(waypoints.getLength() - 1);
                    if (sameWaypoint(first, last)) {
                        throw new AiGenerationException("AI_BPMN_MISSING_DI",
                                "AI generated BPMN has a zero-length diagram edge for sequenceFlow '"
                                + element.getAttribute("bpmnElement") + "'");
                    }
                }
                edgedElements.add(element.getAttribute("bpmnElement"));
            }
        }

        Set<String> missingShapes = new HashSet<>(flowNodes.keySet());
        missingShapes.removeAll(shapedElements);
        Set<String> missingEdges = new HashSet<>(sequenceFlowIds);
        missingEdges.removeAll(edgedElements);
        if (!missingShapes.isEmpty() || !missingEdges.isEmpty()) {
            throw new AiGenerationException("AI_BPMN_MISSING_DI",
                    "AI generated BPMN has incomplete diagram connectivity. Missing shapes="
                    + missingShapes + ", missing edges=" + missingEdges);
        }
        } catch (AiGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new AiGenerationException("AI_BPMN_INVALID_XML",
                    "AI generated BPMN XML could not be validated: " + e.getMessage());
        }
    }

    private boolean sameWaypoint(Element first, Element last) {
        try {
            double firstX = Double.parseDouble(first.getAttribute("x"));
            double firstY = Double.parseDouble(first.getAttribute("y"));
            double lastX = Double.parseDouble(last.getAttribute("x"));
            double lastY = Double.parseDouble(last.getAttribute("y"));
            return Double.compare(firstX, lastX) == 0 && Double.compare(firstY, lastY) == 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private Document parseBpmnSecurely(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private Set<String> traverse(Set<String> roots, Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            queue.addAll(graph.getOrDefault(current, Set.of()));
        }
        return visited;
    }

    private AiGenerationException disconnected(String detail) {
        return new AiGenerationException("AI_BPMN_DISCONNECTED_NODES",
                "AI generated BPMN is not a complete Start-to-End graph: " + detail);
    }

    private AiGenerationException invalidFormBinding(String detail) {
        return new AiGenerationException("AI_FORM_STAGE_BINDING_INVALID",
                "AI generated forms are not correctly bound to BPMN user tasks: " + detail);
    }

    private AiGenerationException invalidActionBinding(String detail) {
        return new AiGenerationException("AI_ACTION_STAGE_BINDING_INVALID",
                "AI generated actions are not correctly bound to BPMN user tasks: " + detail);
    }

    private String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getTagName();
    }

    private static String buildFallbackBpmn(String rawName) {
        String processId = rawName.toLowerCase().replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (processId.isEmpty()) {
            processId = "generated_process";
        }
        processId = processId + "_process";
        String processName = rawName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<bpmn:definitions xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xmlns:bpmn=\"" + BPMN_MODEL_NAMESPACE + "\" "
                + "xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" "
                + "xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\" "
                + "xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\" "
                + "id=\"Definitions_1\" targetNamespace=\"http://platform.local/bpmn\">\n"
                + "  <bpmn:process id=\"" + processId + "\" name=\"" + processName + "\" isExecutable=\"true\">\n"
                + "    <bpmn:startEvent id=\"StartEvent_1\" name=\"Start\"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>\n"
                + "    <bpmn:endEvent id=\"EndEvent_1\" name=\"End\"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:endEvent>\n"
                + "    <bpmn:sequenceFlow id=\"Flow_1\" sourceRef=\"StartEvent_1\" targetRef=\"EndEvent_1\"/>\n"
                + "  </bpmn:process>\n"
                + "  <bpmndi:BPMNDiagram id=\"BPMNDiagram_1\"><bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"" + processId + "\">\n"
                + "    <bpmndi:BPMNShape id=\"StartEvent_1_di\" bpmnElement=\"StartEvent_1\"><dc:Bounds x=\"152\" y=\"102\" width=\"36\" height=\"36\"/></bpmndi:BPMNShape>\n"
                + "    <bpmndi:BPMNShape id=\"EndEvent_1_di\" bpmnElement=\"EndEvent_1\"><dc:Bounds x=\"302\" y=\"102\" width=\"36\" height=\"36\"/></bpmndi:BPMNShape>\n"
                + "    <bpmndi:BPMNEdge id=\"Flow_1_di\" bpmnElement=\"Flow_1\"><di:waypoint x=\"188\" y=\"120\"/><di:waypoint x=\"302\" y=\"120\"/></bpmndi:BPMNEdge>\n"
                + "  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>\n"
                + "</bpmn:definitions>";
    }

    private static String tryDecodeBase64(String xml) {
        try {
            byte[] decoded = Base64.getDecoder().decode(xml.replaceAll("\\s", ""));
            return stripBom(new String(decoded, StandardCharsets.UTF_8)).trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** BOM 写成转义序列：裸 U+FEFF 在源码里不可见，编辑器一保存就可能被吃掉。 */
    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == BOM ? s.substring(1) : s;
    }

    // ==================== 回复正文 ====================

    /** 对话气泡里不该出现协议标记，逐个剥掉后返回。 */
    private static String stripMarkers(String reply) {
        String cleaned = REQUIREMENTS_DOC.matcher(reply).replaceAll("");
        cleaned = DESIGN_DOC.matcher(cleaned).replaceAll("");
        cleaned = GENERATED_DATA.matcher(cleaned).replaceAll("");
        return cleaned.replace(PHASE_COMPLETE_MARKER, "").trim();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AiGenerationException("AI_RESPONSE_PARSE_FAILED",
                    "Failed to re-serialize a generated defaultValue: " + e.getMessage());
        }
    }
}
