package de.metis.mail.protocol;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import de.metis.mail.model.MailMessage;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * POP3 integration tests against the embedded GreenMail POP3 server.
 */
class Pop3IntegrationTest {

    private static final String USER = "popuser@example.com";
    private static final String PASS = "popsecret";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup[]{ServerSetup.POP3, ServerSetup.SMTP});

    @BeforeEach
    void setupUser() {
        greenMail.setUser(USER, USER, PASS);
    }

    private MailClientConfig pop3Config() {
        return new MailClientConfig("127.0.0.1", ServerSetup.POP3.getPort(),
                USER, PASS, MailClientConfig.Security.NONE);
    }

    private MailClientConfig smtpConfig() {
        return new MailClientConfig("127.0.0.1", ServerSetup.SMTP.getPort(),
                USER, PASS, MailClientConfig.Security.NONE);
    }

    @Test
    void sendViaSmtpThenFetchViaPop3() throws Exception {
        MailMessage msg = MailMessage.builder()
                .from("Alice", USER)
                .to("Alice", USER)
                .subject("POP3 fetch test")
                .plainTextBody("Hello via POP3")
                .build();
        try (SmtpMailClient smtp = new SmtpMailClient(smtpConfig())) {
            smtp.send(msg);
        }

        try (Pop3MailClient pop3 = new Pop3MailClient(pop3Config())) {
            pop3.connect();
            assertTrue(pop3.isConnected());
            assertEquals(1, pop3.getMessageCount());

            List<MailMessage> fetched = pop3.fetchMessages(10);
            assertEquals(1, fetched.size());
            assertEquals("POP3 fetch test", fetched.get(0).getSubject());
            assertTrue(fetched.get(0).getPlainTextBody().contains("Hello via POP3"));
        }
    }

    @Test
    void pop3WrongPasswordFails() {
        MailClientConfig bad = new MailClientConfig("127.0.0.1", ServerSetup.POP3.getPort(),
                USER, "wrong-password", MailClientConfig.Security.NONE);
        try (Pop3MailClient client = new Pop3MailClient(bad)) {
            assertThrows(MessagingException.class, client::connect);
        }
    }

    @Test
    void pop3DeleteRemovesMessageFromServer() throws Exception {
        GreenMailUtil.sendTextEmail(USER, "sender@example.com", "To be deleted",
                "bye", ServerSetup.SMTP);

        try (Pop3MailClient pop3 = new Pop3MailClient(pop3Config())) {
            pop3.connect();
            assertEquals(1, pop3.getMessageCount());
            pop3.deleteMessage(1);
        }

        // Reconnect: message must be gone from the server
        try (Pop3MailClient pop3 = new Pop3MailClient(pop3Config())) {
            pop3.connect();
            assertEquals(0, pop3.getMessageCount());
        }
    }

    @Test
    void fetchWithoutConnectThrows() {
        try (Pop3MailClient pop3 = new Pop3MailClient(pop3Config())) {
            assertThrows(IllegalStateException.class, () -> pop3.fetchMessages(10));
        }
    }
}
