package com.workflow.email.inbound;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailFileFieldValueTest {

    @Test
    void persist_empty_returnsEmptyString() {
        assertThat(EmailFileFieldValue.persist(List.of(), 10)).isEmpty();
    }

    @Test
    void persist_oneFile_returnsUrl() {
        assertThat(EmailFileFieldValue.persist(
                List.of(new EmailFileFieldValue.StoredUpload("/api/v1/upload/files/a.pdf", "a.pdf")),
                10)).isEqualTo("/api/v1/upload/files/a.pdf");
    }

    @Test
    void persist_twoFiles_returnsJsonArray() {
        String value = EmailFileFieldValue.persist(List.of(
                new EmailFileFieldValue.StoredUpload("/api/v1/upload/files/a.pdf", "a.pdf"),
                new EmailFileFieldValue.StoredUpload("/api/v1/upload/files/b.docx", "b.docx")
        ), 10);
        assertThat(value).contains("\"url\":\"/api/v1/upload/files/a.pdf\"");
        assertThat(value).contains("\"name\":\"b.docx\"");
        assertThat(value).startsWith("[");
    }
}
