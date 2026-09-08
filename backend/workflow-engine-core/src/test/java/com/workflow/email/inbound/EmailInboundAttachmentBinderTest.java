package com.workflow.email.inbound;

import com.workflow.client.DeveloperWorkstationFileClient;
import com.workflow.email.extract.EmailAttachment;
import com.workflow.email.extract.EmailExtractionSpec;
import com.workflow.email.extract.EmailExtractionSpec.FieldRule;
import com.workflow.email.extract.EmailExtractionSpec.RuleType;
import com.workflow.email.extract.EmailExtractionSpec.Source;
import com.workflow.email.extract.EmailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailInboundAttachmentBinderTest {

    private DeveloperWorkstationFileClient fileClient;
    private EmailInboundAttachmentBinder binder;

    @BeforeEach
    void setUp() {
        fileClient = mock(DeveloperWorkstationFileClient.class);
        binder = new EmailInboundAttachmentBinder(fileClient);
    }

    private EmailExtractionSpec spec(boolean required) {
        FieldRule rule = new FieldRule();
        rule.setTarget("quote_files");
        rule.setSource(Source.ATTACHMENTS);
        rule.setType(RuleType.DIRECT);
        rule.setRequired(required);
        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of(rule));
        return spec;
    }

    @Test
    void noMapping_doesNotUpload() {
        EmailExtractionSpec spec = new EmailExtractionSpec();
        spec.setFields(List.of());
        EmailMessage email = new EmailMessage(
                "m0", "s", "a@b.com", "body", null, Map.of(),
                List.of(new EmailAttachment("a.pdf", "application/pdf", "x".getBytes())));

        EmailInboundAttachmentBinder.BindResult result = binder.bind(email, spec);

        assertThat(result.fields()).isEmpty();
        verify(fileClient, never()).upload(any(), any(), any());
    }

    @Test
    void oneAttachment_writesUrl() {
        when(fileClient.upload(eq("a.pdf"), any(), any()))
                .thenReturn(Optional.of(new DeveloperWorkstationFileClient.UploadedFileRef(
                        "/api/v1/upload/files/a.pdf", "a.pdf")));
        EmailMessage email = new EmailMessage(
                "m1", "s", "a@b.com", "body", null, Map.of(),
                List.of(new EmailAttachment("a.pdf", "application/pdf", "x".getBytes())));

        EmailInboundAttachmentBinder.BindResult result = binder.bind(email, spec(false));

        assertThat(result.fields()).containsEntry("quote_files", "/api/v1/upload/files/a.pdf");
        assertThat(result.attachmentNames()).containsExactly("a.pdf");
        assertThat(result.missingRequired()).isEmpty();
    }

    @Test
    void twoAttachments_writesJsonArray() {
        when(fileClient.upload(eq("a.pdf"), any(), any()))
                .thenReturn(Optional.of(new DeveloperWorkstationFileClient.UploadedFileRef(
                        "/api/v1/upload/files/a.pdf", "a.pdf")));
        when(fileClient.upload(eq("b.docx"), any(), any()))
                .thenReturn(Optional.of(new DeveloperWorkstationFileClient.UploadedFileRef(
                        "/api/v1/upload/files/b.docx", "b.docx")));
        EmailMessage email = new EmailMessage(
                "m2", "s", "a@b.com", "body", null, Map.of(),
                List.of(
                        new EmailAttachment("a.pdf", "application/pdf", "x".getBytes()),
                        new EmailAttachment("b.docx", "application/vnd.openxmlformats", "y".getBytes())));

        EmailInboundAttachmentBinder.BindResult result = binder.bind(email, spec(false));

        assertThat((String) result.fields().get("quote_files")).startsWith("[");
        assertThat((String) result.fields().get("quote_files")).contains("b.docx");
    }

    @Test
    void required_andOversizedOnly_marksMissing() {
        byte[] huge = new byte[(int) EmailFileFieldValue.MAX_FILE_SIZE_BYTES + 1];
        EmailMessage email = new EmailMessage(
                "m3", "s", "a@b.com", "body", null, Map.of(),
                List.of(new EmailAttachment("big.bin", "application/octet-stream", huge)));

        EmailInboundAttachmentBinder.BindResult result = binder.bind(email, spec(true));

        assertThat(result.fields()).isEmpty();
        assertThat(result.missingRequired()).contains("quote_files");
        assertThat(result.attachmentErrors()).isNotEmpty();
        verify(fileClient, never()).upload(any(), any(), any());
    }
}
