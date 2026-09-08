package com.workflow.email.extract;

import com.workflow.email.extract.EmailExtractionSpec.ColumnRule;
import com.workflow.email.extract.EmailExtractionSpec.FieldRule;
import com.workflow.email.extract.EmailExtractionSpec.PostProcess;
import com.workflow.email.extract.EmailExtractionSpec.SubTableRule;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes an {@link EmailExtractionSpec} against an {@link EmailMessage} to produce main-table
 * fields and sub-table rows — the no-code runtime for visual-pick / AI-assist extraction rules.
 *
 * <p>Stateless and side-effect free; safe to reuse. Regex patterns are compiled per call but
 * guarded by {@code REGEX_TIMEOUT_CHARS} input truncation to avoid pathological inputs.
 */
@Slf4j
public final class EmailFieldExtractor {

    /** Hard cap on text length fed to regex/anchor scans, guarding against huge bodies. */
    private static final int MAX_SCAN_CHARS = 200_000;

    private EmailFieldExtractor() {
    }

    /** Extracts fields and sub-table rows; never throws on rule errors (records misses instead). */
    public static ExtractionResult extract(EmailMessage email, EmailExtractionSpec spec) {
        ExtractionResult result = new ExtractionResult();
        if (email == null || spec == null) {
            return result;
        }
        extractFields(email, spec, result);
        extractSubTables(email, spec, result);
        return result;
    }

    private static void extractFields(EmailMessage email, EmailExtractionSpec spec, ExtractionResult result) {
        if (spec.getFields() == null) {
            return;
        }
        for (FieldRule rule : spec.getFields()) {
            if (rule == null || !StringUtils.hasText(rule.getTarget())) {
                continue;
            }
            String value = applyPostProcess(extractFieldValue(email, rule), rule.getPostProcess());
            if (StringUtils.hasText(value)) {
                result.getFields().put(rule.getTarget(), value);
            } else if (rule.isRequired()) {
                result.getMissingRequired().add(rule.getTarget());
            }
        }
    }

    private static String extractFieldValue(EmailMessage email, FieldRule rule) {
        if (rule.getType() == null) {
            return null;
        }
        return switch (rule.getType()) {
            case DIRECT -> directValue(email, rule.getSource());
            case CONST -> rule.getValue();
            case HEADER -> readHeader(email, rule.getHeader());
            case LABEL -> extractByLabel(sourceText(email, rule.getSource()), rule.getLabel());
            case BETWEEN -> extractBetween(sourceText(email, rule.getSource()), rule.getBefore(), rule.getAfter());
            case REGEX -> extractByRegex(sourceText(email, rule.getSource()), rule.getPattern(),
                    rule.getGroup() != null ? rule.getGroup() : 1);
        };
    }

    /** Returns the full email attribute or source text for {@link EmailExtractionSpec.RuleType#DIRECT}. */
    private static String directValue(EmailMessage email, EmailExtractionSpec.Source source) {
        String attribute = emailAttributeValue(email, source);
        if (StringUtils.hasText(attribute)) {
            return attribute;
        }
        if (source == EmailExtractionSpec.Source.SUBJECT) {
            return email.subject();
        }
        if (source == EmailExtractionSpec.Source.TEXT
                || source == EmailExtractionSpec.Source.TEXT_AND_HTML
                || source == EmailExtractionSpec.Source.HTML) {
            return sourceText(email, source);
        }
        return null;
    }

    private static String emailAttributeValue(EmailMessage email, EmailExtractionSpec.Source source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case FROM -> email.from();
            case TO -> readHeader(email, "to");
            case CC -> readHeader(email, "cc");
            case REPLY_TO -> readHeader(email, "reply-to");
            case DATE -> readHeader(email, "date");
            case MESSAGE_ID -> email.messageId();
            case SUBJECT -> email.subject();
            default -> null;
        };
    }

    private static String sourceText(EmailMessage email, EmailExtractionSpec.Source source) {
        if (source == null) {
            return combinedTextAndHtml(email);
        }
        return switch (source) {
            case SUBJECT -> email.subject();
            case FROM, TO, CC, REPLY_TO, DATE, MESSAGE_ID -> emailAttributeValue(email, source);
            case HTML -> htmlToText(email.html());
            case TEXT, TEXT_AND_HTML -> combinedTextAndHtml(email);
            case HEADER, CONST -> truncate(email.text());
        };
    }

    /** Merges plain-text and HTML-derived text so forwarded/HTML-only messages still match. */
    static String combinedTextAndHtml(EmailMessage email) {
        String plain = truncate(email.text());
        String html = htmlToText(email.html());
        boolean hasPlain = StringUtils.hasText(plain);
        boolean hasHtml = StringUtils.hasText(html);
        if (!hasPlain) {
            return hasHtml ? html : null;
        }
        if (!hasHtml) {
            return plain;
        }
        String p = plain.trim();
        String h = html.trim();
        if (p.equals(h) || p.contains(h) || h.contains(p)) {
            return p.length() >= h.length() ? p : h;
        }
        return truncate(p + "\n" + h);
    }

    private static String readHeader(EmailMessage email, String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        String normalized = header.toLowerCase();
        if ("from".equals(normalized)) {
            return email.from();
        }
        if ("message-id".equals(normalized) && StringUtils.hasText(email.messageId())) {
            return email.messageId();
        }
        if (email.headers() == null) {
            return null;
        }
        return email.headers().get(normalized);
    }

    /** Returns the text after {@code label} on the same line, trimmed. */
    static String extractByLabel(String text, String label) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(label)) {
            return null;
        }
        int idx = text.indexOf(label);
        if (idx < 0) {
            return null;
        }
        int start = idx + label.length();
        int end = start;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
            end++;
        }
        String captured = text.substring(start, end).trim();
        return captured.isEmpty() ? null : captured;
    }

    /** Returns the text between {@code before} and {@code after} anchors (after optional). */
    static String extractBetween(String text, String before, String after) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(before)) {
            return null;
        }
        int idx = text.indexOf(before);
        if (idx < 0) {
            return null;
        }
        int start = idx + before.length();
        int end;
        if (StringUtils.hasText(after)) {
            end = text.indexOf(after, start);
            if (end < 0) {
                end = text.length();
            }
        } else {
            end = text.length();
        }
        String captured = text.substring(start, end).trim();
        return captured.isEmpty() ? null : captured;
    }

    static String extractByRegex(String text, String pattern, int group) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(pattern)) {
            return null;
        }
        try {
            Matcher matcher = Pattern.compile(pattern).matcher(text);
            if (!matcher.find()) {
                return null;
            }
            int groupCount = matcher.groupCount();
            if (group > 0 && group <= groupCount) {
                String captured = matcher.group(group);
                return captured != null ? captured.trim() : null;
            }
            // Lookbehind / whole-match patterns have no capture group — use full match.
            String captured = matcher.group(0);
            return captured != null ? captured.trim() : null;
        } catch (RuntimeException e) {
            log.warn("Email extraction regex failed (pattern omitted): {}", e.getMessage());
        }
        return null;
    }

    private static void extractSubTables(EmailMessage email, EmailExtractionSpec spec, ExtractionResult result) {
        if (spec.getSubTables() == null || !StringUtils.hasText(email.html())) {
            return;
        }
        Document doc = Jsoup.parse(email.html());
        for (SubTableRule rule : spec.getSubTables()) {
            if (rule == null || !StringUtils.hasText(rule.getBindingId()) || rule.getColumns() == null) {
                continue;
            }
            List<Map<String, Object>> rows = extractTableRows(doc, rule);
            if (!rows.isEmpty()) {
                result.getSubTables().put(rule.getBindingId().trim(), rows);
            }
        }
    }

    private static List<Map<String, Object>> extractTableRows(Document doc, SubTableRule rule) {
        String selector = StringUtils.hasText(rule.getTableSelector()) ? rule.getTableSelector() : "table";
        Elements tables = doc.select(selector);
        int index = rule.getTableIndex() != null ? rule.getTableIndex() : 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        if (tables.isEmpty() || index < 0 || index >= tables.size()) {
            return rows;
        }
        Elements trs = tables.get(index).select("tr");
        int startRow = rule.isHeaderRow() ? 1 : 0;
        for (int i = startRow; i < trs.size(); i++) {
            Elements cells = trs.get(i).select("td,th");
            Map<String, Object> row = buildRow(cells, rule.getColumns());
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> buildRow(Elements cells, List<ColumnRule> columns) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ColumnRule column : columns) {
            if (column == null || !StringUtils.hasText(column.getField())) {
                continue;
            }
            String raw;
            if (StringUtils.hasText(column.getConstValue())) {
                raw = column.getConstValue();
            } else if (column.getColumnIndex() != null
                    && column.getColumnIndex() >= 0
                    && column.getColumnIndex() < cells.size()) {
                raw = cells.get(column.getColumnIndex()).text();
            } else {
                continue;
            }
            String value = applyPostProcess(raw, column.getPostProcess());
            if (StringUtils.hasText(value)) {
                row.put(column.getField(), value);
            }
        }
        return row;
    }

    static String applyPostProcess(String value, List<PostProcess> steps) {
        if (value == null || steps == null || steps.isEmpty()) {
            return value;
        }
        String result = value;
        for (PostProcess step : steps) {
            if (step == null) {
                continue;
            }
            result = switch (step) {
                case TRIM -> result.trim();
                case DIGITS_ONLY -> result.replaceAll("[^0-9]", "");
                case STRIP_CURRENCY -> result.replaceAll("[^0-9.,-]", "").trim();
                case UPPER -> result.toUpperCase();
                case LOWER -> result.toLowerCase();
            };
        }
        return result;
    }

    private static String htmlToText(String html) {
        if (!StringUtils.hasText(html)) {
            return null;
        }
        return truncate(Jsoup.parse(html).text());
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
    }
}
