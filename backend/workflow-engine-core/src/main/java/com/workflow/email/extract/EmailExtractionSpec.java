package com.workflow.email.extract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Declarative extraction rules produced by the visual-pick / AI-assist designer (no code).
 *
 * <p>Serialized as the {@code extractionRules} JSON stored on the email Start Event. The runtime
 * interpreter ({@link EmailFieldExtractor}) executes it without any scripting engine.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmailExtractionSpec {

    /** Main-table field rules. */
    private List<FieldRule> fields;

    /** Sub-table (one-to-many) rules; each produces rows for {@code __subTables__[bindingId]}. */
    private List<SubTableRule> subTables;

    public List<FieldRule> getFields() {
        return fields;
    }

    public void setFields(List<FieldRule> fields) {
        this.fields = fields;
    }

    public List<SubTableRule> getSubTables() {
        return subTables;
    }

    public void setSubTables(List<SubTableRule> subTables) {
        this.subTables = subTables;
    }

    /** Which part of the email a rule reads from. */
    public enum Source {
        SUBJECT,
        /** Sender address (raw From header). */
        FROM,
        /** Primary recipients (raw To header). */
        TO,
        /** Carbon-copy recipients (raw Cc header). */
        CC,
        /** Reply-To header when present. */
        REPLY_TO,
        /** Sent date/time (ISO-8601 instant when available from IMAP). */
        DATE,
        /** Provider message id (Message-ID header or IMAP uid fallback). */
        MESSAGE_ID,
        /** Plain-text body only. */
        TEXT,
        /** HTML body converted to text only. */
        HTML,
        /** Plain-text + HTML body (QQ forwards often have HTML only). */
        TEXT_AND_HTML,
        /**
         * All non-inline attachments of this email, stored into one FILE field
         * ({@link RuleType#DIRECT} only).
         */
        ATTACHMENTS,
        HEADER,
        CONST
    }

    /** How a value is located inside the chosen source. */
    public enum RuleType {
        /** Map the entire chosen source without parsing (email attributes and full subject). */
        DIRECT,
        /** Fixed literal value (uses {@code value}). */
        CONST,
        /** Text after a label up to end-of-line (uses {@code label}). */
        LABEL,
        /** Text between {@code before} and {@code after} anchors. */
        BETWEEN,
        /** Regex with capture {@code group} (internal; not authored by hand by end users). */
        REGEX,
        /** Raw header value (uses {@code header}). */
        HEADER
    }

    /** Post-processing applied to a captured string, in order. */
    public enum PostProcess {
        TRIM, DIGITS_ONLY, STRIP_CURRENCY, UPPER, LOWER
    }

    /** One main-table field extraction rule. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldRule {
        private String target;
        private Source source;
        private RuleType type;
        private String value;
        private String label;
        private String before;
        private String after;
        private String pattern;
        private Integer group;
        private String header;
        private boolean required;
        private List<PostProcess> postProcess;

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public Source getSource() {
            return source;
        }

        public void setSource(Source source) {
            this.source = source;
        }

        public RuleType getType() {
            return type;
        }

        public void setType(RuleType type) {
            this.type = type;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getBefore() {
            return before;
        }

        public void setBefore(String before) {
            this.before = before;
        }

        public String getAfter() {
            return after;
        }

        public void setAfter(String after) {
            this.after = after;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public Integer getGroup() {
            return group;
        }

        public void setGroup(Integer group) {
            this.group = group;
        }

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public List<PostProcess> getPostProcess() {
            return postProcess;
        }

        public void setPostProcess(List<PostProcess> postProcess) {
            this.postProcess = postProcess;
        }
    }

    /** Maps an HTML table (or repeated block) to sub-table rows. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubTableRule {
        private String bindingId;
        private String tableSelector;
        private Integer tableIndex;
        private boolean headerRow;
        private List<ColumnRule> columns;

        public String getBindingId() {
            return bindingId;
        }

        public void setBindingId(String bindingId) {
            this.bindingId = bindingId;
        }

        public String getTableSelector() {
            return tableSelector;
        }

        public void setTableSelector(String tableSelector) {
            this.tableSelector = tableSelector;
        }

        public Integer getTableIndex() {
            return tableIndex;
        }

        public void setTableIndex(Integer tableIndex) {
            this.tableIndex = tableIndex;
        }

        public boolean isHeaderRow() {
            return headerRow;
        }

        public void setHeaderRow(boolean headerRow) {
            this.headerRow = headerRow;
        }

        public List<ColumnRule> getColumns() {
            return columns;
        }

        public void setColumns(List<ColumnRule> columns) {
            this.columns = columns;
        }
    }

    /** Maps one HTML table column (by index) to a sub-table field. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnRule {
        private String field;
        private Integer columnIndex;
        private String constValue;
        private List<PostProcess> postProcess;

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public Integer getColumnIndex() {
            return columnIndex;
        }

        public void setColumnIndex(Integer columnIndex) {
            this.columnIndex = columnIndex;
        }

        public String getConstValue() {
            return constValue;
        }

        public void setConstValue(String constValue) {
            this.constValue = constValue;
        }

        public List<PostProcess> getPostProcess() {
            return postProcess;
        }

        public void setPostProcess(List<PostProcess> postProcess) {
            this.postProcess = postProcess;
        }
    }
}
