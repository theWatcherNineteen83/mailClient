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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** M4-004: Content-ID (cid:) je Anhang speichern (Schema v4) + Migration v3 -> v4. */
class AttachmentContentIdTest {

    @TempDir
    Path dir;

    private static MailMessage msg(String id, Consumer<MailMessage.Builder> attachments) {
        MailMessage.Builder b = MailMessage.builder()
                .from("Alice", "alice@example.com")
                .to("Bob", "bob@example.com")
                .subject("cid")
                .plainTextBody("body")
                .sentDate(Instant.parse("2026-10-18T00:00:00Z"));
        b.addHeader("Message-ID", id);
        if (attachments != null) {
            attachments.accept(b);
        }
        return b.build();
    }

    @Test
    void contentIdRoundTripsThroughStore() throws SQLException {
        byte[] png = { (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a };
        try (MailStore store = new MailStore(dir.resolve("mail.db"), dir.resolve("attachments"))) {
            long acc = store.createAccount("a", "imap", "imap.x.de", 993, "smtp.x.de", 465, "u@x.de");
            long folder = store.getOrCreateFolder(acc, "Posteingang");
            MailMessage m = msg("<c1@x>", b -> {
                b.addAttachment("inline-logo123.png", "image/png", png.length, png, "logo123");
                b.addAttachment("report.pdf", "application/pdf", 3, new byte[]{1,2,3});
            });
            store.upsertMessages(folder, List.of(new SyncItem(m, 0)));

            StoredMessage row = store.listMessages(folder, 10, 0).get(0);
            List<MailStore.StoredAttachment> atts = store.listAttachments(row.id());
            assertEquals(2, atts.size());
            assertEquals("logo123", atts.get(0).contentId());
            assertNull(atts.get(1).contentId());
        }
    }

    @Test
    void legacyV3DatabaseGetsContentIdColumn() throws SQLException {
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
                    + " auth_spf TEXT, auth_dkim TEXT, auth_dmarc TEXT,"
                    + " UNIQUE(folder_id, message_id))");
            s.executeUpdate("CREATE TABLE attachments (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " message_pk INTEGER NOT NULL, filename TEXT, mime_type TEXT, size INTEGER, path TEXT)");
            s.executeUpdate("CREATE TABLE schema_version (version INTEGER NOT NULL)");
            s.executeUpdate("INSERT INTO schema_version(version) VALUES (3)");
        }

        try (MailStore store = new MailStore(db, dir.resolve("attachments"))) {
            long acc = store.createAccount("legacy", "imap", "imap.x.de", 993, "smtp.x.de", 465, "u@x.de");
            long folder = store.getOrCreateFolder(acc, "Posteingang");
            store.upsertMessages(folder, List.of(new SyncItem(
                msg("<m1@x>", b -> b.addAttachment("alt.bin", "application/octet-stream", 1, new byte[]{9})), 0)));
            StoredMessage row = store.listMessages(folder, 10, 0).get(0);
            List<MailStore.StoredAttachment> atts = store.listAttachments(row.id());
            assertEquals(1, atts.size());
            assertNull(atts.get(0).contentId(), "Alt-Anhang bleibt ohne Content-ID lesbar");
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
