package de.metis.mail.protocol;

import de.metis.mail.model.MailMessage;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.logging.Level;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Converts Jakarta Mail {@link Message} objects into the immutable core
 * {@link MailMessage} model (and back to {@link MimeMessage} for sending).
 */
public final class MessageConverter {

    private static final Logger LOG = Logger.getLogger(MessageConverter.class.getName());
    private static final int MAX_ATTACHMENT_SIZE = 25 * 1024 * 1024; // 25 MB guard

    private MessageConverter() {
    }

    /**
     * Convert a Jakarta Mail message into the core model.
     */
    public static MailMessage toMailMessage(Message message) throws MessagingException, IOException {
        MailMessage.Builder builder = MailMessage.builder();

        // From
        Address[] from = message.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            builder.from(ia.getPersonal(), ia.getAddress());
        } else {
            builder.from("", "unknown@localhost");
        }

        // To / Cc
        addRecipients(builder, message, Message.RecipientType.TO, true);
        addRecipients(builder, message, Message.RecipientType.CC, false);

        // Subject (required by model)
        String subject = message.getSubject();
        builder.subject(subject != null ? subject : "(no subject)");

        // Date
        Date sent = message.getSentDate();
        if (sent != null) {
            builder.sentDate(sent.toInstant());
        }

        // Selected headers
        putHeader(builder, message, "Message-ID");
        putHeader(builder, message, "In-Reply-To");
        putHeader(builder, message, "References");
        putHeader(builder, message, "Authentication-Results");
        putHeader(builder, message, "Return-Path");
        putHeader(builder, message, "Content-Type");

        // Body + attachments
        extractContent(message, builder);

        // Flags
        builder.setUnseen(!message.isSet(Flags.Flag.SEEN));
        builder.setFlagged(message.isSet(Flags.Flag.FLAGGED));

        return builder.build();
    }

    private static void addRecipients(MailMessage.Builder builder, Message message,
                                      Message.RecipientType type, boolean isTo) throws MessagingException {
        Address[] addrs = message.getRecipients(type);
        if (addrs == null) return;
        for (Address a : addrs) {
            if (a instanceof InternetAddress ia) {
                if (isTo) {
                    builder.to(ia.getPersonal(), ia.getAddress());
                } else {
                    builder.cc(ia.getPersonal(), ia.getAddress());
                }
            }
        }
    }

    private static void putHeader(MailMessage.Builder builder, Message message, String name)
            throws MessagingException {
        String[] values = message.getHeader(name);
        if (values != null) {
            for (String v : values) {
                builder.addHeader(name, v);
            }
        }
    }

    private static void extractContent(Part part, MailMessage.Builder builder)
            throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            if (content != null) {
                builder.plainTextBody(content.toString());
            }
        } else if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            if (content != null) {
                builder.htmlBody(content.toString());
            }
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                extractContent(mp.getBodyPart(i), builder);
            }
        } else {
            // Anhang oder Inline-Bild (Content-ID)
            String fileName = part.getFileName();
            String cid = normalizeContentId(part.getHeader("Content-ID"));
            if (fileName == null && cid == null) {
                return; // wie bisher: ohne Namen und ohne CID ignorieren
            }
            String mime = part.getContentType();
            if (fileName == null) {
                fileName = fallbackName(cid, mime);
            }
            byte[] data = readBytes(part);
            long size = part.getSize() > 0 ? part.getSize() : data.length;
            if (data.length <= MAX_ATTACHMENT_SIZE) {
                builder.addAttachment(fileName, mime, size, data, cid);
            } else {
                LOG.warning("Attachment too large, skipped: " + fileName + " (" + size + " bytes)");
            }
        }
    }

    private static byte[] readBytes(Part part) throws IOException, MessagingException {
        try (InputStream in = part.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    /** "<logo123>" -> "logo123"; null/leer -> null. */
    static String normalizeContentId(String[] header) {
        if (header == null || header.length == 0 || header[0] == null) return null;
        String v = header[0].strip();
        if (v.startsWith("<") && v.endsWith(">") && v.length() > 2) {
            v = v.substring(1, v.length() - 1).strip();
        }
        return v.isEmpty() ? null : v;
    }

    /** Dateiname fuer Inline-Teile ohne Namen, z. B. "inline-logo123.png". */
    static String fallbackName(String cid, String contentType) {
        String base = contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        String ext = switch (base) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "bin";
        };
        String safe = cid.replaceAll("[^A-Za-z0-9._-]", "_");
        return "inline-" + safe + "." + ext;
    }

    /**
     * Convert the core model into a sendable MimeMessage.
     */
    public static MimeMessage toMimeMessage(jakarta.mail.Session session, MailMessage msg)
            throws MessagingException, java.io.UnsupportedEncodingException {
        MimeMessage mime = new MimeMessage(session);

        for (var addr : msg.getFrom()) {
            mime.addFrom(new Address[]{new InternetAddress(addr.getEmail(), addr.getName())});
        }
        for (var addr : msg.getTo()) {
            mime.addRecipient(Message.RecipientType.TO, new InternetAddress(addr.getEmail(), addr.getName()));
        }
        for (var addr : msg.getCc()) {
            mime.addRecipient(Message.RecipientType.CC, new InternetAddress(addr.getEmail(), addr.getName()));
        }
        mime.setSubject(msg.getSubject(), "UTF-8");
        Instant sent = msg.getSentDate();
        if (sent != null) {
            mime.setSentDate(Date.from(sent));
        }

        if (msg.getAttachments().isEmpty()) {
            if (msg.getHtmlBody() != null && msg.getPlainTextBody() != null) {
                jakarta.mail.internet.MimeMultipart alt = new jakarta.mail.internet.MimeMultipart("alternative");
                var textPart = new jakarta.mail.internet.MimeBodyPart();
                textPart.setText(msg.getPlainTextBody(), "UTF-8");
                var htmlPart = new jakarta.mail.internet.MimeBodyPart();
                htmlPart.setContent(msg.getHtmlBody(), "text/html; charset=UTF-8");
                alt.addBodyPart(textPart);
                alt.addBodyPart(htmlPart);
                mime.setContent(alt);
            } else if (msg.getHtmlBody() != null) {
                mime.setContent(msg.getHtmlBody(), "text/html; charset=UTF-8");
            } else {
                mime.setText(msg.getPlainTextBody() != null ? msg.getPlainTextBody() : "", "UTF-8");
            }
        } else {
            jakarta.mail.internet.MimeMultipart mixed = new jakarta.mail.internet.MimeMultipart("mixed");
            var textPart = new jakarta.mail.internet.MimeBodyPart();
            textPart.setText(msg.getPlainTextBody() != null ? msg.getPlainTextBody() : "", "UTF-8");
            mixed.addBodyPart(textPart);
            for (var att : msg.getAttachments()) {
                var attPart = new jakarta.mail.internet.MimeBodyPart();
                attPart.setFileName(att.getFileName());
                attPart.setContent(att.getData(), att.getMimeType());
                attPart.setDisposition(Part.ATTACHMENT);
                mixed.addBodyPart(attPart);
            }
            mime.setContent(mixed);
        }

        mime.saveChanges();
        return mime;
    }
}
