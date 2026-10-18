package de.metis.mail.protocol;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.DummySSLSocketFactory;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Date;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4-008: Flags (gesehen / geloescht) werden ueber die Message-ID auf den
 * IMAP-Server gespiegelt — echter Protokolltest gegen GreenMail.
 */
class ImapFlagSyncTest {

    private static final String USER = "alice@example.com";
    private static final String PASS = "secret123";
    private static final String MID = "flagtest-4711@example.com";

    static final ServerSetup IMAP = new ServerSetup(3243, "127.0.0.1", ServerSetup.PROTOCOL_IMAP);
    static final ServerSetup SMTP = new ServerSetup(3325, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(new ServerSetup[]{IMAP, SMTP});

    @BeforeEach
    void seed() throws Exception {
        greenMail.setUser(USER, USER, PASS);
        if (greenMail.getManagers().getImapHostManager()
                .getInbox(greenMail.getManagers().getUserManager().getUser(USER)).getMessageCount() > 0) {
            return;
        }
        MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipients(Message.RecipientType.TO, USER);
        msg.setSubject("Flag test");
        msg.setSentDate(new Date());
        msg.setHeader("Message-ID", "<" + MID + ">");
        msg.setText("body");
        try (Transport transport = Session.getInstance(new Properties()).getTransport("smtp")) {
            transport.connect("127.0.0.1", SMTP.getPort(), USER, PASS);
            transport.sendMessage(msg, msg.getAllRecipients());
        }
        greenMail.waitForIncomingEmail(5000, 1);
    }

    private MailClientConfig config() {
        return new MailClientConfig("127.0.0.1", IMAP.getPort(), USER, PASS, MailClientConfig.Security.NONE);
    }

    private int messageCount() throws Exception {
        Properties props = new Properties();
        props.setProperty("mail.store.protocol", "imap");
        Store store = Session.getInstance(props).getStore("imap");
        store.connect("127.0.0.1", IMAP.getPort(), USER, PASS);
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        try {
            return inbox.getMessageCount();
        } finally {
            inbox.close(false);
        }
    }

    private Message serverMessage() throws Exception {
        Properties props = new Properties();
        props.setProperty("mail.store.protocol", "imap");
        props.setProperty("mail.imap.host", "127.0.0.1");
        props.setProperty("mail.imap.port", String.valueOf(IMAP.getPort()));
        Store store = Session.getInstance(props).getStore("imap");
        store.connect("127.0.0.1", IMAP.getPort(), USER, PASS);
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
        return inbox.getMessage(1);
    }

    @Test
    void marksSeenAndDeletedByMessageId() throws Exception {
        // GreenMail-SMTP setzt eine eigene Message-ID -> die echte aus dem Ordner nehmen.
        // Vor dem Setzen von DELETED lesen: danach liefert der Ordner die Mail nicht mehr.
        String storedId = serverMessage().getHeader("Message-ID")[0].trim()
                .replaceAll("^<|>$", "");
        boolean plainHit;
        boolean strippedHit;
        try (ImapMailClient client = new ImapMailClient(config())) {
            client.connect();
            plainHit = client.applyFlags("INBOX", "<" + storedId + ">", false, null, false);
            strippedHit = client.applyFlags("INBOX", storedId, false, Boolean.TRUE, true);
        }
        assertTrue(plainHit, "Mail muss ueber die Message-ID gefunden werden");
        assertTrue(strippedHit, "Message-ID ohne spitze Klammern muss ebenfalls greifen");

        Message m = serverMessage();
        assertTrue(m.isSet(Flags.Flag.SEEN), "SEEN muss auf dem Server gesetzt sein");
        assertTrue(m.isSet(Flags.Flag.FLAGGED), "FLAGGED muss gesetzt sein");

        // DELETED bleibt als Flag gesetzt (kein expunge), Restore ist damit moeglich.
        assertTrue(m.isSet(Flags.Flag.DELETED), "DELETED muss auf dem Server gesetzt sein");
    }

    @Test
    void unknownMessageIdReturnsFalse() throws Exception {
        try (ImapMailClient client = new ImapMailClient(config())) {
            client.connect();
            assertFalse(client.applyFlags("INBOX", "<does-not-exist@example.com>", false, null, false));
        }
    }
}
