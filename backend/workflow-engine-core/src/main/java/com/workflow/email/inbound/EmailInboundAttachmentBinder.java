package com.workflow.email.inbound;

import com.workflow.client.DeveloperWorkstationFileClient;
import com.workflow.email.extract.EmailAttachment;
import com.workflow.email.extract.EmailExtractionSpec;
import com.workflow.email.extract.EmailExtractionSpec.FieldRule;
import com.workflow.email.extract.EmailMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Uploads inbound attachments through DW {@code POST /upload} and writes the FILE field value
 * for every {@code ATTACHMENTS} mapping row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailInboundAttachmentBinder {

    private final DeveloperWorkstationFileClient fileClient;

    public BindResult bind(EmailMessage email, EmailExtractionSpec spec) {
        List<FieldRule> rules = attachmentRules(spec);
        if (rules.isEmpty()) {
            return BindResult.empty();
        }
        List<String> names = email.attachmentNames();
        List<String> errors = new ArrayList<>();
        List<EmailFileFieldValue.StoredUpload> stored = uploadEligible(email.attachments(), errors);
        String fileValue = EmailFileFieldValue.persist(stored, EmailFileFieldValue.DEFAULT_MAX_FILES);
        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (FieldRule rule : rules) {
            if (StringUtils.hasText(fileValue)) {
                fields.put(rule.getTarget(), fileValue);
            } else if (rule.isRequired()) {
                missing.add(rule.getTarget());
            }
        }
        return new BindResult(fields, names, errors, missing);
    }

    private List<EmailFileFieldValue.StoredUpload> uploadEligible(
            List<EmailAttachment> attachments, List<String> errors) {
        List<EmailFileFieldValue.StoredUpload> stored = new ArrayList<>();
        if (attachments == null) {
            return stored;
        }
        for (EmailAttachment attachment : attachments) {
            Optional<EmailFileFieldValue.StoredUpload> uploaded = uploadOne(attachment, stored.size(), errors);
            uploaded.ifPresent(stored::add);
        }
        return stored;
    }

    private Optional<EmailFileFieldValue.StoredUpload> uploadOne(
            EmailAttachment attachment, int alreadyStored, List<String> errors) {
        String name = StringUtils.hasText(attachment.filename()) ? attachment.filename() : "attachment";
        if (attachment.size() == 0) {
            errors.add(name + ": empty");
            return Optional.empty();
        }
        if (attachment.size() > EmailFileFieldValue.MAX_FILE_SIZE_BYTES) {
            errors.add(name + ": exceeds 50MB");
            return Optional.empty();
        }
        if (alreadyStored >= EmailFileFieldValue.DEFAULT_MAX_FILES) {
            errors.add(name + ": maxFiles exceeded");
            return Optional.empty();
        }
        Optional<DeveloperWorkstationFileClient.UploadedFileRef> uploaded =
                fileClient.upload(name, attachment.contentType(), attachment.content());
        if (uploaded.isEmpty()) {
            errors.add(name + ": upload failed");
            return Optional.empty();
        }
        return Optional.of(new EmailFileFieldValue.StoredUpload(uploaded.get().url(), uploaded.get().name()));
    }

    private static List<FieldRule> attachmentRules(EmailExtractionSpec spec) {
        List<FieldRule> rules = new ArrayList<>();
        if (spec == null || spec.getFields() == null) {
            return rules;
        }
        for (FieldRule rule : spec.getFields()) {
            if (rule != null
                    && rule.getSource() == EmailExtractionSpec.Source.ATTACHMENTS
                    && StringUtils.hasText(rule.getTarget())) {
                rules.add(rule);
            }
        }
        return rules;
    }

    public record BindResult(
            Map<String, Object> fields,
            List<String> attachmentNames,
            List<String> attachmentErrors,
            List<String> missingRequired) {
        public static BindResult empty() {
            return new BindResult(Map.of(), List.of(), List.of(), List.of());
        }
    }
}
