package com.workflow.email.inbound;

import com.platform.common.mail.ImapTransportProperties;
import com.platform.common.mail.MailDiagnostics;
import com.workflow.email.extract.EmailAttachment;
import com.workflow.email.extract.EmailMessage;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * IMAP implementation of {@link InboundMailClient} using jakarta.mail.
 *
 * <p>Works with app-password mailboxes (QQ / 163 / Outlook / Gmail) — no OAuth required.
 * Incremental polling uses IMAP UIDs as the cursor (Power Automate-style "new email arrives"):
 * a blank cursor seeds the baseline (no history replay) and subsequent polls return only
 * messages with a higher UID.
 */
@Slf4j
@Component
public class ImapInboundMailClient implements InboundMailClient {

    @Override
    public FetchResult fetchNew(MailboxAccess access, String folder, String cursor, int max) {
        String protocol = access.ssl() ? "imaps" : "imap";
        return fetchNew(access, folder, cursor, max, buildProps(access, protocol));
    }

    FetchResult fetchNew(MailboxAccess access, String folder, String cursor, int max, Properties props) {
        String folderName = StringUtils.hasText(folder) ? folder : "INBOX";
        String protocol = access.ssl() ? "imaps" : "imap";
        Session session = Session.getInstance(props);

        log.info("[IMAP-FETCH] begin protocol={} host={} port={} ssl={} user={} folder={} cursor={} max={}",
                protocol, access.host(), access.port(), access.ssl(), mask(access.username()), folderName, cursor, max);

        Store store = null;
        Folder mailFolder = null;
        try {
            store = session.getStore(protocol);
            store.connect(access.host(), access.port(), access.username(), access.password());
            mailFolder = store.getFolder(folderName);
            mailFolder.open(Folder.READ_ONLY);

            UIDFolder uidFolder = (UIDFolder) mailFolder;
            long lastUid = parseCursor(cursor);

            if (lastUid < 0) {
                long baseline = Math.max(0, uidFolder.getUIDNext() - 1);
                log.info("[IMAP-FETCH] host={} folder={} first poll, baseline cursor={}",
                        access.host(), folderName, baseline);
                return new FetchResult(List.of(), String.valueOf(baseline));
            }
            FetchResult result = fetchSince(uidFolder, mailFolder, lastUid, max);
            log.info("[IMAP-FETCH] SUCCESS host={} folder={} fetched={} newCursor={}",
                    access.host(), folderName, result.messages().size(), result.nextCursor());
            return result;
        } catch (Exception e) {
            log.error("[IMAP-FETCH] FAILED host={} port={} ssl={} folder={} | causeChain={} | rootCause={}",
                    access.host(), access.port(), access.ssl(), folderName,
                    MailDiagnostics.causeChain(e), MailDiagnostics.rootCause(e), e);
            throw new IllegalStateException("IMAP fetch failed for " + access.host() + ": "
                    + MailDiagnostics.rootCause(e), e);
        } finally {
            closeQuietly(mailFolder, store);
        }
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<none>";
        }
        int at = value.indexOf('@');
        return at > 1 ? value.charAt(0) + "***" + value.substring(at) : value.charAt(0) + "***";
    }

    private Properties buildProps(MailboxAccess access, String protocol) {
        return ImapTransportProperties.apply(access.host(), access.port(), access.ssl(), protocol);
    }

    private FetchResult fetchSince(UIDFolder uidFolder, Folder folder, long lastUid, int max) throws Exception {
        Message[] candidates = uidFolder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
        List<Message> newer = new ArrayList<>();
        for (Message message : candidates) {
            if (uidFolder.getUID(message) > lastUid) {
                newer.add(message);
            }
        }
        newer.sort(Comparator.comparingLong(m -> safeUid(uidFolder, m)));

        List<EmailMessage> mapped = new ArrayList<>();
        long maxUid = lastUid;
        for (Message message : newer) {
            if (mapped.size() >= max) {
                break;
            }
            long uid = uidFolder.getUID(message);
            mapped.add(toEmailMessage(message, uid));
            maxUid = Math.max(maxUid, uid);
        }
        return new FetchResult(mapped, String.valueOf(maxUid));
    }

    private long safeUid(UIDFolder uidFolder, Message message) {
        try {
            return uidFolder.getUID(message);
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private EmailMessage toEmailMessage(Message message, long uid) throws Exception {
        String subject = message.getSubject();
        String from = (message.getFrom() != null && message.getFrom().length > 0)
                ? message.getFrom()[0].toString() : null;
        String messageId = resolveMessageId(message, uid);

        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<EmailAttachment> attachments = new ArrayList<>();
        extractParts(message, text, html, attachments);

        Map<String, String> headers = new HashMap<>();
        if (from != null) {
            headers.put("from", from);
        }
        putHeader(headers, "to", formatAddresses(message.getRecipients(Message.RecipientType.TO)));
        putHeader(headers, "cc", formatAddresses(message.getRecipients(Message.RecipientType.CC)));
        if (message instanceof MimeMessage mimeMessage) {
            putHeader(headers, "reply-to", formatAddresses(mimeMessage.getReplyTo()));
        }
        if (message.getSentDate() != null) {
            headers.put("date", message.getSentDate().toInstant().toString());
        }
        if (StringUtils.hasText(messageId)) {
            headers.put("message-id", messageId);
        }
        return new EmailMessage(messageId, subject, from,
                text.length() > 0 ? text.toString() : null,
                html.length() > 0 ? html.toString() : null,
                headers,
                attachments);
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        if (StringUtils.hasText(value)) {
            headers.put(name, value);
        }
    }

    static String formatAddresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < addresses.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(addresses[i].toString());
        }
        return builder.toString();
    }

    private String resolveMessageId(Message message, long uid) throws Exception {
        if (message instanceof MimeMessage mime) {
            String id = mime.getMessageID();
            if (StringUtils.hasText(id)) {
                return id;
            }
        }
        return "imap-uid:" + uid;
    }

    /** Recursively collects text/plain and text/html bodies from a (possibly multipart) part. */
    void extractParts(Part part, StringBuilder text, StringBuilder html) throws Exception {
        extractParts(part, text, html, new ArrayList<>());
    }

    void extractParts(
            Part part,
            StringBuilder text,
            StringBuilder html,
            List<EmailAttachment> attachments) throws Exception {
        if (isInlineCid(part)) {
            return;
        }
        if (isAttachmentPart(part)) {
            collectAttachment(part, attachments);
            return;
        }
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                extractParts(multipart.getBodyPart(i), text, html, attachments);
            }
            return;
        }
        if (content instanceof Message nested) {
            extractParts(nested, text, html, attachments);
            return;
        }
        if (content instanceof InputStream inputStream && part.isMimeType("message/rfc822")) {
            Session nestedSession = Session.getDefaultInstance(new Properties());
            Message nestedMessage = new MimeMessage(nestedSession, inputStream);
            extractParts(nestedMessage, text, html, attachments);
            return;
        }
        appendTextOrHtml(part, content, text, html);
    }

    private static void appendTextOrHtml(
            Part part, Object content, StringBuilder text, StringBuilder html) throws Exception {
        String body = contentAsString(content);
        if (body == null) {
            return;
        }
        if (part.isMimeType("text/html")) {
            html.append(body);
        } else if (part.isMimeType("text/plain")) {
            text.append(body);
        }
    }

    /** Inline CID images stay in HTML; they are not FILE attachments. */
    static boolean isInlineCid(Part part) throws Exception {
        String[] cids = part.getHeader("Content-ID");
        if (cids == null || cids.length == 0 || !StringUtils.hasText(cids[0])) {
            return false;
        }
        String disposition = part.getDisposition();
        return !Part.ATTACHMENT.equalsIgnoreCase(disposition);
    }

    static boolean isAttachmentPart(Part part) throws Exception {
        if (part.isMimeType("multipart/*") || part.isMimeType("message/rfc822")) {
            return false;
        }
        String disposition = part.getDisposition();
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
            return true;
        }
        String filename = decodeFilename(part.getFileName());
        if (!StringUtils.hasText(filename)) {
            return false;
        }
        return !part.isMimeType("text/plain") && !part.isMimeType("text/html");
    }

    private static void collectAttachment(Part part, List<EmailAttachment> attachments) throws Exception {
        String filename = decodeFilename(part.getFileName());
        if (!StringUtils.hasText(filename)) {
            filename = "attachment";
        }
        byte[] bytes = readPartBytes(part);
        if (bytes.length == 0) {
            return;
        }
        attachments.add(new EmailAttachment(filename, part.getContentType(), bytes));
    }

    private static byte[] readPartBytes(Part part) throws Exception {
        try (InputStream in = part.getInputStream()) {
            return in.readAllBytes();
        } catch (Exception streamFailed) {
            Object content = part.getContent();
            if (content instanceof byte[] raw) {
                return raw;
            }
            if (content instanceof InputStream in) {
                return in.readAllBytes();
            }
            throw streamFailed;
        }
    }

    static String decodeFilename(String raw) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        try {
            return MimeUtility.decodeText(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    private static String contentAsString(Object content) throws Exception {
        if (content instanceof String body) {
            return body;
        }
        if (content instanceof InputStream inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private long parseCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return -1L;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
        } catch (Exception e) {
            log.debug("IMAP folder close ignored: {}", e.getMessage());
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            log.debug("IMAP store close ignored: {}", e.getMessage());
        }
    }
}
