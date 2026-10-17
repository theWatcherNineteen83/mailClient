package de.metis.mail.desktop;

import de.metis.mail.db.MailStore;
import de.metis.mail.protocol.MailClientConfig;
import de.metis.mail.protocol.SmtpMailClient;
import de.metis.mail.model.MailMessage;

/**
 * M4-007: Versand einer fertigen Mail ueber SMTP des gewaehlten Kontos.
 *
 * <p>Kapselt die Umwandlung des UI-Formulars in ein {@link MailMessage} und den
 * Aufruf von {@link SmtpMailClient}. Blockierendes Netzwerk-IO — Aufrufer muss
 * dies in einem SwingWorker ausfuehren (kein IO auf der EDT).
 *
 * <p>Fehlt eine SMTP-Konfiguration, faellt die Klasse auf "nur lokal speichern"
 * zurueck ({@link SendResult#localOnly()}), damit der Gesendet-Ordner-Komfort
 * erhalten bleibt.
 */
public final class MailSender {

    /** Ergebnis eines Versands. */
    public record SendResult(boolean sentViaSmtp, String error) {
        public boolean ok() { return error == null; }
        public static SendResult smtp() { return new SendResult(true, null); }
        public static SendResult localOnly() { return new SendResult(false, null); }
        public static SendResult failure(String error) { return new SendResult(false, error); }
    }

    /** M4-007: Anhang, wie ihn die UI uebergibt (Name, MIME-Typ, Bytes). */
    public record OutgoingAttachment(String fileName, String mimeType, byte[] data) { }

    private final MailStore store;
    private final String accountName;

    public MailSender(MailStore store, String accountName) {
        this.store = store;
        this.accountName = accountName;
    }

    /**
     * Baut die Mail aus den Formularfeldern und versendet sie.
     *
     * @param fromAddr Absenderadresse (E-Mail des Kontos)
     * @param to       Empfaenger (ein oder mehrere, kommagetrennt)
     * @param subject  Betreff
     * @param body     Textkoerper
     * @param atts     Anhaenge (darf leer sein)
     */
    public SendResult send(String fromAddr, String to, String subject, String body,
                           java.util.List<OutgoingAttachment> atts) {
        MailMessage.Builder b = MailMessage.builder();
        if (fromAddr != null && !fromAddr.isBlank()) {
            b.from("", fromAddr);
        }
        for (String addr : to.split("[,;]")) {
            String a = addr.trim();
            if (!a.isEmpty()) b.to("", a);
        }
        b.subject(subject == null ? "" : subject)
                .sentDate(java.time.Instant.now())
                .addHeader("Message-ID", "<" + java.util.UUID.randomUUID() + "@mailclient.local>")
                .plainTextBody(body)
                .setUnseen(false);
        if (atts != null) {
            for (OutgoingAttachment att : atts) {
                b.addAttachment(att.fileName(), att.mimeType(), att.data().length, att.data());
            }
        }
        MailMessage msg = b.build();

        MailStore.StoredAccount acc = smtpAccount();
        if (acc == null) {
            // Kein SMTP hinterlegt: nur lokal ablegen (Verhalten vor M4-007).
            return SendResult.localOnly();
        }
        try (SmtpMailClient client = new SmtpMailClient(new MailClientConfig(
                acc.smtpHost(), acc.smtpPort(), acc.username(), acc.password(),
                securityOf(acc.smtpSecurity())))) {
            client.connect();
            client.send(msg);
            return SendResult.smtp();
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return SendResult.failure(m);
        }
    }

    /** Konto mit vollstaendigen SMTP-Angaben, sonst null. */
    private MailStore.StoredAccount smtpAccount() {
        try {
            for (MailStore.StoredAccount a : store.listAccounts()) {
                if (accountName != null && !a.name().equals(accountName)) continue;
                if (a.smtpHost() != null && !a.smtpHost().isBlank()
                        && a.smtpPort() > 0
                        && a.username() != null && !a.username().isBlank()
                        && a.password() != null && !a.password().isBlank()) {
                    return a;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    static MailClientConfig.Security securityOf(String name) {
        if (name == null || name.isBlank()) return MailClientConfig.Security.SSL;
        try {
            return MailClientConfig.Security.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MailClientConfig.Security.SSL;
        }
    }
}
