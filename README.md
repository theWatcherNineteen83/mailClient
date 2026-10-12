# mailClient — Modular Java Mail Client

Modularer E-Mail-Client in Java (17+) mit Sicherheits- und Spam-Filter-Modulen.
Entstanden aus einem Brainwriting-Konzept, erste Module von Metis generiert,
Bugfixes, Tests und Build-Struktur von Prometheus (OpenClaw Agent).

## Module (Stand: M0 Core Foundation)

| Paket | Klasse | Zweck |
|---|---|---|
| `de.metis.mail.model` | `MailAddress` | RFC-5322-Adress-Parsing, Validierung, Formatierung |
| `de.metis.mail.model` | `MailMessage` | Immutable Mail-Modell mit Builder (Headers, Body, Attachments, Flags) |
| `de.metis.mail.security` | `HtmlSanitizer` | HTML-Whitelist-Sanitizer (Tag-Rewriter, Attribut-/URL-Scheme-Filter) |
| `de.metis.mail.security` | `AttachmentPolicy` | Anhang-Blockliste, Doppelendungs-Erkennung, SHA-256 |
| `de.metis.mail.security` | `AuthenticationResultsParser` | SPF/DKIM/DMARC aus Authentication-Results-Headern |
| `de.metis.mail.spam` | `SpamHeuristicScorer` | Lokale Spam-Heuristik (0–100) |
| `de.metis.mail.storage` | `MboxSplitter` | mbox → einzelne EML-Nachrichten |
| `de.metis.mail.module` | `ClamdClient` | ClamAV TCP-Client (PING/VERSION/INSTREAM) |

## Build

```bash
gradle build    # kompilieren + 44 JUnit-5-Tests
gradle test     # nur Tests
```

Voraussetzung: JDK 17+ (getestet mit JDK 25, Gradle 9.5).

## Projektstruktur

```
├── settings.gradle          # Multi-Project: root + core
├── build.gradle             # Shared Config (Java 17, JUnit Platform)
├── core/                    # Core-Modul (M0)
│   ├── build.gradle
│   └── src/{main,test}/java/de/metis/mail/...
├── .specify/                # spec-kit: constitution, spec, plan, tasks
└── brainwriting_java_mail_client.md   # Ursprüngliches Konzept
```

## Roadmap

- **M0 (fertig):** Core Foundation — Model, Security, Spam, Storage
- **M1:** IMAP/SMTP via Jakarta Mail
- **M2:** Security-Tests gegen bekannte Angriffe
- **M3:** SQLite-Storage, Sync, Threading, 100K+ Mails
- **M4:** JavaFX Desktop-UI
- **M5:** Android-UI

Details: [.specify/tasks.md](.specify/tasks.md)

## Lizenz

Apache License 2.0 (siehe LICENSE)
