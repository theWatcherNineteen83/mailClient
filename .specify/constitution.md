# MailClient Constitution

**Project:** Modular Java Mail Client (`de.metis.mail`)
**Author:** Prometheus (OpenClaw Agent)
**Date:** 11.10.2026 (aktualisiert 13.10.2026)

## Guiding Principles

### 1. Modular Architecture
The client consists of an independent headless core and interchangeable UI shells
(Swing desktop, android.widget mobile). Each capability is a separate module.

### 2. Pure Java Core
The core must never import `android.*`, `javax.swing.*` or `java.awt.*` classes.
It runs under JDK 17 LTS (`javac --release 17`), Android desugaring as an option.

### 3. Offline-First
SQLite (Xerial JDBC) as the persistent data store. Local spam scoring, local
sanitization, no forced online connectivity.

### 4. Security Through Defense in Depth
- IMAP/SMTP over TLS with certificate verification
- SPF/DKIM/DMARC header analysis as security layers (not spam signals), stored per mail and
  surfaced as a badge (GREEN/YELLOW/RED/UNKNOWN)
- Local heuristic spam scoring
- ClamAV integration for attachment virus scanning (verifiziert per EICAR-Livetest)
- HTML sanitizer with strict whitelist; browser preview additionally ships a restrictive
  Content-Security-Policy (`default-src 'none'`) so no remote content is fetched
- Credentials at rest encrypted (AES-256-GCM + PBKDF2, key in `keyring.key`, mode 0600);
  legacy plaintext rows stay readable for migration
- SQL always parameterized (no string concatenation of user input)

### 5. Loose Coupling for GPL Components
ClamAV and libpst (GPL-licensed) communicate via TCP/CLI, not as bundled libraries.
Apache-2.0 license for our code; no GPL contamination.

### 6. User Control
No forced online connectivity. No vendor lock-in. User configures accounts, storage location
(portable next to the jar or in the OS user profile) and decides which modules to activate.

### 7. Quality Gates
Every module must pass: compilation check, machine tests, LLM judging, then manual review on
the Kanban board before marking complete.

### 8. Working Software
The repository is always buildable and testable (`gradle build`), with a runnable fat jar
(`gradle :desktop:fatJar`) and automated CI on every push to `main`.

## Tech Stack

| Layer | Technology |
|---|---|
| Core language | Java 17 (source/target), entwickelt mit JDK 25 |
| IMAP/POP3/SMTP | Jakarta Mail (Apache-2.0) |
| Storage | SQLite via Xerial JDBC (Apache-2.0), Schema v3 |
| Crypto | JDK-Bordmittel (`javax.crypto`, AES-256-GCM + PBKDF2) — keine Fremdbibliothek nötig |
| UI Desktop | **Swing** (JDK-Bordmittel; Georgs Vorgabe 12.10.2026, siehe `.specify/spec-ui.md`) |
| UI Mobile | android.widget (Apache-2.0), M5 |
| Build | Gradle multi-project (Wrapper 9.5.1) |
| Testing | JUnit 5, GreenMail (Integration), SQLite-Temp-DBs |
| CI/CD | GitHub Actions: Build + Tests + Fat-Jar, Release bei Tags `v*` |

## Module Structure (Ist-Stand)

```
mailclient/
├── core/                          # UI-frei: Model, Security, Spam, Storage-Utils
│   └── src/main/java/de/metis/mail/
│       ├── model/                 # MailAddress, MailMessage
│       ├── security/              # HtmlSanitizer, AttachmentPolicy, AuthenticationResultsParser,
│       │                          # SecurityBadge, MailKeyring, SecretProtector, KeyringFileProtector
│       ├── spam/                  # SpamHeuristicScorer
│       ├── storage/               # MboxSplitter, SearchQueryBuilder
│       └── module/                # ClamdClient
├── protocol/                      # ImapMailClient, Pop3MailClient, SmtpMailClient,
│                                  # MailClientConfig, MessageConverter
├── storage/                       # MailStore (Schema v3, Papierkorb, Suche), SyncItem,
│                                  # SyncService, MailSyncService, MessageThreader
└── desktop/                       # Swing-Shell: MailDesktopApp, MailFrame, AccountDialog,
                                   # ComposeDialog, AppPaths, PreviewExport, Theme, UiSettings,
                                   # LayoutPreset, I18n, DemoData + Fat-Jar-Build
```

## Process Rules

1. Brainwriting phase: capture all requirements and design decisions in a single document.
2. Feed completed modules to Metis AGI for code generation with explicit package names and acceptance tests.
3. Compile check with `javac --release 17`, then functional smoke tests.
4. LLM-judge evaluation on each module.
5. Manual review on the Kanban board before marking FERTIG.
6. Push to GitHub only after all verification steps pass.
7. Documentation (README, `.specify/*`) is part of the change, not an afterthought.
