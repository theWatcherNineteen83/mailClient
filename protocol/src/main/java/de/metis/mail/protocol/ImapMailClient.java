package de.metis.mail.protocol;

import de.metis.mail.model.MailMessage;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * IMAP client (M1-001). Connects to an IMAP server via Jakarta Mail,
 * lists folders, fetches messages and converts them to the core
 * {@link MailMessage} model.
 *
 * <p>Usage:
 * <pre>{@code
 * try (ImapMailClient client = new ImapMailClient(config)) {
 *     client.connect();
 *     List<MailMessage> mails = client.fetchInbox(50);
 * }
 * }</pre>
 */
public class ImapMailClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ImapMailClient.class.getName());

    private final MailClientConfig config;
    private final Session session;
    private Store store;

    public ImapMailClient(MailClientConfig config) {
        this.config = config;
        this.session = Session.getInstance(buildProperties(config));
    }

    private static Properties buildProperties(MailClientConfig config) {
        Properties props = new Properties();
        String prefix = "mail.imap";
        switch (config.security()) {
            case SSL -> {
                prefix = "mail.imaps";
                props.setProperty("mail.imaps.ssl.enable", "true");
                props.setProperty("mail.store.protocol", "imaps");
            }
            case STARTTLS -> {
                props.setProperty("mail.imap.starttls.enable", "true");
                props.setProperty("mail.store.protocol", "imap");
            }
            case NONE -> props.setProperty("mail.store.protocol", "imap");
        }
        props.setProperty(prefix + ".host", config.host());
        props.setProperty(prefix + ".port", String.valueOf(config.port()));
        props.setProperty(prefix + ".partialfetch", "false");
        return props;
    }

    /**
     * Authenticate against the server (M1-003: basic username/password auth).
     */
    public void connect() throws MessagingException {
        String protocol = config.security() == MailClientConfig.Security.SSL ? "imaps" : "imap";
        store = session.getStore(protocol);
        store.connect(config.host(), config.port(), config.username(), config.password());
        LOG.info("IMAP connected to " + config.host() + ":" + config.port() + " as " + config.username());
    }

    public boolean isConnected() {
        return store != null && store.isConnected();
    }

    /**
     * List all folder names visible to the user.
     */
    public List<String> listFolders() throws MessagingException {
        requireConnection();
        List<String> names = new ArrayList<>();
        for (Folder f : store.getDefaultFolder().list("*")) {
            names.add(f.getFullName());
        }
        return names;
    }

    /**
     * Fetch up to {@code maxMessages} most recent messages from the given folder,
     * converted to the core model.
     */
    public List<MailMessage> fetchMessages(String folderName, int maxMessages)
            throws MessagingException, IOException {
        requireConnection();
        Folder folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            int total = folder.getMessageCount();
            int start = Math.max(1, total - maxMessages + 1);
            List<MailMessage> result = new ArrayList<>();
            if (total > 0) {
                for (Message m : folder.getMessages(start, total)) {
                    try {
                        result.add(MessageConverter.toMailMessage(m));
                    } catch (MessagingException | IOException e) {
                        LOG.log(Level.WARNING, "Skipping unparsable message #" + m.getMessageNumber(), e);
                    }
                }
            }
            return result;
        } finally {
            folder.close(false);
        }
    }

    /**
     * Convenience: fetch from INBOX.
     */
    public List<MailMessage> fetchInbox(int maxMessages) throws MessagingException, IOException {
        return fetchMessages("INBOX", maxMessages);
    }

    /**
     * Mark a message as seen/unseen or flagged by index (1-based within folder).
     */
    public void setFlag(String folderName, int messageNumber, Flags.Flag flag, boolean value)
            throws MessagingException {
        requireConnection();
        Folder folder = store.getFolder(folderName);
        folder.open(Folder.READ_WRITE);
        try {
            Message m = folder.getMessage(messageNumber);
            m.setFlag(flag, value);
        } finally {
            folder.close(true);
        }
    }

    /**
     * M4-008: Setzt Flags einer Mail auf dem Server, adressiert ueber die Message-ID
     * (spitze Klammern optional). UTF-7-kodierte Ordner
     * funktionieren nicht ueber diesen Weg und liefern {@code false}.
     */
    public boolean applyFlags(String folderName, String messageId, boolean unseen,
                              Boolean flagged, boolean deleted) throws MessagingException {
        requireConnection();
        Folder folder = store.getFolder(folderName);
        folder.open(Folder.READ_WRITE);
        try {
            String needle = messageId.trim();
            if (needle.startsWith("<") && needle.endsWith(">")) {
                needle = needle.substring(1, needle.length() - 1);
            }
            for (Message m : searchByMessageId(folder, needle)) {
                m.setFlag(Flags.Flag.SEEN, !unseen);
                if (flagged != null) m.setFlag(Flags.Flag.FLAGGED, flagged);
                m.setFlag(Flags.Flag.DELETED, deleted);
                return true;
            }
            return false;
        } finally {
            // Kein expunge: DELETED bleibt als Flag bestehen, damit sich Aenderungen
            // rueckgaengig machen lassen (restore = setFlag DELETED false).
            folder.close(false);
        }
    }

    /**
     * Liefert die Mails, deren Message-ID-Header passt. GreenMail und einige
     * IMAP-Server ignorieren HEADER-SEARCH auf Message-ID, daher wird die
     * Kopfzeile lokal geprueft (Ordner ist beim Aufruf bereits geoeffnet).
     */
    private static java.util.List<Message> searchByMessageId(Folder folder, String messageId)
            throws MessagingException {
        java.util.List<Message> hits = new java.util.ArrayList<>();
        for (Message m : folder.getMessages()) {
            String[] values = m.getHeader("Message-ID");
            if (values == null) continue;
            for (String v : values) {
                if (v == null) continue;
                String norm = v.trim();
                if (norm.startsWith("<") && norm.endsWith(">")) {
                    norm = norm.substring(1, norm.length() - 1);
                }
                if (norm.equals(messageId)) {
                    hits.add(m);
                    break;
                }
            }
        }
        return hits;
    }

    private void requireConnection() throws MessagingException {
        if (!isConnected()) {
            throw new IllegalStateException("IMAP client is not connected; call connect() first");
        }
    }

    @Override
    public void close() {
        if (store != null) {
            try {
                store.close();
                LOG.info("IMAP connection closed");
            } catch (MessagingException e) {
                LOG.log(Level.WARNING, "Error closing IMAP store", e);
            }
        }
    }
}
