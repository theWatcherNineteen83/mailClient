package de.metis.mail.db;

import java.time.Instant;

/**
 * A message row as stored in SQLite (metadata only, no bodies).
 */
public record StoredMessage(
        long id,
        long folderId,
        String messageId,
        String inReplyTo,
        String refs,
        String fromAddr,
        String fromName,
        String toAddrs,
        String subject,
        Instant sentDate,
        String preview,
        int spamScore,
        boolean unseen,
        boolean flagged,
        boolean deleted,
        Long threadId) {
}
