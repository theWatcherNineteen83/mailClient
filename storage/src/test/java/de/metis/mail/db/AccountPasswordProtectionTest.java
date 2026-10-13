package de.metis.mail.db;

import de.metis.mail.security.KeyringFileProtector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4-003: Konto-Passwoerter liegen verschluesselt in SQLite (Keyring), werden in der
 * API aber im Klartext geliefert; Altbestand im Klartext bleibt lesbar.
 */
class AccountPasswordProtectionTest {

    @TempDir
    Path dir;

    private String rawPassword(Path db, String account) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT password FROM accounts WHERE name='" + account + "'")) {
            assertTrue(rs.next(), "Konto muss existieren");
            return rs.getString(1);
        }
    }

    @Test
    void passwordIsEncryptedOnDiskAndPlainInApi() throws Exception {
        Path db = dir.resolve("mail.db");
        try (MailStore store = new MailStore(db, dir.resolve("attachments"))) {
            store.setSecretProtector(new KeyringFileProtector(dir.resolve("keyring.key")));
            store.createAccount("Verschluesselt", "v@example.com", "imap", "imap.v.de", 993, "ssl",
                    "smtp.v.de", 465, "ssl", "v@example.com", "strengGeheim123");

            String stored = rawPassword(db, "Verschluesselt");
            assertNotEquals("strengGeheim123", stored, "Klartext darf nicht in der DB stehen");
            assertTrue(stored.startsWith(KeyringFileProtector.PREFIX), stored);

            List<MailStore.StoredAccount> all = store.listAccounts();
            assertEquals("strengGeheim123", all.get(0).password(),
                    "die API liefert das Passwort wieder im Klartext");
        }
        assertTrue(java.nio.file.Files.exists(dir.resolve("keyring.key")));
    }

    @Test
    void updateAccountKeepsEncryption() throws Exception {
        Path db = dir.resolve("mail.db");
        try (MailStore store = new MailStore(db, dir.resolve("attachments"))) {
            store.setSecretProtector(new KeyringFileProtector(dir.resolve("keyring.key")));
            long id = store.createAccount("Konto", "k@example.com", "imap", "imap.k.de", 993, "ssl",
                    "smtp.k.de", 465, "ssl", "k@example.com", "erstes123");
            store.updateAccount(id, "Konto", "k@example.com", "imap", "imap.k.de", 993, "ssl",
                    "smtp.k.de", 465, "ssl", "k@example.com", "zweites456");

            String stored = rawPassword(db, "Konto");
            assertTrue(stored.startsWith(KeyringFileProtector.PREFIX));
            assertFalse(stored.contains("zweites456"));
            assertEquals("zweites456", store.listAccounts().get(0).password());
        }
    }

    @Test
    void legacyPlaintextRowStaysReadable() throws Exception {
        Path db = dir.resolve("mail.db");
        try (MailStore store = new MailStore(db, dir.resolve("attachments"))) {
            store.setSecretProtector(new KeyringFileProtector(dir.resolve("keyring.key")));
            store.createAccount("Alt", "a@example.com", "imap", "imap.a.de", 993, "ssl",
                    "smtp.a.de", 465, "ssl", "a@example.com", "altesPasswort");
            // Altbestand simulieren: Passwort im Klartext in der DB
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE accounts SET password='altesPasswort' WHERE name='Alt'");
            }
            assertEquals("altesPasswort", store.listAccounts().get(0).password(),
                    "Klartext-Altbestand darf den Client nicht aussperren");
        }
    }

    @Test
    void withoutProtectorEverythingStaysPlain() throws SQLException {
        Path db = dir.resolve("mail.db");
        try (MailStore store = new MailStore(db, dir.resolve("attachments"))) {
            store.createAccount("Ohne", "o@example.com", "imap", "imap.o.de", 993, "ssl",
                    "smtp.o.de", 465, "ssl", "o@example.com", "offen123");
            assertEquals("offen123", rawPassword(db, "Ohne"));
        }
    }
}
