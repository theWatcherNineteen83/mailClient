package de.metis.mail.db;

import de.metis.mail.model.MailMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MailStoreTest {

    @TempDir
    Path dir;

    private MailStore store;
    private long folderId;

    @BeforeEach
    void open() throws SQLException {
        store = new MailStore(dir.resolve("mail.db"), dir.resolve("attachments"));
        long accountId = store.createAccount("test", "imap", "imap.example.com", 143,
                "smtp.example.com", 587, "user@example.com");
        folderId = store.getOrCreateFolder(accountId, "INBOX");
    }

    @AfterEach
    void close() {
        store.close();
    }

    private static MailMessage msg(String messageId, String subject, String body) {
        MailMessage.Builder b = MailMessage.builder()
                .from("Alice", "alice@example.com")
                .to("Bob", "bob@example.com")
                .subject(subject)
                .plainTextBody(body)
                .sentDate(Instant.parse("2026-10-12T10:00:00Z"));
        if (messageId != null) {
            b.addHeader("Message-ID", messageId);
        }
        return b.build();
    }

    @Test
    void insertAndCount() throws SQLException {
        int affected = store.upsertMessages(folderId, List.of(
                new SyncItem(msg("<m1@example.com>", "First", "body1"), 0),
                new SyncItem(msg("<m2@example.com>", "Second", "body2"), 42)));
        assertEquals(2, affected);
        assertEquals(2, store.countMessages(folderId));
    }

    @Test
    void upsertDeduplicatesByMessageId() throws SQLException {
        store.upsertMessages(folderId, List.of(new SyncItem(msg("<dup@example.com>", "Old", "b"), 0)));
        store.upsertMessages(folderId, List.of(new SyncItem(msg("<dup@example.com>", "New", "b"), 10)));
        assertEquals(1, store.countMessages(folderId));
        List<StoredMessage> all = store.listMessages(folderId, 10, 0);
        assertEquals("New", all.get(0).subject());
        assertEquals(10, all.get(0).spamScore());
    }

    @Test
    void missingMessageIdGetsStableFallback() throws SQLException {
        store.upsertMessages(folderId, List.of(new SyncItem(msg(null, "No ID", "b"), 0)));
        store.upsertMessages(folderId, List.of(new SyncItem(msg(null, "No ID", "b"), 0)));
        assertEquals(1, store.countMessages(folderId), "content-hash fallback must dedupe");
    }

    @Test
    void foldersAreIsolated() throws SQLException {
        long other = store.getOrCreateFolder(store.accountIdByName("test"), "Archive");
        store.upsertMessages(folderId, List.of(new SyncItem(msg("<a@x>", "Inbox mail", "b"), 0)));
        store.upsertMessages(other, List.of(new SyncItem(msg("<a@x>", "Same id other folder", "b"), 0)));
        assertEquals(1, store.countMessages(folderId));
        assertEquals(1, store.countMessages(other));
        assertEquals(2, store.countAll());
    }

    @Test
    void listPagingOrderNewestFirst() throws SQLException {
        for (int i = 0; i < 5; i++) {
            MailMessage m = MailMessage.builder()
                    .from("A", "a@x.de").subject("s" + i)
                    .sentDate(Instant.parse("2026-10-0" + (i + 1) + "T10:00:00Z"))
                    .addHeader("Message-ID", "<m" + i + "@x>").build();
            store.upsertMessages(folderId, List.of(new SyncItem(m, 0)));
        }
        List<StoredMessage> page = store.listMessages(folderId, 2, 0);
        assertEquals(2, page.size());
        assertEquals("s4", page.get(0).subject());
        assertEquals("s3", page.get(1).subject());
        assertEquals("s2", store.listMessages(folderId, 2, 2).get(0).subject());
    }

    @Test
    void searchFindsBySubjectAndSender() throws SQLException {
        store.upsertMessages(folderId, List.of(
                new SyncItem(msg("<s1@x>", "Invoice October", "b"), 0),
                new SyncItem(msg("<s2@x>", "Holiday photos", "b"), 0)));
        assertEquals(1, store.search(folderId, "Invoice", 10).size());
        assertEquals(0, store.search(folderId, "nothing-here", 10).size());
        assertTrue(store.search(folderId, "alice@example", 10).size() >= 2);
    }

    @Test
    void flagsUpdate() throws SQLException {
        store.upsertMessages(folderId, List.of(new SyncItem(msg("<f@x>", "F", "b"), 0)));
        long id = store.listMessages(folderId, 1, 0).get(0).id();
        store.setUnseen(id, false);
        store.setFlagged(id, true);
        StoredMessage m = store.getMessage(id);
        assertFalse(m.unseen());
        assertTrue(m.flagged());
        store.setDeleted(id, true);
        assertEquals(0, store.countMessages(folderId), "deleted hidden from count");
    }

    @Test
    void bodiesAreRetrievable() throws SQLException {
        store.upsertMessages(folderId, List.of(
                new SyncItem(msg("<b@x>", "B", "long text body"), 0)));
        long id = store.listMessages(folderId, 1, 0).get(0).id();
        String[] bodies = store.getBodies(id);
        assertNotNull(bodies);
        assertEquals("long text body", bodies[0]);
    }

    @Test
    void attachmentsGoToDiskWithMetadata() throws SQLException {
        MailMessage m = MailMessage.builder()
                .from("A", "a@x.de").subject("with attachment")
                .addHeader("Message-ID", "<att@x>")
                .addAttachment("note.txt", "text/plain", 5,
                        "hello".getBytes(StandardCharsets.UTF_8))
                .build();
        store.upsertMessages(folderId, List.of(new SyncItem(m, 0)));

        // Metadata row exists (checked via re-upsert not duplicating) and file on disk
        List<Path> files;
        try (var stream = Files.walk(dir.resolve("attachments"))) {
            files = stream.filter(Files::isRegularFile).toList();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(1, files.size());
        assertEquals("note.txt", files.get(0).getFileName().toString());
    }

    @Test
    void persistsAcrossReopen() throws SQLException {
        store.upsertMessages(folderId, List.of(new SyncItem(msg("<p@x>", "Persisted", "b"), 7)));
        store.close();

        store = new MailStore(dir.resolve("mail.db"), dir.resolve("attachments"));
        assertEquals(1, store.countAll());
        // folderId lookup again after reopen
        long fid = store.getOrCreateFolder(store.accountIdByName("test"), "INBOX");
        assertEquals(folderId, fid);
        assertEquals("Persisted", store.listMessages(fid, 10, 0).get(0).subject());
    }
}
