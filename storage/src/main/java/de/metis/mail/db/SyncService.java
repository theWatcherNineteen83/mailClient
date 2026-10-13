package de.metis.mail.db;

import de.metis.mail.model.MailMessage;
import de.metis.mail.protocol.ImapMailClient;
import de.metis.mail.protocol.MailClientConfig;
import de.metis.mail.protocol.Pop3MailClient;
import de.metis.mail.spam.SpamHeuristicScorer;

import java.util.ArrayList;
import java.util.List;

/**
 * M4-003: Holt die Mails eines angelegten Kontos (IMAP oder POP3) in die lokale
 * SQLite-Ablage. Der Aufrufer führt {@link #syncAccount} in einem Worker-Thread aus
 * (SwingWorker) — diese Klasse macht bewusst blockierendes Netzwerk-IO.
 *
 * <p>Ablauf pro Konto: Login → INBOX abrufen (max. N Mails) → Spam-Score per
 * {@link SpamHeuristicScorer} → {@code upsertMessages} in den lokalen Ordner
 * „Posteingang“ (De-Duplizierung über Message-ID passiert in {@link MailStore}).
 */
public final class SyncService {

    /** Lokaler Zielordner für abgerufene Mails (Name wie im Demo-Bestand). */
    public static final String INBOX_LOCAL = "Posteingang";

    public static final int DEFAULT_MAX = 200;

    private final SpamHeuristicScorer scorer = new SpamHeuristicScorer();

    /** Ergebnis eines Konto-Syncs; {@code error == null} heißt Erfolg. */
    public record SyncResult(String account, int fetched, int stored, String error) {
        public boolean ok() { return error == null; }
    }

    /** Sync nur für Konten mit echten Zugangsdaten (Demo-Konten überspringen). */
    public static boolean hasCredentials(MailStore.StoredAccount acc) {
        return notBlank(acc.host()) && notBlank(acc.username()) && notBlank(acc.password());
    }

    public SyncResult syncAccount(MailStore store, MailStore.StoredAccount acc) {
        return syncAccount(store, acc, DEFAULT_MAX);
    }

    public SyncResult syncAccount(MailStore store, MailStore.StoredAccount acc, int max) {
        if (!hasCredentials(acc)) {
            return new SyncResult(acc.name(), 0, 0, "Keine Zugangsdaten hinterlegt");
        }
        try {
            MailClientConfig cfg = new MailClientConfig(acc.host(), acc.port(), acc.username(),
                    acc.password(), securityOf(acc.security()));
            List<MailMessage> fetched = "pop3".equalsIgnoreCase(acc.protocol())
                    ? fetchPop3(cfg, max) : fetchImap(cfg, max);
            long folderId = store.getOrCreateFolder(acc.id(), INBOX_LOCAL);
            List<SyncItem> items = new ArrayList<>(fetched.size());
            for (MailMessage m : fetched) {
                items.add(new SyncItem(m, score(m)));
            }
            int stored = store.upsertMessages(folderId, items);
            return new SyncResult(acc.name(), fetched.size(), stored, null);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new SyncResult(acc.name(), 0, 0, msg);
        }
    }

    // ---------- Empfang ----------

    private static List<MailMessage> fetchImap(MailClientConfig cfg, int max) throws Exception {
        try (ImapMailClient c = new ImapMailClient(cfg)) {
            c.connect();
            return c.fetchMessages("INBOX", max);
        }
    }

    private static List<MailMessage> fetchPop3(MailClientConfig cfg, int max) throws Exception {
        try (Pop3MailClient c = new Pop3MailClient(cfg)) {
            c.connect();
            return c.fetchMessages(max);
        }
    }

    // ---------- Hilfs ----------

    private static MailClientConfig.Security securityOf(String name) {
        if (name == null || name.isBlank()) return MailClientConfig.Security.SSL;
        try {
            return MailClientConfig.Security.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MailClientConfig.Security.SSL;
        }
    }

    private int score(MailMessage m) {
        String from = firstHeader(m, "From");
        String returnPath = firstHeader(m, "Return-Path");
        boolean hasAuth = m.getHeaders() != null
                && m.getHeaders().keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Authentication-Results"));
        try {
            return scorer.score(from, returnPath, m.getSubject(), m.getPlainTextBody(), hasAuth);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String firstHeader(MailMessage m, String name) {
        if (m.getHeaders() == null) return null;
        for (var e : m.getHeaders().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
