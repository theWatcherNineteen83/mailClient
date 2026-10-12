package de.metis.mail.protocol;

import de.metis.mail.model.MailMessage;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-004: Integration tests against a real (embedded) IMAP/SMTP server.
 * GreenMail provides actual protocol implementations in-JVM.
 */
class MailProtocolIntegrationTest {

    private static final String USER = "alice@example.com";
    private static final String PASS = "secret123";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup[]{ServerSetup.IMAP, ServerSetup.SMTP});

    @BeforeEach
    void setupUser() {
        greenMail.setUser(USER, USER, PASS);
    }

    private MailClientConfig imapConfig() {
        return new MailClientConfig("127.0.0.1", ServerSetup.IMAP.getPort(),
                USER, PASS, MailClientConfig.Security.NONE);
    }

    private MailClientConfig smtpConfig() {
        return new MailClientConfig("127.0.0.1", ServerSetup.SMTP.getPort(),
                USER, PASS, MailClientConfig.Security.NONE);
    }

    @Test
    void smtpSendDeliversMessage() throws Exception {
        MailMessage msg = MailMessage.builder()
                .from("Alice", USER)
                .to("Bob", "bob@example.com")
                .subject("SMTP integration test")
                .plainTextBody("Hello via SMTP")
                .build();

        try (SmtpMailClient client = new SmtpMailClient(smtpConfig())) {
            client.send(msg);
        }

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertTrue(received.length >= 1);
        MimeMessage last = received[received.length - 1];
        assertEquals("SMTP integration test", last.getSubject());
        assertEquals("Hello via SMTP", GreenMailUtil.getBody(last).trim());
    }

    @Test
    void imapFetchConvertsToCoreModel() throws MessagingException, IOException {
        // Seed a message into the inbox via GreenMail utility
        GreenMailUtil.sendTextEmail(USER, "sender@example.com", "IMAP test subject",
                "IMAP test body", ServerSetup.IMAP);

        try (ImapMailClient client = new ImapMailClient(imapConfig())) {
            client.connect();
            assertTrue(client.isConnected());

            List<String> folders = client.listFolders();
            assertTrue(folders.stream().anyMatch(f -> f.equalsIgnoreCase("INBOX")),
                    "INBOX must be listed, got: " + folders);

            List<MailMessage> messages = client.fetchInbox(10);
            assertFalse(messages.isEmpty());

            MailMessage fetched = messages.get(messages.size() - 1);
            assertEquals("IMAP test subject", fetched.getSubject());
            assertNotNull(fetched.getPlainTextBody());
            assertTrue(fetched.getPlainTextBody().contains("IMAP test body"));
            assertEquals("sender@example.com", fetched.getFrom().get(0).getEmail());
        }
    }

    @Test
    void imapWrongPasswordFails() {
        MailClientConfig bad = new MailClientConfig("127.0.0.1", ServerSetup.IMAP.getPort(),
                USER, "wrong-password", MailClientConfig.Security.NONE);
        try (ImapMailClient client = new ImapMailClient(bad)) {
            assertThrows(MessagingException.class, client::connect);
        }
    }

    @Test
    void roundTripSendThenFetch() throws MessagingException, IOException {
        MailMessage msg = MailMessage.builder()
                .from("Alice", USER)
                .to("Alice", USER) // send to self so it lands in our mailbox
                .subject("Round trip")
                .plainTextBody("Body round trip")
                .build();

        try (SmtpMailClient smtp = new SmtpMailClient(smtpConfig())) {
            smtp.send(msg);
        }

        try (ImapMailClient imap = new ImapMailClient(imapConfig())) {
            imap.connect();
            List<MailMessage> messages = imap.fetchInbox(50);
            assertTrue(messages.stream().anyMatch(m -> "Round trip".equals(m.getSubject())),
                    "sent message must appear in INBOX");
        }
    }
}
