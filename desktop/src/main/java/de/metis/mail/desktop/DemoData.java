package de.metis.mail.desktop;

import de.metis.mail.db.MailStore;
import de.metis.mail.db.StoredMessage;
import de.metis.mail.db.SyncItem;
import de.metis.mail.model.MailMessage;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Demo-Bestand für die erste UI-Abstimmung: befüllt eine SQLite-DB
 * mit einem Konto, drei Ordnern und realistischen Mails.
 * Später von echtem IMAP/POP3-Sync (MailSyncService) ersetzt.
 */
public final class DemoData {

    private DemoData() { }

    public static long[] seed(MailStore store) throws SQLException {
        long acc = store.createAccount("Georg Privat", "imap", "imap.strato.de", 993,
                "smtp.strato.de", 465, "g_seher@sumone.eu");
        long inbox = store.getOrCreateFolder(acc, "Posteingang");
        long sent = store.getOrCreateFolder(acc, "Gesendet");
        long arch = store.getOrCreateFolder(acc, "Archiv");

        Instant now = Instant.now();

        store.upsertMessages(inbox, List.of(
                item(msg("<msg-100@work>", "IT-Abteilung", "it@firma.example",
                        "Urlaubsvertretung ab Montag",
                        "Hallo Georg,\nab Montag bist du als Urlaubsvertretung eingetragen. Details im Anhang.",
                        null, now.minus(Duration.ofHours(3)), 12), 15),
                item(msg("<msg-101@news>", "Technik Newsletter", "no-reply@technik.example",
                        "WOCHENANGEBOT: 90% RABATT nur heute!!!",
                        "Klicken Sie JETZT — nur für kurze Zeit!",
                        "<html><body style='font-family:sans-serif'><h2>MEGA DEAL 90%!!!</h2>"
                                + "<p>Nur heute: <b>Kostenlose</b> Software, <a href='http://spam.example'>hier klicken</a>!"
                                + "<script>alert('boese')</script></p></body></html>",
                        now.minus(Duration.ofHours(7)), 88), 74),
                item(msg("<msg-102@list>", "Rennrad-Forum", "digest@rennrad.example",
                        "[Rennrad] Neue Bremsscheiben 180mm Erfahrungen?",
                        "Im Thread diskutieren gerade 14 Teilnehmer über Scheibenbremsen...",
                        "<html><body><p>Hallo zusammen,</p><p>hat jemand Erfahrungen mit <b>180mm-Scheiben</b>"
                                + " am Heck? <img src='cid:pic1' alt='Bremse'></p><hr><p>— Forum-Team</p></body></html>",
                        now.minus(Duration.ofDays(1)), 34), 8),
                item(msg("<msg-103@bank>", "Sparkasse Coburg", "service@sparkasse.example",
                        "Ihre Kontoauszung November liegt bereit",
                        "Der monatliche Kontoauszug ist im Online-Banking verfügbar.",
                        null, now.minus(Duration.ofDays(2)), 40), 5),
                item(msg("<msg-104@family>", "Maria", "maria@suumone.example",
                        "Kaffee am Wochenende?",
                        "Lust auf Kaffee in Coburg? Samstagvormittag wäre bei mir easy.",
                        null, now.minus(Duration.ofDays(3)), 8), 3)));

        store.upsertMessages(sent, List.of(
                item(msg("<msg-200@kali>", "Georg Seher", "g_seher@sumone.eu",
                        "Re: dimmtag Meeting verschoben",
                        "Passt mir, dann eben Donnerstag 14 Uhr.",
                        null, now.minus(Duration.ofDays(1)), 60), 0)));

        store.upsertMessages(arch, List.of(
                item(msg("<msg-300@archive>", "GitHub", "noreply@github.example",
                        "[metis-build] Build #342 passed",
                        "The push e7f12ab passed: 131 tests, 0 failures.",
                        null, now.minus(Duration.ofDays(4)), 3), 0),
                item(msg("<msg-301@archive>", "Strom Monitor", "alert@strom.example",
                        "Nachtverbrauch OK — alles ruhig",
                        "Verbrauch 0.4 kWh, keine Auffälligkeiten.",
                        null, now.minus(Duration.ofDays(5)), 2), 0)));

        return new long[]{acc, inbox, sent, arch};
    }

    private static MailMessage msg(String id, String name, String addr, String subject,
                                   String text, String html, Instant date, int unseenAfterDays) {
        MailMessage.Builder b = MailMessage.builder()
                .from(name, addr)
                .to("Georg", "g_seher@sumone.eu")
                .subject(subject)
                .sentDate(date)
                .addHeader("Message-ID", id)
                .plainTextBody(text);
        if (html != null) b.htmlBody(html);
        if (unseenAfterDays < 20) b.setUnseen(true);
        if (subject.startsWith("Kaffee")) b.setFlagged(true);
        return b.build();
    }

    private static SyncItem item(MailMessage m, int spamScore) {
        return new SyncItem(m, spamScore);
    }
}
