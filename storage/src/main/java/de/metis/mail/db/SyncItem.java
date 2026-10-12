package de.metis.mail.db;

import de.metis.mail.model.MailMessage;

/**
 * One mail plus its computed spam score, ready for storage.
 */
public record SyncItem(MailMessage message, int spamScore) {
}
