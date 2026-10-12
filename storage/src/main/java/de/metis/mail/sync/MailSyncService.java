package de.metis.mail.sync;

import de.metis.mail.db.MailStore;
import de.metis.mail.db.SyncItem;
import de.metis.mail.model.MailMessage;
import de.metis.mail.protocol.ImapMailClient;
import de.metis.mail.protocol.Pop3MailClient;
import de.metis.mail.spam.SpamHeuristicScorer;

import jakarta.mail.MessagingException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * M3-002: Sync logic — fetch messages from IMAP or POP3, compute spam scores,
 * and upsert them into the SQLite store (de-duplicated by Message-ID per folder).
 */
public class MailSyncService {

    private static final Logger LOG = Logger.getLogger(MailSyncService.class.getName());

    private final MailStore store;
    private final SpamHeuristicScorer scorer = new SpamHeuristicScorer();

    public MailSyncService(MailStore store) {
        this.store = store;
    }

    /**
     * Sync a folder from an already-connected IMAP client.
     *
     * @return number of rows inserted/updated
     */
    public int syncImap(long folderId, ImapMailClient client, String folderName, int maxMessages)
            throws MessagingException, IOException, java.sql.SQLException {
        List<MailMessage> fetched = client.fetchMessages(folderName, maxMessages);
        return syncMessages(folderId, fetched);
    }

    /**
     * Sync from an already-connected POP3 client (single mailbox).
     */
    public int syncPop3(long folderId, Pop3MailClient client, int maxMessages)
            throws MessagingException, IOException, java.sql.SQLException {
        List<MailMessage> fetched = client.fetchMessages(maxMessages);
        return syncMessages(folderId, fetched);
    }

    /**
     * Protocol-agnostic sync: score and store a batch of messages.
     */
    public int syncMessages(long folderId, List<MailMessage> fetched) throws java.sql.SQLException {
        List<SyncItem> items = new ArrayList<>(fetched.size());
        for (MailMessage m : fetched) {
            items.add(new SyncItem(m, score(m)));
        }
        int affected = store.upsertMessages(folderId, items);
        LOG.info("Synced " + fetched.size() + " messages into folder " + folderId
                + " (" + affected + " rows affected)");
        return affected;
    }

    private int score(MailMessage m) {
        String fromHeader = m.getFrom().isEmpty()
                ? null
                : (m.getFrom().get(0).getName() == null ? "" : m.getFrom().get(0).getName())
                        + " <" + m.getFrom().get(0).getEmail() + ">";
        List<String> rp = m.getHeaders().get("Return-Path");
        String returnPath = rp == null || rp.isEmpty() ? null : rp.get(0);
        boolean hasAuth = m.getHeaders().containsKey("Authentication-Results");
        String body = m.getPlainTextBody() != null ? m.getPlainTextBody() : m.getHtmlBody();
        return scorer.score(fromHeader, returnPath, m.getSubject(), body, hasAuth);
    }
}
