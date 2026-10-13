package de.metis.mail.db;

import de.metis.mail.model.MailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4-008: Papierkorb statt "Mail verschwindet" — soft-geloeschte Mails sind im
 * Papierkorb sichtbar, lassen sich zurueckholen und endgueltig loeschen (inkl.
 * Anhang-Dateien auf der Platte).
 */
class MailTrashTest {

    @TempDir
    Path dir;

    private MailStore store;
    private long inbox;

    @BeforeEach
    void open() throws SQLException {
        store = new MailStore(dir.resolve("mail.db"), dir.resolve("attachments"));
        long acc = store.createAccount("trash", "imap", "imap.x.de", 993, "smtp.x.de", 465, "u@x.de");
        inbox = store.getOrCreateFolder(acc, "Posteingang");
        store.getOrCreateFolder(acc, "Papierkorb");
        store.upsertMessages(inbox, List.of(
                new SyncItem(msg("<t1@x>", "Erste"), 0),
                new SyncItem(msg("<t2@x>", "Zweite"), 0)));
    }

    private static MailMessage msg(String id, String subject) {
        MailMessage.Builder b = MailMessage.builder()
                .from("Alice", "alice@example.com")
                .to("Bob", "bob@example.com")
                .subject(subject)
                .plainTextBody("body")
                .sentDate(Instant.parse("2026-10-13T18:00:00Z"));
        b.addHeader("Message-ID", id);
        return b.build();
    }

    private long idOf(String subject) throws SQLException {
        for (StoredMessage m : store.listMessages(inbox, 10, 0)) {
            if (subject.equals(m.subject())) return m.id();
        }
        throw new IllegalStateException("nicht gefunden: " + subject);
    }

    @Test
    void deleteMovesMailIntoTrashView() throws SQLException {
        long id = idOf("Erste");
        store.setDeleted(id, true);

        assertEquals(1, store.listMessages(inbox, 10, 0).size(), "geloeschte Mail ist aus der Liste weg");
        assertEquals(1, store.listDeleted(inbox, 10, 0).size(), "im Papierkorb sichtbar");
        assertEquals(1, store.countDeleted(inbox));
        assertEquals("Erste", store.listDeleted(inbox, 10, 0).get(0).subject());
    }

    @Test
    void restoreBringsMailBack() throws SQLException {
        long id = idOf("Zweite");
        store.setDeleted(id, true);
        store.restore(id);

        assertEquals(2, store.listMessages(inbox, 10, 0).size());
        assertEquals(0, store.countDeleted(inbox));
    }

    @Test
    void purgeDeletesRowAndAttachmentFile() throws SQLException, Exception {
        long id = idOf("Erste");
        store.setDeleted(id, true);

        // Anhang von Hand anlegen (Datei + Metadaten) wie beim Sync
        Path attach = dir.resolve("attachments").resolve("weg.txt");
        Files.createDirectories(attach.getParent());
        Files.writeString(attach, "inhalt", StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("mail.db").toAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO attachments (message_pk, filename, mime_type, size, path) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, id);
            ps.setString(2, "weg.txt");
            ps.setString(3, "text/plain");
            ps.setLong(4, 6);
            ps.setString(5, "weg.txt");
            ps.executeUpdate();
        }
        assertTrue(Files.exists(attach));

        assertEquals(1, store.purge(id));

        assertEquals(0, store.countDeleted(inbox), "endgueltig geloescht");
        assertFalse(Files.exists(attach), "Anhang-Datei muss mit entfernt werden");
    }

    @Test
    void emptyTrashRemovesAllDeleted() throws SQLException {
        store.setDeleted(idOf("Erste"), true);
        store.setDeleted(idOf("Zweite"), true);
        assertEquals(2, store.countDeleted(inbox));

        assertEquals(2, store.purgeDeleted(inbox));

        assertEquals(0, store.countDeleted(inbox));
        assertEquals(0, store.countAll(), "Papierkorb leeren entfernt die Datensaetze wirklich");
    }
}
