package de.metis.mail.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Represents an email message containing sender, recipients, subject,
 * date, headers, body content (plain text and HTML), attachments, and status flags.
 */
public final class MailMessage {

    private static final Logger LOG = Logger.getLogger(MailMessage.class.getName());

    // Flags
    public static final String FLAG_UNSEEN = "UNSEEN";
    public static final String FLAG_FLAGGED = "FLAGGED";

    // Immutable fields
    private final List<MailAddress> from;
    private final List<MailAddress> to;
    private final List<MailAddress> cc;
    private final String subject;
    private final Instant sentDate;
    private final Map<String, List<String>> headers;
    private final String plainTextBody;
    private final String htmlBody;
    private final List<Attachment> attachments;
    private final boolean unseen;
    private final boolean flagged;

    private MailMessage(Builder builder) {
        this.from = List.copyOf(builder.from);
        this.to = List.copyOf(builder.to);
        this.cc = List.copyOf(builder.cc);
        this.subject = Objects.requireNonNull(builder.subject, "Subject cannot be null");
        this.sentDate = builder.sentDate != null ? builder.sentDate : Instant.now();
        this.headers = builder.headers != null ? new LinkedHashMap<>(builder.headers) : new LinkedHashMap<>();
        this.plainTextBody = builder.plainTextBody;
        this.htmlBody = builder.htmlBody;
        this.attachments = List.copyOf(builder.attachments);
        this.unseen = builder.unseen;
        this.flagged = builder.flagged;
        
        LOG.fine("Initialized MailMessage with subject: " + subject);
    }

    public List<MailAddress> getFrom() {
        return from;
    }

    public List<MailAddress> getTo() {
        return to;
    }

    public List<MailAddress> getCc() {
        return cc;
    }

    public String getSubject() {
        return subject;
    }

    public Instant getSentDate() {
        return sentDate;
    }

    public Map<String, List<String>> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    public String getPlainTextBody() {
        return plainTextBody;
    }

    public String getHtmlBody() {
        return htmlBody;
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public boolean isUnseen() {
        return unseen;
    }

    public boolean isFlagged() {
        return flagged;
    }

    @Override
    public String toString() {
        return "MailMessage{" +
                "from=" + from +
                ", to=" + to +
                ", cc=" + cc +
                ", subject='" + subject + '\'' +
                ", sentDate=" + sentDate +
                ", unseen=" + unseen +
                ", flagged=" + flagged +
                '}';
    }

    /**
     * Represents an attachment to the email message.
     */
    public static final class Attachment {
        private final String fileName;
        private final String mimeType;
        private final long size;
        private final byte[] data;

        private Attachment(String fileName, String mimeType, long size, byte[] data) {
            this.fileName = Objects.requireNonNull(fileName, "FileName cannot be null");
            this.mimeType = mimeType != null ? mimeType : "application/octet-stream";
            this.size = size;
            this.data = data != null ? data.clone() : new byte[0];
        }

        public String getFileName() {
            return fileName;
        }

        public String getMimeType() {
            return mimeType;
        }

        public long getSize() {
            return size;
        }

        public byte[] getData() {
            return data.clone();
        }

        @Override
        public String toString() {
            return "Attachment{" +
                    "fileName='" + fileName + '\'' +
                    ", mimeType='" + mimeType + '\'' +
                    ", size=" + size +
                    '}';
        }
    }

    /**
     * Represents an email address with name and email address.
     */
    public static final class MailAddress {
        private final String name;
        private final String email;

        private MailAddress(String name, String email) {
            this.name = name != null ? name : "";
            this.email = Objects.requireNonNull(email, "Email cannot be null");
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }

        @Override
        public String toString() {
            if (name == null || name.isEmpty()) {
                return email;
            }
            return name + " <" + email + ">";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MailAddress that = (MailAddress) o;
            return Objects.equals(email, that.email);
        }

        @Override
        public int hashCode() {
            return Objects.hash(email);
        }
    }

    /**
     * Builder for MailMessage.
     */
    public static class Builder {
        private final List<MailAddress> from = new ArrayList<>();
        private final List<MailAddress> to = new ArrayList<>();
        private final List<MailAddress> cc = new ArrayList<>();
        private String subject;
        private Instant sentDate;
        private Map<String, List<String>> headers = new LinkedHashMap<>();
        private String plainTextBody;
        private String htmlBody;
        private final List<Attachment> attachments = new ArrayList<>();
        private boolean unseen = true;
        private boolean flagged = false;

        public Builder from(String name, String email) {
            this.from.add(new MailAddress(name, email));
            return this;
        }

        public Builder to(String name, String email) {
            this.to.add(new MailAddress(name, email));
            return this;
        }

        public Builder cc(String name, String email) {
            this.cc.add(new MailAddress(name, email));
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder sentDate(Instant sentDate) {
            this.sentDate = sentDate;
            return this;
        }

        public Builder addHeader(String key, String value) {
            this.headers.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            return this;
        }

        public Builder plainTextBody(String body) {
            this.plainTextBody = body;
            return this;
        }

        public Builder htmlBody(String body) {
            this.htmlBody = body;
            return this;
        }

        public Builder addAttachment(String fileName, String mimeType, long size, byte[] data) {
            this.attachments.add(new Attachment(fileName, mimeType, size, data));
            return this;
        }

        public Builder setUnseen(boolean unseen) {
            this.unseen = unseen;
            return this;
        }

        public Builder setFlagged(boolean flagged) {
            this.flagged = flagged;
            return this;
        }

        public MailMessage build() {
            return new MailMessage(this);
        }
    }

    /**
     * Creates a new builder for MailMessage.
     */
    public static Builder builder() {
        return new Builder();
    }
}