package de.metis.mail.db;

import de.metis.mail.model.MailMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4-004: SPF/DKIM/DMARC je Mail speichern (Schema v3) — Grundlage fuer die
 * Sicherheits-Ampel im Leser. Deckt auch die Migration einer v2-Datenbank ab.
 */
class AuthResultsStorageTest {

    @TempDir
    Path dir;

    private static MailMessage msg(String id, String subject, String authHeader) {
        MailMessage.Builder b = MailMessage.builder()
                .from("Alice", "alice@example.com")
                .to("Bob", "bob@example.com")
                .subject(subject)
                .plainTextBody("body")
                .sentDate(Instant.parse("2026-10-13T12:00:00Z"));
        b.addHeader("Message-ID", id);
        if (authHeader != null) {
            b.addHeader("Authentication-Results", authHeader);
        }
        return b.build();
    }

    @Test
    void authResultsAreStoredPerMessage() throws SQLException {
        try (MailStore store = new MailStore(dir.resolve("mail.db"), dir.resolve("attachments"))) {
            long acc = store.createAccount("a", "imap", "imap.x.de", 993, "smtp.x.de", 465, "u@x.de");
            long folder = store.getOrCreateFolder(acc, "Posteingang");
            store.upsertMessages(folder, List.of(
                    new SyncItem(msg("<a1@x>", "Gut",
                            "spf=pass smtp.mailfrom=ok.de; dkim=pass header.d=ok.de; dmarc=pass header.from=ok.de"), 0),
                    new SyncItem(msg("<a2@x>", "Boese",
                            "spf=fail smtp.mailfrom=spam.ru; dkim=fail; dmarc=fail"), 90),
                    new SyncItem(msg("<a3@x>", "Ohne", null), 0)));

            String[] good = null;
            String[] bad = null;
            String[] none = null;
            for (StoredMessage m : store.listMessages(folder, 10, 0)) {
                switch (m.subject()) {
                    case "Gut" -> good = store.authResults(m.id());
                    case "Boese" -> bad = store.authResults(m.id());
                    case "Ohne" -> none = store.authResults(m.id());
                    default -> { }
                }
            }
            assertArrayEquals(new String[]{"pass", "pass", "pass"}, good);
            assertArrayEquals(new String[]{"fail", "fail", "fail"}, bad);
            assertArrayEquals(new String[]{null, null, null}, none,
                    "ohne Authentication-Results bleibt die Ampel unbekannt");
        }
    }

    @Test
    void legacyDatabaseGetsAuthColumns() throws SQLException {
        Path db = dir.resolve("alt.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " name TEXT NOT NULL UNIQUE, protocol TEXT NOT NULL DEFAULT 'imap', host TEXT, port INTEGER,"
                    + " smtp_host TEXT, smtp_port INTEGER, username TEXT, email TEXT, password TEXT,"
                    + " security TEXT NOT NULL DEFAULT 'ssl', smtp_security TEXT NOT NULL DEFAULT 'ssl',"
                    + " created_at TEXT NOT NULL DEFAULT (datetime('now')))");
            s.executeUpdate("CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,"
                    + " name TEXT NOT NULL, last_uid INTEGER NOT NULL DEFAULT 0, UNIQUE(account_id, name))");
            s.executeUpdate("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,"
                    + " message_id TEXT NOT NULL, in_reply_to TEXT, refs TEXT, from_addr TEXT, from_name TEXT,"
                    + " to_addrs TEXT, cc_addrs TEXT, subject TEXT NOT NULL, sent_date INTEGER, preview TEXT,"
                    + " body_text TEXT, body_html TEXT, spam_score INTEGER NOT NULL DEFAULT 0,"
                    + " unseen INTEGER NOT NULL DEFAULT 1, flagged INTEGER NOT NULL DEFAULT 0,"
                    + " deleted INTEGER NOT NULL DEFAULT 0, thread_id INTEGER,"
                    + " UNIQUE(folder_id, message_id))");
            s.executeUpdate("CREATE TABLE schema_version (version INTEGER NOT NULL)");
            s.executeUpdate("INSERT INTO schema_version(version) VALUES (2)");
        }

        try (MailStore store = new MailStore(db, dir.resolve("attachments"))) {
            long acc = store.createAccount("legacy", "imap", "imap.x.de", 993, "smtp.x.de", 465, "u@x.de");
            long folder = store.getOrCreateFolder(acc, "Posteingang");
            store.upsertMessages(folder, List.of(new SyncItem(msg("<m1@x>", "Migriert",
                    "spf=pass smtp.mailfrom=ok.de; dkim=none; dmarc=pass"), 0)));
            List<StoredMessage> rows = store.listMessages(folder, 10, 0);
            assertEquals(1, rows.size(), "Alt-DB muss nach der Migration nutzbar sein");
            assertArrayEquals(new String[]{"pass", "none", "pass"}, store.authResults(rows.get(0).id()));
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement s = c.createStatement()) {
            int version = 0;
            try (var rs = s.executeQuery("SELECT version FROM schema_version")) {
                if (rs.next()) version = rs.getInt(1);
            }
            assertEquals(MailStore.SCHEMA_VERSION, version, "Schema-Version wird hochgezogen");
        }
    }
}
