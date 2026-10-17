# mailClient — Modular Java Mail Client

Modularer E-Mail-Client in Java (17+) mit Sicherheits- und Spam-Filter-Modulen.
Entstanden aus einem Brainwriting-Konzept: die ersten Module wurden von **Metis AGI**
generiert, Integration, Bugfixes, Tests, UI und Build kommen von **Prometheus** (OpenClaw Agent).

## Download & Start

- **Aktuelles Release:** [v0.1.5-m4](https://github.com/theWatcherNineteen83/mailClient/releases/latest)
- **Direkt:** [MailClient-0.1.0.jar](https://github.com/theWatcherNineteen83/mailClient/releases/download/v0.1.5-m4/MailClient-0.1.0.jar)
- Voraussetzung: **Java 17+** (prüfen mit `java -version`)
- Start aus der Konsole, damit Meldungen sichtbar sind: `java -jar MailClient-0.1.0.jar`
- Die SQLite-Natives für Windows/macOS/Linux liegen im Jar — keine Zusatzinstallation nötig.

### Ablageort der Daten

Reihenfolge: `-Dmailclient.data=<Pfad>` → `location.txt` im Benutzerprofil → `portable.txt`
neben dem Jar → bestehendes `~/MailClient-Daten` → OS-Standard (Windows `%APPDATA%\MailClient`,
macOS `~/Library/Application Support/MailClient`, Linux `~/.local/share/mailclient`).
Im Programm: **Datei → Datenordner…** (Portabel / Benutzerverzeichnis / eigener Ordner).
Der Start loggt den benutzten Ordner. Inhalt: `mail.sqlite`, `attachments/`, `keyring.key`.

## Module (M0–M3 fertig, M4 weitgehend live)

| Paket | Klasse | Zweck |
|---|---|---|
| `de.metis.mail.model` | `MailAddress`, `MailMessage` | RFC-5322-Adressen, immutables Mail-Modell mit Builder |
| `de.metis.mail.security` | `HtmlSanitizer` | HTML-Whitelist-Sanitizer (Tag-Rewriter, Attribut-/Scheme-Filter) |
| `de.metis.mail.security` | `AttachmentPolicy` | Anhang-Blockliste, Doppelendungs-Erkennung, SHA-256 |
| `de.metis.mail.security` | `AuthenticationResultsParser` | SPF/DKIM/DMARC aus Authentication-Results-Headern |
| `de.metis.mail.security` | `SecurityBadge` | Ampel-Logik GREEN/YELLOW/RED/UNKNOWN + Label/Tooltip |
| `de.metis.mail.security` | `MailKeyring`, `SecretProtector`, `KeyringFileProtector` | Passwort-Verschlüsselung (AES-256-GCM, PBKDF2) mit lokalem Keyring |
| `de.metis.mail.spam` | `SpamHeuristicScorer` | Lokale Spam-Heuristik (0–100) |
| `de.metis.mail.storage` | `MboxSplitter`, `SearchQueryBuilder` | mbox → EML; sichere SQL-Suchklauseln (Platzhalter, ESCAPE) |
| `de.metis.mail.module` | `ClamdClient` | ClamAV TCP-Client (PING/VERSION/INSTREAM, 4-Byte-Längenpräfix) |
| `de.metis.mail.protocol` | `ImapMailClient`, `Pop3MailClient`, `SmtpMailClient`, `MailClientConfig`, `MessageConverter` | IMAP/POP3/SMTP via Jakarta Mail |
| `de.metis.mail.db` | `MailStore`, `StoredMessage`, `SyncItem`, `SyncService` | SQLite (Schema v3: accounts/folders/messages/attachments + auth_spf/dkim/dmarc), Suche, Papierkorb, Sync |
| `de.metis.mail.sync`, `...threading` | `MailSyncService`, `MessageThreader` | Sync-Pipeline, JWZ-Threading |
| `de.metis.mail.desktop` | `MailDesktopApp`, `MailFrame`, `AccountDialog`, `ComposeDialog`, `AppPaths`, `PreviewExport`, `Theme`, `UiSettings`, `LayoutPreset`, `I18n`, `DemoData` | Swing-Shell (M4): Konten-CRUD, Sync, Papierkorb, Suche, Sicherheits-Ampel, Vorschau, Design/Layouts, DE/EN |

## Sicherheit

- **Passwörter verschlüsselt** in SQLite (Präfix `enc1:`), Schlüssel in `keyring.key` (Rechte 0600,
  beim ersten Start zufällig erzeugt). Klartext-Altbestand bleibt lesbar.
- **HTML-Sanitizer** vor jeder Anzeige (Whitelist, keine Skripte/Event-Handler). Die Vorschau im
  Systembrowser bekommt zusätzlich eine restriktive CSP (`default-src 'none'`) — damit lädt der
  Browser keine entfernten Bilder/Skripte nach (kein Tracking-Pixel).
- **SPF/DKIM/DMARC** je Mail gespeichert (Schema v3) und als Ampel im Leser sichtbar.
- **SQL-Suche** ausschließlich mit Parametern; Benutzereingaben werden nie in SQL geklebt.
- **ClamAV**: `ClamdClient` live gegen einen echten clamd verifiziert — EICAR ergibt
  `stream: Eicar-Test-Signature FOUND`, eine saubere Mail `stream: OK`.

## Build

```bash
GRADLE_OPTS="-Xmx3g" ./gradlew --no-daemon build   # kompilieren + 199 JUnit-5-Tests
gradle test       # nur Tests
gradle :desktop:fatJar   # MailClient-0.1.0.jar — ein File, Start per java -jar (JDK 17+)
```

Voraussetzung: JDK 17+ (entwickelt mit JDK 25 + Gradle 9.5; die CI baut mit Temurin 21).

**CI:** GitHub Actions führt bei jedem Push auf `main` Build, Tests und Fat-Jar aus.
Tags `v*` erzeugen automatisch ein Release mit angehängtem Jar.

## Projektstruktur

```
├── settings.gradle          # Multi-Project: root + core + protocol + storage + desktop
├── core/                    # Model, Security (Sanitizer, Badge, Keyring), Spam, Storage-Utils
├── protocol/                # IMAP, POP3, SMTP via Jakarta Mail
├── storage/                 # SQLite-Store, Sync, Threading, Papierkorb
├── desktop/                 # Swing-Shell (M4) + Fat-Jar-Build
├── .specify/                # spec-kit: constitution, spec, spec-ui, plan, tasks
├── screenshots/             # UI-Screenshots (Hell/Dunkel)
└── brainwriting_java_mail_client.md   # Ursprüngliches Konzept
```

## Performance (M3-004, real gemessen)

100.000 Mails gegen eine echte SQLite-DB (kali):

| Operation | Zeit |
|---|---|
| 100K Inserts (Batch-Upsert, WAL) | 1,1 s (~94K msg/s) |
| Deep-Page-Query (offset 99950, limit 50) | 82 ms |
| LIKE-Suche | 58 ms |
| Voll-Read aller Zeilen (für Threading) | 458 ms |

→ 150K+ Mails sind für dieses Design problemlos machbar. Attachment-Binärdaten liegen
als Dateien auf Disk, nur Metadaten in der DB (hält die DB schlank).

## Roadmap

- **M0 ✅:** Core Foundation — Model, Security, Spam, Storage
- **M1 ✅:** IMAP, POP3, SMTP via Jakarta Mail (GreenMail-Integrationstests)
- **M2 ✅:** Security-Tests gegen bekannte Angriffe (27 XSS-Vektoren, Attachment-Evasion, clamd-Protokolltest, **EICAR-Livetest gegen echtes clamd am 13.10.2026**)
- **M3 ✅:** SQLite-Storage, Sync, Threading, 100K-Perf-Test
- **M4 🟡:** Desktop-UI in **Swing**. Live: Shell, 5 Layout-Presets, Sprachumschaltung, Design-Menü,
  Farbschema Hell/Dunkel/Automatisch, Konto-Verwaltung (Anlegen mit Strato-Preset und Verbindungstest,
  **Bearbeiten/Löschen**, **Konto-Umschalter**), **echter IMAP/POP3-Sync mit Statuszeile**,
  **Papierkorb (Wiederherstellen / endgültig löschen / leeren)**, **SQL-Suche mit Feld-Präfixen**
  (`from:`, `subject:`, `has:attachment:`), **SPF/DKIM/DMARC-Ampel**, **Vorschau integriert oder im
  Systembrowser**, **wählbarer Ablageort (portabel / Benutzerprofil)**, Threading-Umschaltung,
  Spam-Prozent-Ampel, Build+Release über GitHub Actions,
  **SMTP-Versand mit Anhängen** (M4-007), **Anhang-Leiste im Leser** öffnen/speichern (M4-006)
  - *Offen:* Paging-UI, HiDPI/Tray, Flag- und Lösch-Sync zum IMAP-Server
- **M5:** Android-UI

Details: [.specify/tasks.md](.specify/tasks.md) · UI-Vorgabe: [.specify/spec-ui.md](.specify/spec-ui.md)

## Mitwirkende

- **Metis AGI** (miniedi): erste Modulgenerationen (`MailAddress`, `MailMessage`, `AttachmentPolicy`,
  `HtmlSanitizer`, `ClamdClient`, `SpamHeuristicScorer`, `MboxSplitter`, `AuthenticationResultsParser`),
  dazu `MailKeyring`, `SecurityBadge`, `SearchQueryBuilder` — jeweils compile-geprüft, danach Review,
  Bugfix und Test durch Prometheus.
- **Prometheus** (OpenClaw Agent): Architektur, Integration, Bugfixes (BUG-001…005), 199 Tests,
  Swing-UI, CI/CD, Releases, Dokumentation.

## Lizenz

Apache License 2.0 (siehe LICENSE)
