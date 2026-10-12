package de.metis.mail.protocol;

/**
 * Connection configuration for IMAP/SMTP servers (M1-003: basic authentication).
 *
 * @param host     server hostname
 * @param port     server port
 * @param username login name
 * @param password login password
 * @param security transport security mode
 */
public record MailClientConfig(String host, int port, String username, String password, Security security) {

    public enum Security {
        /** Plaintext connection (local/test only). */
        NONE,
        /** Plaintext connection upgraded via STARTTLS. */
        STARTTLS,
        /** Implicit TLS from the first byte. */
        SSL
    }

    public MailClientConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
        if (security == null) {
            security = Security.STARTTLS;
        }
    }

    public static MailClientConfig imap(String host, int port, String user, String pass) {
        return new MailClientConfig(host, port, user, pass, Security.STARTTLS);
    }

    public static MailClientConfig smtp(String host, int port, String user, String pass) {
        return new MailClientConfig(host, port, user, pass, Security.STARTTLS);
    }
}
