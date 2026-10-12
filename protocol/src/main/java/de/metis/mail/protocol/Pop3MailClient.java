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
 * POP3 client. Retrieves messages from the server's single mailbox (INBOX).
 *
 * <p>POP3 limitations compared to IMAP (by protocol design):
 * <ul>
 *   <li>No server-side folders — only INBOX.</li>
 *   <li>No server-side flags — SEEN/FLAGGED state is client-local.</li>
 *   <li>DELETE marks messages for removal on server at close (expunge).</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * try (Pop3MailClient client = new Pop3MailClient(config)) {
 *     client.connect();
 *     List<MailMessage> mails = client.fetchMessages(50);
 * }
 * }</pre>
 */
public class Pop3MailClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Pop3MailClient.class.getName());

    private final MailClientConfig config;
    private final Session session;
    private Store store;

    public Pop3MailClient(MailClientConfig config) {
        this.config = config;
        this.session = Session.getInstance(buildProperties(config));
    }

    private static Properties buildProperties(MailClientConfig config) {
        Properties props = new Properties();
        String prefix = "mail.pop3";
        switch (config.security()) {
            case SSL -> {
                prefix = "mail.pop3s";
                props.setProperty("mail.pop3s.ssl.enable", "true");
                props.setProperty("mail.store.protocol", "pop3s");
            }
            case STARTTLS -> {
                props.setProperty("mail.pop3.starttls.enable", "true");
                props.setProperty("mail.store.protocol", "pop3");
            }
            case NONE -> props.setProperty("mail.store.protocol", "pop3");
        }
        props.setProperty(prefix + ".host", config.host());
        props.setProperty(prefix + ".port", String.valueOf(config.port()));
        return props;
    }

    /**
     * Authenticate against the server (basic username/password auth).
     */
    public void connect() throws MessagingException {
        String protocol = config.security() == MailClientConfig.Security.SSL ? "pop3s" : "pop3";
        store = session.getStore(protocol);
        store.connect(config.host(), config.port(), config.username(), config.password());
        LOG.info("POP3 connected to " + config.host() + ":" + config.port() + " as " + config.username());
    }

    public boolean isConnected() {
        return store != null && store.isConnected();
    }

    /**
     * Number of messages currently on the server.
     */
    public int getMessageCount() throws MessagingException {
        requireConnection();
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        try {
            return inbox.getMessageCount();
        } finally {
            inbox.close(false);
        }
    }

    /**
     * Fetch up to {@code maxMessages} most recent messages, converted to the core model.
     */
    public List<MailMessage> fetchMessages(int maxMessages) throws MessagingException, IOException {
        requireConnection();
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        try {
            int total = inbox.getMessageCount();
            int start = Math.max(1, total - maxMessages + 1);
            List<MailMessage> result = new ArrayList<>();
            if (total > 0) {
                for (Message m : inbox.getMessages(start, total)) {
                    try {
                        result.add(MessageConverter.toMailMessage(m));
                    } catch (MessagingException | IOException e) {
                        LOG.log(Level.WARNING, "Skipping unparsable message #" + m.getMessageNumber(), e);
                    }
                }
            }
            return result;
        } finally {
            inbox.close(false);
        }
    }

    /**
     * Mark a message for deletion on the server (1-based index).
     * The deletion takes effect when the mailbox is closed with expunge,
     * i.e. on {@link #close()} after a delete operation or via {@link #expungeAndClose()}.
     */
    public void deleteMessage(int messageNumber) throws MessagingException {
        requireConnection();
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
        try {
            Message m = inbox.getMessage(messageNumber);
            m.setFlag(Flags.Flag.DELETED, true);
        } finally {
            inbox.close(true); // expunge deleted messages
        }
        LOG.info("POP3 message #" + messageNumber + " marked deleted and expunged");
    }

    private void requireConnection() {
        if (!isConnected()) {
            throw new IllegalStateException("POP3 client is not connected; call connect() first");
        }
    }

    @Override
    public void close() {
        if (store != null) {
            try {
                store.close();
                LOG.info("POP3 connection closed");
            } catch (MessagingException e) {
                LOG.log(Level.WARNING, "Error closing POP3 store", e);
            }
        }
    }
}
