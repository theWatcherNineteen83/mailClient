package de.metis.mail.db;

import de.metis.mail.model.MailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suche gegen das echte Schema: Feld-Praefixe, Wildcard-Escaping, Anhang-Filter.
 * Deckt die Integration des von Metis erzeugten SearchQueryBuilder ab.
 */
class MailSearchTest {

    @TempDir
    Path dir;

    private MailStore store;
    private long folderId;

    @BeforeEach
    void open() throws SQLException {
        store = new MailStore(dir.resolve("mail.db"), dir.resolve("attachments"));
        long accountId = store.createAccount("test", "imap", "imap.example.com", 143,
                "smtp.example.com", 587, "user@example.com");
        folderId = store.getOrCreateFolder(accountId, "Posteingang");
        store.upsertMessages(folderId, List.of(
                new SyncItem(msg("<s1@example.com>", "Rechnung Oktober", "alice"), 0),
                new SyncItem(msg("<s2@example.com>", "Projektbericht", "bob"), 0),
                new SyncItem(msg("<s3@example.com>", "Rabatt 50% im Shop", "carol"), 0)));
    }

    private static MailMessage msg(String messageId, String subject, String from) {
        MailMessage.Builder b = MailMessage.builder()
                .from(from, from + "@example.com")
                .to("Bob", "bob@example.com")
                .subject(subject)
                .plainTextBody("body")
                .sentDate(Instant.parse("2026-10-13T10:00:00Z"));
        if (messageId != null) {
            b.addHeader("Message-ID", messageId);
        }
        return b.build();
    }

    @Test
    void fullTextSearchFindsSubject() throws SQLException {
        assertEquals(1, store.search(folderId, "Projektbericht", 10).size());
    }

    @Test
    void subjectPrefixMatchesOnlySubject() throws SQLException {
        List<StoredMessage> hits = store.search(folderId, "subject:rechnung", 10);
        assertEquals(1, hits.size(), "COLLATE NOCASE muss greifen");
        assertEquals("Rechnung Oktober", hits.get(0).subject());
    }

    @Test
    void fromPrefixMatchesSender() throws SQLException {
        assertEquals(1, store.search(folderId, "from:alice", 10).size());
        assertEquals(0, store.search(folderId, "from:niemand", 10).size());
    }

    @Test
    void percentInQueryIsLiteralNotWildcard() throws SQLException {
        assertEquals(1, store.search(folderId, "50%", 10).size(),
                "escaped Prozent darf kein Alles-Matcher sein");
        assertEquals(0, store.search(folderId, "ab%", 10).size());
    }

    @Test
    void attachmentFilterRunsAgainstAttachmentsTable() throws SQLException {
        assertEquals(3, store.search(folderId, "has:attachment:no", 10).size(),
                "ohne Anhaenge muessen alle Treffer liefern");
        assertEquals(0, store.search(folderId, "has:attachment:yes", 10).size());
    }

    @Test
    void injectionAttemptDoesNotBreakSql() throws SQLException {
        assertEquals(0, store.search(folderId, "x%' OR 1=1 --", 10).size(),
                "Injection-Versuch darf nichts finden und nichts zerstoeren");
        assertEquals(3, store.search(folderId, "", 10).size(), "Tabelle lebt noch");
    }

    @Test
    void emptyQueryReturnsAllNonDeleted() throws SQLException {
        assertEquals(3, store.search(folderId, "", 10).size(), "leere Suche = keine Einschraenkung");
        assertEquals(3, store.search(folderId, null, 10).size(), "null-Suche ebenso");
    }
}
