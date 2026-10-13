package de.metis.mail.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4-003: Kontoschema v2 — neue Spalten (email, password, security, smtp_security),
 * Rundtrip über createAccount/listAccounts und Migration einer Alt-DB (v1).
 */
class AccountSchemaTest {

    @TempDir
    Path dir;

    @Test
    void fullAccountRoundTrip() throws SQLException {
        try (MailStore store = new MailStore(dir.resolve("mail.db"), null)) {
            long id = store.createAccount("Georg Privat", "georg@example.com",
                    "imap", "imap.strato.de", 993, "ssl",
                    "smtp.strato.de", 465, "ssl",
                    "georg@example.com", "geheim123");
            assertTrue(id > 0);

            List<MailStore.StoredAccount> all = store.listAccounts();
            assertEquals(1, all.size());
            MailStore.StoredAccount a = all.get(0);
            assertEquals("Georg Privat", a.name());
            assertEquals("georg@example.com", a.email());
            assertEquals("imap", a.protocol());
            assertEquals("imap.strato.de", a.host());
            assertEquals(993, a.port());
            assertEquals("ssl", a.security());
            assertEquals("smtp.strato.de", a.smtpHost());
            assertEquals(465, a.smtpPort());
            assertEquals("ssl", a.smtpSecurity());
            assertEquals("georg@example.com", a.username());
            assertEquals("geheim123", a.password());
        }
    }

    @Test
    void legacySignatureStillWorksAndDefaultsToSsl() throws SQLException {
        try (MailStore store = new MailStore(dir.resolve("mail.db"), null)) {
            store.createAccount("alt", "imap", "imap.alt.de", 143, "smtp.alt.de", 587, "u@alt.de");
            MailStore.StoredAccount a = store.listAccounts().get(0);
            assertEquals("ssl", a.security(), "Legacy-Signatur muss auf Default ssl fallen");
            assertEquals("ssl", a.smtpSecurity());
            assertNull(a.email());
            assertNull(a.password());
        }
    }

    @Test
    void upsertUpdatesExistingAccount() throws SQLException {
        try (MailStore store = new MailStore(dir.resolve("mail.db"), null)) {
            store.createAccount("k", "a@x.de", "imap", "h1", 993, "ssl", "s1", 465, "ssl", "u", "p1");
            store.createAccount("k", "b@x.de", "pop3", "h2", 995, "ssl", "s2", 587, "starttls", "u", "p2");
            List<MailStore.StoredAccount> all = store.listAccounts();
            assertEquals(1, all.size(), "gleicher Kontoname = Update, kein Duplikat");
            assertEquals("pop3", all.get(0).protocol());
            assertEquals("b@x.de", all.get(0).email());
            assertEquals("starttls", all.get(0).smtpSecurity());
            assertEquals("p2", all.get(0).password());
        }
    }

    @Test
    void migratesLegacyV1Database() throws SQLException {
        Path db = dir.resolve("legacy.db");
        // v1-Schema ohne neue Spalten anlegen (Stand vor M4-003)
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement s = c.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE,
                        protocol TEXT NOT NULL DEFAULT 'imap',
                        host TEXT, port INTEGER,
                        smtp_host TEXT, smtp_port INTEGER,
                        username TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')))
                    """);
            s.executeUpdate("INSERT INTO accounts (name,protocol,host,port,smtp_host,smtp_port,username) "
                    + "VALUES ('alt','imap','h',143,'s',587,'u')");
        }

        // Wiedereröffnen → Migration muss Spalten ergänzen und Altdaten erhalten
        try (MailStore store = new MailStore(db, null)) {
            List<MailStore.StoredAccount> all = store.listAccounts();
            assertEquals(1, all.size());
            MailStore.StoredAccount a = all.get(0);
            assertEquals("alt", a.name());
            assertEquals("h", a.host());
            assertEquals("ssl", a.security(), "migrierte Zeilen bekommen Default ssl");
            assertNull(a.email());

            // Neue Konten funktionieren auf der migrierten DB sofort
            store.createAccount("neu", "n@x.de", "imap", "imap.x.de", 993, "ssl",
                    "smtp.x.de", 465, "ssl", "n@x.de", "pw");
            assertEquals(2, store.listAccounts().size());
        }
    }
}
