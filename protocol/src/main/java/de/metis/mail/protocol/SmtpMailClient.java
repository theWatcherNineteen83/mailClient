package de.metis.mail.protocol;

import de.metis.mail.model.MailMessage;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;
import java.util.logging.Logger;

/**
 * SMTP client (M1-002). Sends core {@link MailMessage} objects via
 * Jakarta Mail with basic username/password authentication (M1-003).
 *
 * <p>Usage:
 * <pre>{@code
 * try (SmtpMailClient client = new SmtpMailClient(config)) {
 *     client.send(message);
 * }
 * }</pre>
 */
public class SmtpMailClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SmtpMailClient.class.getName());

    private final MailClientConfig config;
    private final Session session;
    private Transport transport;

    public SmtpMailClient(MailClientConfig config) {
        this.config = config;
        this.session = Session.getInstance(buildProperties(config));
    }

    private static Properties buildProperties(MailClientConfig config) {
        Properties props = new Properties();
        String prefix = "mail.smtp";
        switch (config.security()) {
            case SSL -> {
                prefix = "mail.smtps";
                props.setProperty("mail.smtps.ssl.enable", "true");
                props.setProperty("mail.transport.protocol", "smtps");
            }
            case STARTTLS -> {
                props.setProperty("mail.smtp.starttls.enable", "true");
                props.setProperty("mail.transport.protocol", "smtp");
            }
            case NONE -> props.setProperty("mail.transport.protocol", "smtp");
        }
        props.setProperty(prefix + ".host", config.host());
        props.setProperty(prefix + ".port", String.valueOf(config.port()));
        props.setProperty(prefix + ".auth", "true");
        return props;
    }

    /**
     * Connect and authenticate.
     */
    public void connect() throws MessagingException {
        String protocol = config.security() == MailClientConfig.Security.SSL ? "smtps" : "smtp";
        transport = session.getTransport(protocol);
        transport.connect(config.host(), config.port(), config.username(), config.password());
        LOG.info("SMTP connected to " + config.host() + ":" + config.port() + " as " + config.username());
    }

    /**
     * Send a message. Connects lazily if not already connected.
     */
    public void send(MailMessage message) throws MessagingException, java.io.UnsupportedEncodingException {
        if (transport == null || !transport.isConnected()) {
            connect();
        }
        MimeMessage mime = MessageConverter.toMimeMessage(session, message);
        transport.sendMessage(mime, mime.getAllRecipients());
        LOG.info("Message sent: " + message.getSubject());
    }

    public Session getSession() {
        return session;
    }

    @Override
    public void close() {
        if (transport != null) {
            try {
                transport.close();
                LOG.info("SMTP connection closed");
            } catch (MessagingException e) {
                LOG.warning("Error closing SMTP transport: " + e.getMessage());
            }
        }
    }
}
