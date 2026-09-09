package com.workflow.email.extract;

/**
 * One inbound email attachment kept after IMAP parse (filename + bytes).
 *
 * <p>Inline CID images are not represented here — they stay in the HTML body.
 */
public record EmailAttachment(String filename, String contentType, byte[] content) {

    public EmailAttachment {
        filename = filename == null ? "" : filename;
        contentType = contentType == null ? "application/octet-stream" : contentType;
        content = content == null ? new byte[0] : content;
    }

    public int size() {
        return content.length;
    }
}
