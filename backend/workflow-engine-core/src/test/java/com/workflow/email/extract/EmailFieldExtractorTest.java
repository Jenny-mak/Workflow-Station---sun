package com.workflow.email.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.email.extract.EmailExtractionSpec.ColumnRule;
import com.workflow.email.extract.EmailExtractionSpec.FieldRule;
import com.workflow.email.extract.EmailExtractionSpec.PostProcess;
import com.workflow.email.extract.EmailExtractionSpec.RuleType;
import com.workflow.email.extract.EmailExtractionSpec.Source;
import com.workflow.email.extract.EmailExtractionSpec.SubTableRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the no-code email extraction interpreter: text anchors (LABEL/BETWEEN/REGEX),
 * CONST/HEADER, post-processing, HTML table -> sub-table rows, and the missing-required gate.
 */
class EmailFieldExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FieldRule field(String target, Source source, RuleType type) {
        FieldRule rule = new FieldRule();
        rule.setTarget(target);
        rule.setSource(source);
        rule.setType(type);
        return rule;
    }

    @Test
    void extractsLabelFromPlainText() {
        EmailMessage email = new EmailMessage("m1", "新案件通知", "a@b.com",
                "尊敬的客户\n案件编号：2026001\n金额：HKD 1,200.00", null, Map.of());

        FieldRule rule = field("case_number", Source.TEXT, RuleType.LABEL);
        rule.setLabel("案件编号：");
        rule.setRequired(true);

        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(rule));

        ExtractionResult result = EmailFieldExtractor.extract(email, spec);

        assertThat(result.getFields()).containsEntry("case_number", "2026001");
        assertThat(result.hasMissingRequired()).isFalse();
    }

    @Test
    void extractsBetweenFromSubject() {
        EmailMessage email = new EmailMessage("m2", "[MCY-2026001] New case", "a@b.com",
                null, null, Map.of());

        FieldRule rule = field("case_number", Source.SUBJECT, RuleType.BETWEEN);
        rule.setBefore("[MCY-");
        rule.setAfter("]");

        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(rule));

        ExtractionResult result = EmailFieldExtractor.extract(email, spec);

        assertThat(result.getFields()).containsEntry("case_number", "2026001");
    }

    @Test
    void extractsRegexWithPostProcessStripCurrency() {
        EmailMessage email = new EmailMessage("m3", "s", "a@b.com",
                "金额：HKD 1,200.00 总计", null, Map.of());

        FieldRule rule = field("amount", Source.TEXT, RuleType.REGEX);
        rule.setPattern("金额：HKD ([0-9,.]+)");
        rule.setGroup(1);
        rule.setPostProcess(List.of(PostProcess.STRIP_CURRENCY));

        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(rule));

        ExtractionResult result = EmailFieldExtractor.extract(email, spec);

        assertThat(result.getFields()).containsEntry("amount", "1,200.00");
    }

    @Test
    void constAndHeaderSources() {
        EmailMessage email = new EmailMessage("m4", "s", "sender@bank.com",
                "body", null, Map.of("from", "sender@bank.com"));

        FieldRule constRule = field("source", Source.CONST, RuleType.CONST);
        constRule.setValue("EMAIL");
        FieldRule headerRule = field("sender_email", Source.HEADER, RuleType.HEADER);
        headerRule.setHeader("From");

        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(constRule, headerRule));

        ExtractionResult result = EmailFieldExtractor.extract(email, spec);

        assertThat(result.getFields()).containsEntry("source", "EMAIL");
        assertThat(result.getFields()).containsEntry("sender_email", "sender@bank.com");
    }

    @Test
    void missingRequiredFieldIsReportedAndNotWritten() {
        EmailMessage email = new EmailMessage("m5", "no case here", "a@b.com",
                "nothing relevant", null, Map.of());

        FieldRule rule = field("case_number", Source.TEXT, RuleType.LABEL);
        rule.setLabel("案件编号：");
        rule.setRequired(true);

        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(rule));

        ExtractionResult result = EmailFieldExtractor.extract(email, spec);

        assertThat(result.getFields()).doesNotContainKey("case_number");
        assertThat(result.getMissingRequired()).containsExactly("case_number");
        assertThat(result.hasMissingRequired()).isTrue();
    }

    @Test
    void extractsHtmlTableIntoSubTableRows() {
        String html = "<html><body><table>"
                + "<tr><th>Card</th><th>Amount</th></tr>"
                + "<tr><td>4111-2222</td><td>HKD 100</td></tr>"
                + "<tr><td>5500-6677</td><td>HKD 250</td></tr>"
                + "</table></body></html>";
        EmailMessage email = new EmailMessage("m6", "s", "a@b.com", null, html, Map.of());

        ColumnRule card = new ColumnRule();
        card.setField("card_number");
        card.setColumnIndex(0);
        card.setPostProcess(List.of(PostProcess.DIGITS_ONLY));
        ColumnRule amount = new ColumnRule();
        amount.setField("amount");
        amount.setColumnIndex(1);
        amount.setPostProcess(List.of(PostProcess.STRIP_CURRENCY));

        SubTableRule subRule = new SubTableRule();
        subRule.setBindingId("271");
        subRule.setHeaderRow(true);
        subRule.setColumns(List.of(card, amount));

        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setSubTables(List.of(subRule));

        ExtractionResult result = EmailFieldExtractor.extract(email, spec);

        List<Map<String, Object>> rows = result.getSubTables().get("271");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("card_number", "41112222").containsEntry("amount", "100");
        assertThat(rows.get(1)).containsEntry("card_number", "55006677").containsEntry("amount", "250");
    }

    @Test
    void extractsRegexFromHtmlOnlyBodyViaTextAndHtmlSource() {
        String html = "<html><body><p>您好，本次操作校验码为1233333。您正在使用邮箱注册帐号。</p></body></html>";
        EmailMessage email = new EmailMessage("m-html", "校验码", "a@b.com", null, html, Map.of());

        FieldRule rule = field("case_number", Source.TEXT_AND_HTML, RuleType.REGEX);
        rule.setPattern("(?<=校验码为)\\d+");
        rule.setGroup(1);
        rule.setRequired(true);

        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(rule));

        ExtractionResult result = EmailFieldExtractor.extract(email, spec);

        assertThat(result.getFields()).containsEntry("case_number", "1233333");
        assertThat(result.hasMissingRequired()).isFalse();
    }

    @Test
    void textSourceAlsoScansHtmlWhenPlainTextMissing() {
        String html = "<html><body>校验码为9999888</body></html>";
        EmailMessage email = new EmailMessage("m-fallback", "s", "a@b.com", null, html, Map.of());

        FieldRule rule = field("code", Source.TEXT, RuleType.REGEX);
        rule.setPattern("(?<=校验码为)\\d+");
        rule.setGroup(1);

        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(rule));

        ExtractionResult result = EmailFieldExtractor.extract(email, spec);

        assertThat(result.getFields()).containsEntry("code", "9999888");
    }

    @Test
    void directEmailAttributesMapToMainTableFields() {
        EmailMessage email = new EmailMessage(
                "<msg-001@bank.com>",
                "Case notice",
                "Sender Name <sender@bank.com>",
                "body",
                null,
                Map.of(
                        "to", "Ops Team <ops@bank.com>",
                        "cc", "audit@bank.com",
                        "reply-to", "noreply@bank.com",
                        "date", "2026-09-08T08:30:00Z",
                        "message-id", "<msg-001@bank.com>"));

        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(
                directRule("sender_email", Source.FROM),
                directRule("recipient_email", Source.TO),
                directRule("cc_list", Source.CC),
                directRule("reply_address", Source.REPLY_TO),
                directRule("sent_at", Source.DATE),
                directRule("provider_message_id", Source.MESSAGE_ID),
                directRule("email_subject", Source.SUBJECT)));

        ExtractionResult result = EmailFieldExtractor.extract(email, spec);

        assertThat(result.getFields())
                .containsEntry("sender_email", "Sender Name <sender@bank.com>")
                .containsEntry("recipient_email", "Ops Team <ops@bank.com>")
                .containsEntry("cc_list", "audit@bank.com")
                .containsEntry("reply_address", "noreply@bank.com")
                .containsEntry("sent_at", "2026-09-08T08:30:00Z")
                .containsEntry("provider_message_id", "<msg-001@bank.com>")
                .containsEntry("email_subject", "Case notice");
    }

    private FieldRule directRule(String target, Source source) {
        FieldRule rule = field(target, source, RuleType.DIRECT);
        return rule;
    }

    @Test
    void specDeserializesFromJson() throws Exception {
        String json = """
                {
                  "fields": [
                    { "target": "case_number", "source": "TEXT", "type": "LABEL",
                      "label": "Case No: ", "required": true, "postProcess": ["TRIM"] }
                  ],
                  "subTables": [
                    { "bindingId": "271", "headerRow": true,
                      "columns": [ { "field": "card_number", "columnIndex": 0 } ] }
                  ]
                }
                """;

        EmailExtractionSpec spec = objectMapper.readValue(json, EmailExtractionSpec.class);

        assertThat(spec.getFields()).hasSize(1);
        assertThat(spec.getFields().get(0).getType()).isEqualTo(RuleType.LABEL);
        assertThat(spec.getFields().get(0).isRequired()).isTrue();
        assertThat(spec.getSubTables().get(0).getBindingId()).isEqualTo("271");

        EmailMessage email = new EmailMessage("m7", "s", "a@b.com",
                "Case No: ABC-99", null, Map.of());
        ExtractionResult result = EmailFieldExtractor.extract(email, spec);
        assertThat(result.getFields()).containsEntry("case_number", "ABC-99");
    }

    @Test
    void attachmentsRequired_withoutParts_marksMissing() {
        FieldRule rule = field("quote_files", Source.ATTACHMENTS, RuleType.DIRECT);
        rule.setRequired(true);
        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(rule));

        ExtractionResult result = EmailFieldExtractor.extract(
                new EmailMessage("m-att-0", "s", "a@b.com", "body", null, Map.of()), spec);

        assertThat(result.getFields()).doesNotContainKey("quote_files");
        assertThat(result.getMissingRequired()).contains("quote_files");
    }

    @Test
    void attachmentsRequired_withParts_doesNotMarkMissing() {
        FieldRule rule = field("quote_files", Source.ATTACHMENTS, RuleType.DIRECT);
        rule.setRequired(true);
        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(rule));
        EmailMessage email = new EmailMessage(
                "m-att-1", "s", "a@b.com", "body", null, Map.of(),
                List.of(new EmailAttachment("a.pdf", "application/pdf", "x".getBytes())));

        ExtractionResult result = EmailFieldExtractor.extract(email, spec);

        assertThat(result.getFields()).doesNotContainKey("quote_files");
        assertThat(result.hasMissingRequired()).isFalse();
    }
}
