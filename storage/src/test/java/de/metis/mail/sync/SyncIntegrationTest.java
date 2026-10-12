package de.metis.mail.sync;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import de.metis.mail.db.MailStore;
import de.metis.mail.db.StoredMessage;
import de.metis.mail.model.MailMessage;
import de.metis.mail.protocol.ImapMailClient;
import de.metis.mail.protocol.MailClientConfig;
import de.metis.mail.protocol.SmtpMailClient;
import de.metis.mail.threading.MessageThreader;
import de.metis.mail.threading.MessageThreader.ThreadInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3-002 end-to-end: SMTP send -> GreenMail server -> IMAP fetch -> SQLite sync,
 * plus threading over the synced rows.
 */
class SyncIntegrationTest {

    private static final String USER = "sync@example.com";
    private static final String PASS = "syncpass";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup[]{ServerSetup.IMAP, ServerSetup.SMTP});

    @TempDir
    Path dir;

    private MailStore store;
    private MailSyncService sync;
    private long folderId;

    @BeforeEach
    void setup() throws SQLException {
        greenMail.setUser(USER, USER, PASS);
        store = new MailStore(dir.resolve("sync.db"), dir.resolve("att"));
        long accountId = store.createAccount("sync", "imap", "127.0.0.1",
                ServerSetup.IMAP.getPort(), "127.0.0.1", ServerSetup.SMTP.getPort(), USER);
        folderId = store.getOrCreateFolder(accountId, "INBOX");
        sync = new MailSyncService(store);
    }

    private void sendMail(String subject, String body) throws Exception {
        MailMessage msg = MailMessage.builder()
                .from("Sender", "sender@example.com")
                .to("Sync", USER)
                .subject(subject)
                .plainTextBody(body)
                .build();
        try (SmtpMailClient smtp = new SmtpMailClient(new MailClientConfig(
                "127.0.0.1", ServerSetup.SMTP.getPort(), USER, PASS, MailClientConfig.Security.NONE))) {
            smtp.send(msg);
        }
    }

    @Test
    void syncFromImapStoresMessagesWithSpamScores() throws Exception {
        sendMail("Normal mail", "Hey, lunch tomorrow?");
        sendMail("URGENT VERIFY ACCOUNT NOW",
                "Klicken Sie sofort hier http://evil.biz/1 http://evil.biz/2 http://evil.biz/3 "
                        + "http://evil.click/4 http://evil.top/5 und bestaetigen Sie Ihr Passwort");

        try (ImapMailClient imap = new ImapMailClient(new MailClientConfig(
                "127.0.0.1", ServerSetup.IMAP.getPort(), USER, PASS, MailClientConfig.Security.NONE))) {
            imap.connect();
            int affected = sync.syncImap(folderId, imap, "INBOX", 50);
            assertTrue(affected >= 2);
        }

        assertEquals(2, store.countMessages(folderId));
        List<StoredMessage> all = store.listMessages(folderId, 10, 0);
        StoredMessage spam = all.stream()
                .filter(m -> m.subject().contains("URGENT")).findFirst().orElseThrow();
        StoredMessage clean = all.stream()
                .filter(m -> m.subject().equals("Normal mail")).findFirst().orElseThrow();
        assertTrue(spam.spamScore() > clean.spamScore(),
                "spam mail must score higher: spam=" + spam.spamScore() + " clean=" + clean.spamScore());
        assertTrue(spam.spamScore() > 50, "spam score should be high, got " + spam.spamScore());
    }

    @Test
    void resyncDoesNotDuplicate() throws Exception {
        sendMail("Mail one", "body");

        try (ImapMailClient imap = new ImapMailClient(new MailClientConfig(
                "127.0.0.1", ServerSetup.IMAP.getPort(), USER, PASS, MailClientConfig.Security.NONE))) {
            imap.connect();
            sync.syncImap(folderId, imap, "INBOX", 50);
            assertEquals(1, store.countMessages(folderId));

            // Second sync of the same mailbox
            sync.syncImap(folderId, imap, "INBOX", 50);
            assertEquals(1, store.countMessages(folderId), "Message-ID dedupe must prevent duplicates");

            sendMail("Mail two", "body2");
            sync.syncImap(folderId, imap, "INBOX", 50);
            assertEquals(2, store.countMessages(folderId));
        }
    }

    @Test
    void threadingGroupsSyncedConversation() throws Exception {
        // Seed a conversation: GreenMail sets Message-ID on sent messages;
        // we fetch, sync, then simulate a reply by manual header wiring.
        sendMail("Thread root", "root body");

        try (ImapMailClient imap = new ImapMailClient(new MailClientConfig(
                "127.0.0.1", ServerSetup.IMAP.getPort(), USER, PASS, MailClientConfig.Security.NONE))) {
            imap.connect();
            sync.syncImap(folderId, imap, "INBOX", 50);
        }

        List<StoredMessage> stored = store.listForThreading(folderId);
        assertEquals(1, stored.size());
        String rootMsgId = stored.get(0).messageId();
        assertNotNull(rootMsgId, "synced message must carry a Message-ID");

        // Simulate a reply row directly (as if synced later)
        MailMessage reply = MailMessage.builder()
                .from("Other", "other@example.com")
                .to("Sync", USER)
                .subject("Re: Thread root")
                .plainTextBody("reply body")
                .addHeader("Message-ID", "<reply-1@example.com>")
                .addHeader("In-Reply-To", rootMsgId)
                .addHeader("References", rootMsgId)
                .build();
        sync.syncMessages(folderId, List.of(reply));

        // Thread everything
        List<StoredMessage> all = store.listForThreading(folderId);
        List<ThreadInput> inputs = all.stream()
                .map(m -> new ThreadInput(m.id(), m.messageId(), m.inReplyTo(), m.refs()))
                .toList();
        var threadIds = new MessageThreader().computeThreads(inputs);
        store.applyThreadIds(threadIds);

        List<StoredMessage> threaded = store.listForThreading(folderId);
        assertEquals(2, threaded.size());
        Long t1 = threaded.get(0).threadId();
        Long t2 = threaded.get(1).threadId();
        assertNotNull(t1);
        assertEquals(t1, t2, "root and reply must share a thread id");

        List<StoredMessage> thread = store.getThread(t1);
        assertEquals(2, thread.size());
    }
}
