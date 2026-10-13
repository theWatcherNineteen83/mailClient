# mailClient — Modular Java Mail Client

Modularer E-Mail-Client in Java (17+) mit Sicherheits- und Spam-Filter-Modulen.
Entstanden aus einem Brainwriting-Konzept, erste Module von Metis generiert,
Bugfixes, Tests und Build-Struktur von Prometheus (OpenClaw Agent).

## Module (Stand: M0–M3 fertig, M4-Shell teilweise live)

| Paket | Klasse | Zweck |
|---|---|---|
| `de.metis.mail.model` | `MailAddress` | RFC-5322-Adress-Parsing, Validierung, Formatierung |
| `de.metis.mail.model` | `MailMessage` | Immutable Mail-Modell mit Builder (Headers, Body, Attachments, Flags) |
| `de.metis.mail.security` | `HtmlSanitizer` | HTML-Whitelist-Sanitizer (Tag-Rewriter, Attribut-/URL-Scheme-Filter) |
| `de.metis.mail.security` | `AttachmentPolicy` | Anhang-Blockliste, Doppelendungs-Erkennung, SHA-256 |
| `de.metis.mail.security` | `AuthenticationResultsParser` | SPF/DKIM/DMARC aus Authentication-Results-Headern |
| `de.metis.mail.spam` | `SpamHeuristicScorer` | Lokale Spam-Heuristik (0–100) |
| `de.metis.mail.storage` | `MboxSplitter` | mbox → einzelne EML-Nachrichten |
| `de.metis.mail.module` | `ClamdClient` | ClamAV TCP-Client (PING/VERSION/INSTREAM, 4-Byte-Längenpräfix-Chunks) |
| `de.metis.mail.protocol` | `ImapMailClient` | IMAP: connect, Ordner, Fetch → Core-Model, Flags |
| `de.metis.mail.protocol` | `Pop3MailClient` | POP3: connect, Fetch, Delete/Expunge |
| `de.metis.mail.protocol` | `SmtpMailClient` | SMTP-Versand (Multipart/Attachments) |
| `de.metis.mail.protocol` | `MailClientConfig` | Server-Konfiguration (NONE/STARTTLS/SSL + Basic Auth) |
| `de.metis.mail.protocol` | `MessageConverter` | Jakarta Mail ↔ Core-Model |
| `de.metis.mail.db` | `MailStore` | SQLite-Persistenz (WAL, Batch-Upsert, Dedupe, Paging, Suche) |
| `de.metis.mail.sync` | `MailSyncService` | IMAP/POP3 → Spam-Score → SQLite-Sync |
| `de.metis.mail.threading` | `MessageThreader` | JWZ-Threading über References/In-Reply-To |
| `de.metis.mail.desktop` | `MailDesktopApp`, `MailFrame` | Swing-Shell (M4): 5 Layout-Presets, Design-Menü plattformgerecht, Compose/Antwort/Weiterleitung (lokal), Spam-Prozent-Ampel, DE/EN |

## Build

```bash
gradle build    # kompilieren + 131 JUnit-5-Tests (inkl. GreenMail-Integration + 100K-Perf)
gradle test     # nur Tests
gradle :desktop:fatJar # MailClient-0.1.0.jar — ein File, Start per java -jar (JDK 17+)
```

Voraussetzung: JDK 17+ (getestet mit JDK 25, Gradle 9.5).

## Projektstruktur

```
├── settings.gradle          # Multi-Project: root + core + protocol + storage
├── build.gradle             # Shared Config (Java 17, JUnit Platform)
├── core/                    # Core-Modul (M0/M2): Model, Security, Spam, Storage-Utils
├── protocol/                # Protokoll-Modul (M1): IMAP, POP3, SMTP (Jakarta Mail)
├── storage/                 # Storage-Modul (M3): SQLite, Sync, Threading
├── .specify/                # spec-kit: constitution, spec, plan, tasks
└── brainwriting_java_mail_client.md   # Ursprüngliches Konzept
```

## Performance (M3-004, real gemessen)

100.000 Mails gegen echte SQLite-DB auf kali:

| Operation | Zeit |
|---|---|
| 100K Inserts (Batch-Upsert, WAL) | 1,1 s (~94K msg/s) |
| Deep-Page-Query (offset 99950, limit 50) | 82 ms |
| LIKE-Suche | 58 ms |
| Voll-Read aller Zeilen (für Threading) | 458 ms |

→ 150K+ Mails sind für dieses Design problemlos machbar. Attachment-Binärdaten
liegen als Dateien auf Disk, nur Metadaten in der DB (hält die DB schlank).

## Roadmap

- **M0 ✅:** Core Foundation — Model, Security, Spam, Storage
- **M1 ✅:** IMAP, POP3, SMTP via Jakarta Mail (GreenMail-Integrationstests)
- **M2 ✅:** Security-Tests gegen bekannte Angriffe (XSS-Korpus, Attachment-Evasion, clamd-Protokolltest)
- **M3 ✅:** SQLite-Storage, Sync, Threading, 100K-Perf-Test
- **M4 🟡:** Desktop-UI in **Swing** (Vorgabe 12.10.2026). Live: Shell, Layouts (Default/Thunderbird/Outlook/Gmail/Foxmail), Sprachumschaltung, Design-Menü (auto/GTK/Nimbus/Metal, plattformgefiltert), **Farbschema Hell/Dunkel/Automatisch (13.10.2026: verdrahtet, SQLite-persistiert, ThemeTest grün)**, Compose/Antworten/Weiterleiten lokal, Spam-Prozentanzeige, Logo-Icon, **CI-Build via GitHub Actions (seit v0.1.2-m4 grün, Release-Jar automatisch)**. Offen: echte Konto-Anbindung (M4-003), Attachment-UI, SMTP-Versand
- **M5:** Android-UI

Details: [.specify/tasks.md](.specify/tasks.md)

## Lizenz

Apache License 2.0 (siehe LICENSE)
