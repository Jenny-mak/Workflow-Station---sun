package com.workflow.email.inbound;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ImapInboundMailClientExtractTest {

    @Test
    void extractParts_recursesIntoNestedForwardedMessage() throws Exception {
        Session session = Session.getInstance(new Properties());

        MimeBodyPart innerHtml = new MimeBodyPart();
        innerHtml.setContent("<table><tr><td>Case No: ABC-99</td></tr></table>", "text/html; charset=utf-8");

        MimeMultipart innerMultipart = new MimeMultipart("alternative");
        innerMultipart.addBodyPart(innerHtml);

        MimeMessage inner = new MimeMessage(session);
        inner.setContent(innerMultipart);
        inner.saveChanges();

        MimeBodyPart forwardWrapper = new MimeBodyPart();
        forwardWrapper.setContent(inner, "message/rfc822");

        MimeBodyPart outerText = new MimeBodyPart();
        outerText.setText("Forwarded message");

        MimeMultipart outer = new MimeMultipart("mixed");
        outer.addBodyPart(outerText);
        outer.addBodyPart(forwardWrapper);

        MimeMessage message = new MimeMessage(session);
        message.setContent(outer);
        message.saveChanges();

        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        new ImapInboundMailClient().extractParts(message, text, html);

        assertThat(html.toString()).contains("Case No: ABC-99");
        assertThat(text.toString()).contains("Forwarded message");
    }

    @Test
    void extractParts_keepsNamedAttachmentAndSkipsInlineCid() throws Exception {
        Session session = Session.getInstance(new Properties());

        MimeBodyPart text = new MimeBodyPart();
        text.setText("Please see attached");

        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setFileName("quote.pdf");
        pdf.setDataHandler(new DataHandler(new ByteArrayDataSource("pdf-bytes".getBytes(), "application/pdf")));
        pdf.setDisposition(MimeBodyPart.ATTACHMENT);

        MimeBodyPart cidImage = new MimeBodyPart();
        cidImage.setFileName("logo.png");
        cidImage.setDataHandler(new DataHandler(new ByteArrayDataSource("png-bytes".getBytes(), "image/png")));
        cidImage.setDisposition(MimeBodyPart.INLINE);
        cidImage.setHeader("Content-ID", "<logo@local>");

        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(text);
        mixed.addBodyPart(pdf);
        mixed.addBodyPart(cidImage);

        MimeMessage message = new MimeMessage(session);
        message.setContent(mixed);
        message.saveChanges();

        StringBuilder plain = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<com.workflow.email.extract.EmailAttachment> attachments = new ArrayList<>();
        new ImapInboundMailClient().extractParts(message, plain, html, attachments);

        assertThat(plain.toString()).contains("Please see attached");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).filename()).isEqualTo("quote.pdf");
        assertThat(attachments.get(0).content()).isEqualTo("pdf-bytes".getBytes());
    }
}
