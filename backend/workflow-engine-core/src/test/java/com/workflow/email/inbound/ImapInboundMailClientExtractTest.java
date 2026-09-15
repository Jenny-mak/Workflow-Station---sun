package com.workflow.email.inbound;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ImapInboundMailClientExtractTest {

    @Test
    void formatAddress_decodesMimeEncodedWordDisplayName() throws Exception {
        InternetAddress encoded = new InternetAddress(
                "inbound.user@example.com",
                "=?utf-8?B?QWxpY2U=?=",
                "utf-8");

        assertThat(ImapInboundMailClient.formatAddress(encoded))
                .isEqualTo("Alice <inbound.user@example.com>");
    }

    @Test
    void formatAddresses_decodesMultipleRecipients() throws Exception {
        InternetAddress first = new InternetAddress(
                "inbound.user@example.com",
                "=?utf-8?B?QWxpY2U=?=",
                "utf-8");
        InternetAddress second = new InternetAddress("ops@example.com", "Ops Team", "utf-8");

        assertThat(ImapInboundMailClient.formatAddresses(new InternetAddress[] {first, second}))
                .isEqualTo("Alice <inbound.user@example.com>, Ops Team <ops@example.com>");
    }

    @Test
    void decodeAddressText_decodesRawEncodedWordHeaderFragment() throws Exception {
        assertThat(ImapInboundMailClient.decodeAddressText(
                "=?utf-8?B?QWxpY2U=?= <inbound.user@example.com>"))
                .isEqualTo("Alice <inbound.user@example.com>");
    }

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
    void extractParts_rfc822WithAttachmentDisposition_doesNotInlineBody() throws Exception {
        MimeMessage message = mixedOuterTextAndRfc822Attachment();

        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<com.workflow.email.extract.EmailAttachment> attachments = new ArrayList<>();
        new ImapInboundMailClient().extractParts(message, text, html, attachments);

        assertThat(text.toString()).contains("Please process this request");
        assertThat(text.toString()).doesNotContain("SECRET_ATTACHED_MSG_BODY");
        assertThat(html.toString()).doesNotContain("SECRET_ATTACHED_MSG_BODY");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).filename()).isEqualTo("message.eml");
        assertThat(attachments.get(0).content().length).isGreaterThan(0);
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

    @Test
    void captureRawRfc822_writesOriginalMimeBytes() throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setSubject("Quote ABC");
        message.setText("Please see attached");
        message.saveChanges();

        byte[] raw = ImapInboundMailClient.captureRawRfc822(message);

        String rfc822 = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(raw.length).isGreaterThan(0);
        assertThat(rfc822).contains("Quote ABC");
        assertThat(rfc822).contains("Please see attached");
    }

    @Test
    void captureRawRfc822_whenWriteToFails_returnsEmpty() throws Exception {
        Message message = mock(Message.class);
        doThrow(new MessagingException("imap writeTo failed")).when(message).writeTo(any());

        byte[] raw = ImapInboundMailClient.captureRawRfc822(message);

        assertThat(raw).isEmpty();
    }

    @Test
    void captureRawRfc822_thenExtractParts_stillReadsBodyAndAttachment() throws Exception {
        MimeMessage message = mixedPlainAndPdf();

        byte[] raw = ImapInboundMailClient.captureRawRfc822(message);
        String rfc822 = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(rfc822).contains("Please see attached");

        StringBuilder plain = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<com.workflow.email.extract.EmailAttachment> attachments = new ArrayList<>();
        new ImapInboundMailClient().extractParts(message, plain, html, attachments);

        assertThat(plain.toString()).contains("Please see attached");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).filename()).isEqualTo("quote.pdf");
        assertThat(attachments.get(0).content()).isEqualTo("pdf-bytes".getBytes());
    }

    private static MimeMessage mixedOuterTextAndRfc822Attachment() throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage inner = new MimeMessage(session);
        inner.setSubject("Other case");
        inner.setText("SECRET_ATTACHED_MSG_BODY");
        inner.saveChanges();

        MimeBodyPart rfc822 = new MimeBodyPart();
        rfc822.setContent(inner, "message/rfc822");
        rfc822.setDisposition(Part.ATTACHMENT);

        MimeBodyPart outerText = new MimeBodyPart();
        outerText.setText("Please process this request");

        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(outerText);
        mixed.addBodyPart(rfc822);

        MimeMessage message = new MimeMessage(session);
        message.setContent(mixed);
        message.saveChanges();
        return message;
    }

    private static MimeMessage mixedPlainAndPdf() throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Please see attached");
        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setFileName("quote.pdf");
        pdf.setDataHandler(new DataHandler(new ByteArrayDataSource("pdf-bytes".getBytes(), "application/pdf")));
        pdf.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(text);
        mixed.addBodyPart(pdf);
        MimeMessage message = new MimeMessage(session);
        message.setSubject("Quote ABC");
        message.setContent(mixed);
        message.saveChanges();
        return message;
    }
}
