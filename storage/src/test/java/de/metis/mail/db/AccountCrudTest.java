package de.metis.mail.db;

import de.metis.mail.model.MailMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4-003: Konten bearbeiten und loeschen.
 * updateAccount haelt ID/Ordner/Mails, deleteAccount raeumt Ordner und Mails
 * per ON DELETE CASCADE mit ab.
 */
class AccountCrudTest {

    @TempDir
    Path dir;

    private static MailMessage msg(String messageId, String subject) {
        MailMessage.Builder b = MailMessage.builder()
                .from("Alice", "alice@example.com")
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
    void updateAccountKeepsIdAndStoredMails() throws SQLException {
        try (MailStore store = new MailStore(dir.resolve("mail.db"), dir.resolve("attachments"))) {
            long id = store.createAccount("Alt", "alt@example.com", "imap", "imap.alt.de", 993, "ssl",
                    "smtp.alt.de", 465, "ssl", "alt@example.com", "geheim");
            long folderId = store.getOrCreateFolder(id, "Posteingang");
            store.upsertMessages(folderId, List.of(new SyncItem(msg("<c1@example.com>", "Hallo"), 5)));

            int affected = store.updateAccount(id, "Neu", "neu@example.com", "pop3", "pop.pop.de", 995,
                    "starttls", "smtp.pop.de", 587, "starttls", "neu@example.com", "neuespw");
            assertEquals(1, affected);

            List<MailStore.StoredAccount> all = store.listAccounts();
            assertEquals(1, all.size(), "Bearbeiten darf kein zweites Konto anlegen");
            MailStore.StoredAccount a = all.get(0);
            assertEquals(id, a.id(), "ID bleibt stabil");
            assertEquals("Neu", a.name());
            assertEquals("neu@example.com", a.email());
            assertEquals("pop3", a.protocol());
            assertEquals("pop.pop.de", a.host());
            assertEquals(995, a.port());
            assertEquals("starttls", a.security());
            assertEquals("smtp.pop.de", a.smtpHost());
            assertEquals(587, a.smtpPort());
            assertEquals("starttls", a.smtpSecurity());
            assertEquals("neu@example.com", a.username());
            assertEquals("neuespw", a.password());

            assertEquals(1, store.countMessages(folderId), "lokale Mails ueberleben das Bearbeiten");
        }
    }

    @Test
    void renameMovesLookupToNewName() throws SQLException {
        try (MailStore store = new MailStore(dir.resolve("mail.db"), dir.resolve("attachments"))) {
            long id = store.createAccount("Vorher", "v@example.com", "imap", "imap.v.de", 993, "ssl",
                    "smtp.v.de", 465, "ssl", "v@example.com", "pw");
            store.updateAccount(id, "Nachher", "v@example.com", "imap", "imap.v.de", 993, "ssl",
                    "smtp.v.de", 465, "ssl", "v@example.com", "pw");

            assertEquals(id, store.accountIdByName("Nachher"));
            assertThrows(SQLException.class, () -> store.accountIdByName("Vorher"),
                    "alter Kontoname darf nicht mehr aufloesbar sein");
        }
    }

    @Test
    void deleteAccountCascadesFoldersAndMessages() throws SQLException {
        try (MailStore store = new MailStore(dir.resolve("mail.db"), dir.resolve("attachments"))) {
            long id = store.createAccount("Weg", "weg@example.com", "imap", "imap.weg.de", 993, "ssl",
                    "smtp.weg.de", 465, "ssl", "weg@example.com", "pw");
            long inbox = store.getOrCreateFolder(id, "Posteingang");
            store.getOrCreateFolder(id, "Gesendet");
            store.upsertMessages(inbox, List.of(new SyncItem(msg("<d1@example.com>", "Servus"), 0)));
            assertEquals(2, store.foldersOf(id).size());
            assertEquals(1, store.countAll());

            assertEquals(1, store.deleteAccount(id));

            assertTrue(store.listAccounts().isEmpty(), "Konto ist entfernt");
            assertTrue(store.foldersOf(id).isEmpty(), "Ordner per CASCADE entfernt");
            assertEquals(0, store.countAll(), "Mails per CASCADE entfernt");
        }
    }
}
