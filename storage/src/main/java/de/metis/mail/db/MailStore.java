package de.metis.mail.db;

import de.metis.mail.model.MailMessage;
import de.metis.mail.security.AuthenticationResultsParser;
import de.metis.mail.security.SecretProtector;
import de.metis.mail.storage.SearchQueryBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * M3-001: SQLite persistence for mail accounts, folders, messages and attachments.
 *
 * <p>Design decisions for large mailboxes (M3-004):
 * <ul>
 *   <li>WAL journal mode + synchronous=NORMAL for fast, crash-safe writes.</li>
 *   <li>Batch upserts inside a single transaction (1000-row batches).</li>
 *   <li>Indexes on (folder_id, sent_date), message_id and thread_id.</li>
 *   <li>Attachment binaries live on disk under {@code attachmentsDir};
 *       SQLite stores metadata + relative path only (keeps the DB slim and backup-friendly).</li>
 * </ul>
 */
public class MailStore implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MailStore.class.getName());
    public static final int SCHEMA_VERSION = 3;
    private static final int BATCH_SIZE = 1000;

    private static final String UPSERT_MESSAGE_SQL = """
            INSERT INTO messages (folder_id, message_id, in_reply_to, refs,
                from_addr, from_name, to_addrs, cc_addrs, subject, sent_date,
                preview, body_text, body_html, spam_score, unseen, flagged,
                auth_spf, auth_dkim, auth_dmarc)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(folder_id, message_id) DO UPDATE SET
                subject = excluded.subject,
                spam_score = excluded.spam_score,
                unseen = excluded.unseen,
                flagged = excluded.flagged,
                auth_spf = excluded.auth_spf,
                auth_dkim = excluded.auth_dkim,
                auth_dmarc = excluded.auth_dmarc
            """;

    private static final String INSERT_ATTACHMENT_SQL = """
            INSERT INTO attachments (message_pk, filename, mime_type, size, path)
            VALUES (?,?,?,?,?)
            """;

    private final Connection conn;
    private final Path attachmentsDir; // nullable: metadata-only mode

    /** Optionaler Schutz fuer Passwoerter in der Ablage (M4-003: Keyring). */
    private SecretProtector protector = SecretProtector.NONE;

    /** Installiert den Schutz fuer Konto-Passwoerter (z. B. KeyringFileProtector). */
    public void setSecretProtector(SecretProtector protector) {
        this.protector = protector == null ? SecretProtector.NONE : protector;
    }

    /**
     * Open (and initialize) a store backed by a SQLite file.
     *
     * @param dbFile        database file (created if missing); use ":memory:" URL form via {@link #MailStore(String, Path)}
     * @param attachmentsDir directory for attachment binaries; may be null (metadata only)
     */
    public MailStore(Path dbFile, Path attachmentsDir) throws SQLException {
        this("jdbc:sqlite:" + dbFile.toAbsolutePath(), attachmentsDir);
    }

    public MailStore(String jdbcUrl, Path attachmentsDir) throws SQLException {
        this.conn = DriverManager.getConnection(jdbcUrl);
        this.attachmentsDir = attachmentsDir;
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
        }
        if (attachmentsDir != null) {
            try {
                Files.createDirectories(attachmentsDir);
            } catch (IOException e) {
                throw new SQLException("Cannot create attachments dir: " + attachmentsDir, e);
            }
        }
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE,
                        protocol TEXT NOT NULL DEFAULT 'imap',
                        host TEXT,
                        port INTEGER,
                        smtp_host TEXT,
                        smtp_port INTEGER,
                        username TEXT,
                        email TEXT,
                        password TEXT,
                        security TEXT NOT NULL DEFAULT 'ssl',
                        smtp_security TEXT NOT NULL DEFAULT 'ssl',
                        created_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )""");
            // Migration v1 -> v2: fehlende Spalten an bestehenden accounts-Tabellen ergänzen
            for (String col : new String[]{"email TEXT", "password TEXT",
                    "security TEXT NOT NULL DEFAULT 'ssl'", "smtp_security TEXT NOT NULL DEFAULT 'ssl'"}) {
                String name = col.split(" ")[0];
                boolean present = false;
                try (ResultSet rs = s.executeQuery("PRAGMA table_info(accounts)")) {
                    while (rs.next()) {
                        if (name.equalsIgnoreCase(rs.getString("name"))) { present = true; break; }
                    }
                }
                if (!present) s.executeUpdate("ALTER TABLE accounts ADD COLUMN " + col);
            }
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                        name TEXT NOT NULL,
                        last_uid INTEGER NOT NULL DEFAULT 0,
                        UNIQUE(account_id, name)
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
                        message_id TEXT NOT NULL,
                        in_reply_to TEXT,
                        refs TEXT,
                        from_addr TEXT,
                        from_name TEXT,
                        to_addrs TEXT,
                        cc_addrs TEXT,
                        subject TEXT NOT NULL,
                        sent_date INTEGER,
                        preview TEXT,
                        body_text TEXT,
                        body_html TEXT,
                        spam_score INTEGER NOT NULL DEFAULT 0,
                        auth_spf TEXT,
                        auth_dkim TEXT,
                        auth_dmarc TEXT,
                        unseen INTEGER NOT NULL DEFAULT 1,
                        flagged INTEGER NOT NULL DEFAULT 0,
                        deleted INTEGER NOT NULL DEFAULT 0,
                        thread_id INTEGER,
                        UNIQUE(folder_id, message_id)
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS attachments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        message_pk INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                        filename TEXT,
                        mime_type TEXT,
                        size INTEGER,
                        path TEXT
                    )""");
            // Migration v2 -> v3: Authentifizierungs-Ergebnisse (SPF/DKIM/DMARC) je Mail
            for (String col : new String[]{"auth_spf TEXT", "auth_dkim TEXT", "auth_dmarc TEXT"}) {
                String name = col.split(" ")[0];
                boolean present = false;
                try (ResultSet rs = s.executeQuery("PRAGMA table_info(messages)")) {
                    while (rs.next()) {
                        if (name.equalsIgnoreCase(rs.getString("name"))) { present = true; break; }
                    }
                }
                if (!present) s.executeUpdate("ALTER TABLE messages ADD COLUMN " + col);
            }
            s.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_messages_folder_date ON messages(folder_id, sent_date DESC)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_messages_message_id ON messages(message_id)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_messages_thread ON messages(thread_id)");

            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM schema_version")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    s.executeUpdate("INSERT INTO schema_version(version) VALUES (" + SCHEMA_VERSION + ")");
                } else {
                    s.executeUpdate("UPDATE schema_version SET version=" + SCHEMA_VERSION);
                }
            }
        }
        LOG.info("MailStore schema v" + SCHEMA_VERSION + " ready");
    }

    // ---------- accounts & folders ----------

    public long createAccount(String name, String protocol, String host, int port,
                              String smtpHost, int smtpPort, String username) throws SQLException {
        return createAccount(name, null, protocol, host, port, "ssl", smtpHost, smtpPort, "ssl", username, null);
    }

    /**
     * M4-003 (vollständig): Konto mit allen Verbindungsdaten anlegen/aktualisieren.
     * Hinweis: Das Passwort liegt aktuell KLARTEXT in SQLite — Keyring/VS-Ablage folgt.
     */
    public long createAccount(String name, String email, String protocol, String host, int port,
                              String security, String smtpHost, int smtpPort, String smtpSecurity,
                              String username, String password) throws SQLException {
        String sql = """
                INSERT INTO accounts (name, email, protocol, host, port, security,
                    smtp_host, smtp_port, smtp_security, username, password)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(name) DO UPDATE SET
                    email=excluded.email, protocol=excluded.protocol, host=excluded.host, port=excluded.port,
                    security=excluded.security, smtp_host=excluded.smtp_host, smtp_port=excluded.smtp_port,
                    smtp_security=excluded.smtp_security, username=excluded.username, password=excluded.password
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, protocol);
            ps.setString(4, host);
            ps.setInt(5, port);
            ps.setString(6, security);
            ps.setString(7, smtpHost);
            ps.setInt(8, smtpPort);
            ps.setString(9, smtpSecurity);
            ps.setString(10, username);
            ps.setString(11, protector.protect(password));
            ps.executeUpdate();
        }
        return accountIdByName(name);
    }

    /** Alle Konten inkl. Verbindungsdaten (Reihenfolge: Anlage). */
    public List<StoredAccount> listAccounts() throws SQLException {
        List<StoredAccount> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id,name,email,protocol,host,port,security,smtp_host,smtp_port,smtp_security,username,password FROM accounts ORDER BY id")) {
            while (rs.next()) {
                out.add(new StoredAccount(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7),
                        rs.getString(8), rs.getInt(9), rs.getString(10), rs.getString(11),
                        protector.reveal(rs.getString(12))));
            }
        }
        return out;
    }

    /** M4-003: bestehendes Konto aktualisieren — ID bleibt stabil, Ordner und Mails bleiben erhalten. */
    public int updateAccount(long id, String name, String email, String protocol, String host, int port,
                             String security, String smtpHost, int smtpPort, String smtpSecurity,
                             String username, String password) throws SQLException {
        String sql = """
                UPDATE accounts SET name=?, email=?, protocol=?, host=?, port=?, security=?,
                    smtp_host=?, smtp_port=?, smtp_security=?, username=?, password=?
                WHERE id=?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, protocol);
            ps.setString(4, host);
            ps.setInt(5, port);
            ps.setString(6, security);
            ps.setString(7, smtpHost);
            ps.setInt(8, smtpPort);
            ps.setString(9, smtpSecurity);
            ps.setString(10, username);
            ps.setString(11, protector.protect(password));
            ps.setLong(12, id);
            return ps.executeUpdate();
        }
    }

    /** M4-003: Konto loeschen — Ordner und Mails folgen per ON DELETE CASCADE. */
    public int deleteAccount(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM accounts WHERE id=?")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    /** Konto-Datensatz für die UI (M4-003). */
    public record StoredAccount(long id, String name, String email, String protocol,
                                String host, int port, String security,
                                String smtpHost, int smtpPort, String smtpSecurity,
                                String username, String password) { }

    public long accountIdByName(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM accounts WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Account not found: " + name);
    }

    public long getOrCreateFolder(long accountId, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO folders (account_id, name) VALUES (?,?) ON CONFLICT(account_id,name) DO NOTHING")) {
            ps.setLong(1, accountId);
            ps.setString(2, name);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM folders WHERE account_id=? AND name=?")) {
            ps.setLong(1, accountId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Folder not found after create: " + name);
    }

    /** All folders of one account as name -> folderId (INSERT order). */
    public java.util.Map<String, Long> foldersOf(long accountId) throws SQLException {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name FROM folders WHERE account_id=? ORDER BY id")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(2), rs.getLong(1));
            }
        }
        return out;
    }

    // ---------- message upsert (sync target) ----------

    /**
     * Insert or update messages for a folder in efficient batches.
     * De-duplication key is (folder_id, message_id); Message-ID falls back to a
     * content hash when the header is missing.
     *
     * @return number of rows inserted or updated
     */
    public int upsertMessages(long folderId, List<SyncItem> items) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        int affected = 0;
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_MESSAGE_SQL)) {
            int inBatch = 0;
            for (SyncItem item : items) {
                bindMessage(ps, folderId, item);
                ps.addBatch();
                inBatch++;
                if (inBatch % BATCH_SIZE == 0) {
                    affected += sum(ps.executeBatch());
                }
            }
            if (inBatch % BATCH_SIZE != 0) {
                affected += sum(ps.executeBatch());
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }

        // Attachment files + metadata (outside the hot batch path)
        for (SyncItem item : items) {
            storeAttachments(folderId, item);
        }
        return affected;
    }

    private static int sum(int[] counts) {
        int total = 0;
        for (int c : counts) {
            if (c > 0) total += c; // SQLite may return SUCCESS_NO_INFO
        }
        return total;
    }

    private void bindMessage(PreparedStatement ps, long folderId, SyncItem item) throws SQLException {
        MailMessage m = item.message();
        String messageId = headerOrFallback(m, "Message-ID");
        ps.setLong(1, folderId);
        ps.setString(2, messageId);
        ps.setString(3, firstHeader(m, "In-Reply-To"));
        ps.setString(4, firstHeader(m, "References"));
        if (!m.getFrom().isEmpty()) {
            ps.setString(5, m.getFrom().get(0).getEmail());
            ps.setString(6, m.getFrom().get(0).getName());
        } else {
            ps.setNull(5, java.sql.Types.VARCHAR);
            ps.setNull(6, java.sql.Types.VARCHAR);
        }
        ps.setString(7, joinAddresses(m.getTo()));
        ps.setString(8, joinAddresses(m.getCc()));
        ps.setString(9, m.getSubject());
        ps.setLong(10, m.getSentDate() != null ? m.getSentDate().toEpochMilli() : 0L);
        ps.setString(11, preview(m));
        ps.setString(12, m.getPlainTextBody());
        ps.setString(13, m.getHtmlBody());
        ps.setInt(14, item.spamScore());
        ps.setInt(15, m.isUnseen() ? 1 : 0);
        ps.setInt(16, m.isFlagged() ? 1 : 0);
        String[] auth = authResultsOf(m);
        ps.setString(17, auth[0]);
        ps.setString(18, auth[1]);
        ps.setString(19, auth[2]);
    }

    /** SPF/DKIM/DMARC-Status aus dem Authentication-Results-Header (M4-004). */
    private static String[] authResultsOf(MailMessage m) {
        String[] out = new String[]{null, null, null};
        String header = firstHeader(m, "Authentication-Results");
        if (header == null || header.isBlank()) {
            return out;
        }
        for (AuthenticationResultsParser.AuthResult r : new AuthenticationResultsParser().parse(header)) {
            if (r.protocol == null || r.status == null) continue;
            switch (r.protocol.toLowerCase(java.util.Locale.ROOT)) {
                case "spf" -> out[0] = r.status;
                case "dkim" -> out[1] = r.status;
                case "dmarc" -> out[2] = r.status;
                default -> { }
            }
        }
        return out;
    }

    private String joinAddresses(List<MailMessage.MailAddress> addrs) {
        if (addrs == null || addrs.isEmpty()) return null;
        return addrs.stream().map(MailMessage.MailAddress::getEmail).collect(Collectors.joining(", "));
    }

    private static String preview(MailMessage m) {
        String body = m.getPlainTextBody() != null ? m.getPlainTextBody() : m.getHtmlBody();
        if (body == null) return null;
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) : oneLine;
    }

    private static String firstHeader(MailMessage m, String name) {
        List<String> vals = m.getHeaders().get(name);
        return vals == null || vals.isEmpty() ? null : vals.get(0);
    }

    /** Message-ID header, or a stable content-hash fallback when absent. */
    private String headerOrFallback(MailMessage m, String name) {
        String v = firstHeader(m, name);
        if (v != null && !v.isBlank()) return v.trim();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String basis = m.getSubject() + "|" + (m.getSentDate() != null ? m.getSentDate() : "")
                    + "|" + (m.getFrom().isEmpty() ? "" : m.getFrom().get(0).getEmail());
            return "gen-" + HexFormat.of().formatHex(md.digest(basis.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void storeAttachments(long folderId, SyncItem item) throws SQLException {
        MailMessage m = item.message();
        if (m.getAttachments().isEmpty()) return;
        String messageId = headerOrFallback(m, "Message-ID");
        long pk;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM messages WHERE folder_id=? AND message_id=?")) {
            ps.setLong(1, folderId);
            ps.setString(2, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                pk = rs.getLong(1);
            }
        }
        // Skip if attachments already recorded for this message
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM attachments WHERE message_pk=?")) {
            ps.setLong(1, pk);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) return;
            }
        }
        for (MailMessage.Attachment att : m.getAttachments()) {
            String relPath = null;
            if (attachmentsDir != null) {
                try {
                    String dirName = "msg-" + Math.abs(messageId.hashCode());
                    Path dir = attachmentsDir.resolve(dirName);
                    Files.createDirectories(dir);
                    String safeName = att.getFileName().replaceAll("[^A-Za-z0-9._-]", "_");
                    Path file = dir.resolve(safeName);
                    Files.write(file, att.getData());
                    relPath = dirName + "/" + safeName;
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to store attachment " + att.getFileName(), e);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(INSERT_ATTACHMENT_SQL)) {
                ps.setLong(1, pk);
                ps.setString(2, att.getFileName());
                ps.setString(3, att.getMimeType());
                ps.setLong(4, att.getSize());
                ps.setString(5, relPath);
                ps.executeUpdate();
            }
        }
    }

    // ---------- queries ----------

    public long countMessages(long folderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM messages WHERE folder_id=? AND deleted=0")) {
            ps.setLong(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public long countAll() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM messages")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /**
     * Page through a folder, newest first.
     */
    public List<StoredMessage> listMessages(long folderId, int limit, int offset) throws SQLException {
        String sql = """
                SELECT id, folder_id, message_id, in_reply_to, refs, from_addr, from_name, to_addrs,
                       subject, sent_date, preview, spam_score, unseen, flagged, deleted, thread_id
                FROM messages
                WHERE folder_id=? AND deleted=0
                ORDER BY sent_date DESC, id DESC
                LIMIT ? OFFSET ?
                """;
        List<StoredMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, folderId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        }
        return out;
    }

    public StoredMessage getMessage(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, folder_id, message_id, in_reply_to, refs, from_addr, from_name, to_addrs,"
                        + " subject, sent_date, preview, spam_score, unseen, flagged, deleted, thread_id"
                        + " FROM messages WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /** SPF/DKIM/DMARC einer Mail (Index 0/1/2); fehlende Werte sind null. */
    public String[] authResults(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT auth_spf, auth_dkim, auth_dmarc FROM messages WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new String[]{rs.getString(1), rs.getString(2), rs.getString(3)};
                }
            }
        }
        return new String[]{null, null, null};
    }

    /** Soft-geloeschte Mails eines Ordners (Papierkorb-Ansicht, M4-008). */
    public List<StoredMessage> listDeleted(long folderId, int limit, int offset) throws SQLException {
        String sql = """
                SELECT id, folder_id, message_id, in_reply_to, refs, from_addr, from_name, to_addrs,
                       subject, sent_date, preview, spam_score, unseen, flagged, deleted, thread_id
                FROM messages
                WHERE folder_id=? AND deleted=1
                ORDER BY sent_date DESC, id DESC
                LIMIT ? OFFSET ?
                """;
        List<StoredMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, folderId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        }
        return out;
    }

    /** Anzahl der Mails im Papierkorb eines Ordners. */
    public int countDeleted(long folderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM messages WHERE folder_id=? AND deleted=1")) {
            ps.setLong(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Mail aus dem Papierkorb zurueckholen. */
    public void restore(long id) throws SQLException {
        setDeleted(id, false);
    }

    /**
     * Eine Mail endgueltig loeschen: Datensatz, Anhang-Metadaten und die
     * Anhang-Dateien auf der Platte.
     */
    public int purge(long id) throws SQLException {
        List<String> files = attachmentPaths(id);
        int removed;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM messages WHERE id=?")) {
            ps.setLong(1, id);
            removed = ps.executeUpdate();
        }
        deleteFiles(files);
        return removed;
    }

    /** Papierkorb eines Ordners endgueltig leeren. */
    public int purgeDeleted(long folderId) throws SQLException {
        List<String> files = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT a.path FROM attachments a JOIN messages m ON a.message_pk = m.id"
                        + " WHERE m.folder_id=? AND m.deleted=1")) {
            ps.setLong(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1) != null) files.add(rs.getString(1));
                }
            }
        }
        int removed;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM messages WHERE folder_id=? AND deleted=1")) {
            ps.setLong(1, folderId);
            removed = ps.executeUpdate();
        }
        deleteFiles(files);
        return removed;
    }

    private List<String> attachmentPaths(long messageId) throws SQLException {
        List<String> files = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT path FROM attachments WHERE message_pk=?")) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1) != null) files.add(rs.getString(1));
                }
            }
        }
        return files;
    }

    private void deleteFiles(List<String> paths) {
        for (String path : paths) {
            try {
                if (attachmentsDir != null && path != null) {
                    Files.deleteIfExists(attachmentsDir.resolve(path));
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "Anhang-Datei nicht loeschbar: " + path, e);
            }
        }
    }

    /** Full bodies for one message (text + html). */
    public String[] getBodies(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT body_text, body_html FROM messages WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new String[]{rs.getString(1), rs.getString(2)};
            }
        }
        return null;
    }

    /**
     * Suche im Ordner. Der WHERE-Teil kommt aus {@link SearchQueryBuilder}: Feld-Praefixe
     * (from:, subject:, has:attachment) und Volltext, immer als Platzhalter + Parameter.
     */
    public List<StoredMessage> search(long folderId, String query, int limit) throws SQLException {
        SearchQueryBuilder.SearchQuery q = new SearchQueryBuilder().build(query);
        String sql = """
                SELECT id, folder_id, message_id, in_reply_to, refs, from_addr, from_name, to_addrs,
                       subject, sent_date, preview, spam_score, unseen, flagged, deleted, thread_id
                FROM messages
                WHERE folder_id=? AND deleted=0 AND (""" + q.sql() + """
                )
                ORDER BY sent_date DESC LIMIT ?
                """;
        List<StoredMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, folderId);
            for (String param : q.parameters()) {
                ps.setString(i++, param);
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        }
        return out;
    }

    private static String escapeLike(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public void setUnseen(long id, boolean unseen) throws SQLException {
        updateFlag(id, "unseen", unseen);
    }

    public void setFlagged(long id, boolean flagged) throws SQLException {
        updateFlag(id, "flagged", flagged);
    }

    public void setDeleted(long id, boolean deleted) throws SQLException {
        updateFlag(id, "deleted", deleted);
    }

    private void updateFlag(long id, String column, boolean value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE messages SET " + column + "=? WHERE id=?")) {
            ps.setInt(1, value ? 1 : 0);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // ---------- threading support ----------

    public List<StoredMessage> listForThreading(long folderId) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.setFetchSize(5000);
        }
        String sql = "SELECT id, folder_id, message_id, in_reply_to, refs, from_addr, from_name,"
                + " to_addrs, subject, sent_date, preview, spam_score, unseen, flagged, deleted, thread_id"
                + " FROM messages WHERE folder_id=?";
        List<StoredMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        }
        return out;
    }

    public void applyThreadIds(Map<Long, Long> dbIdToThreadId) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement("UPDATE messages SET thread_id=? WHERE id=?")) {
            int inBatch = 0;
            for (Map.Entry<Long, Long> e : dbIdToThreadId.entrySet()) {
                ps.setLong(1, e.getValue());
                ps.setLong(2, e.getKey());
                ps.addBatch();
                if (++inBatch % BATCH_SIZE == 0) ps.executeBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    public List<StoredMessage> getThread(long threadId) throws SQLException {
        String sql = "SELECT id, folder_id, message_id, in_reply_to, refs, from_addr, from_name,"
                + " to_addrs, subject, sent_date, preview, spam_score, unseen, flagged, deleted, thread_id"
                + " FROM messages WHERE thread_id=? ORDER BY sent_date ASC";
        List<StoredMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        }
        return out;
    }

    private StoredMessage mapRow(ResultSet rs) throws SQLException {
        long sentMillis = rs.getLong("sent_date");
        long tid = rs.getLong("thread_id");
        return new StoredMessage(
                rs.getLong("id"),
                rs.getLong("folder_id"),
                rs.getString("message_id"),
                rs.getString("in_reply_to"),
                rs.getString("refs"),
                rs.getString("from_addr"),
                rs.getString("from_name"),
                rs.getString("to_addrs"),
                rs.getString("subject"),
                rs.wasNull() || sentMillis == 0 ? null : Instant.ofEpochMilli(sentMillis),
                rs.getString("preview"),
                rs.getInt("spam_score"),
                rs.getInt("unseen") == 1,
                rs.getInt("flagged") == 1,
                rs.getInt("deleted") == 1,
                rs.wasNull() ? null : tid);
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
                LOG.info("MailStore closed");
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Error closing MailStore", e);
        }
    }
}
