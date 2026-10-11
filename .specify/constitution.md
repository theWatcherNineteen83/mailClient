# MailClient Constitution

**Project:** Modular Java Mail Client (`de.metis.mail`)
**Author:** Prometheus (OpenClaw Agent)
**Date:** 11.10.2026

## Guiding Principles

### 1. Modular Architecture
The client consists of an independent headless core and interchangeable UI shells (JavaFX desktop, android.widget mobile). Each capability is a separate module loaded via Java SPI.

### 2. Pure Java Core
The core must never import `android.*`, JavaFX, or Swing classes. It runs under JDK 17 LTS with Android desugaring as an option.

### 3. Offline-First
SQLite (Xerial JDBC) as the persistent data store. Same database file on both desktop and mobile. Local spam scoring, local sanitization.

### 4. Security Through Defense in Depth
- IMAP/SMTP over TLS with certificate verification
- SPF/DKIM/DMARC header analysis as security layers (not spam signals)
- Local heuristic spam scoring
- Optional ClamAV integration for attachment virus scanning
- HTML sanitizer with strict whitelist

### 5. Loose Coupling for GPL Components
ClamAV and libpst (GPL-licensed) communicate via TCP/CLI, not as bundled libraries. Apache-2.0 license for our code; no GPL contamination.

### 6. User Control
No forced online connectivity. No vendor lock-in. User configures accounts, decides which modules to activate.

### 7. Quality Gates
Every module must pass: compilation check, machine tests, LLM judging, then manual review on the Kanban board before marking complete.

## Tech Stack

| Layer | Technology |
|---|---|
| Core language | Java 17 LTS |
| IMAP/SMTP | Jakarta Mail 2.0.1 (Apache-2.0) |
| Storage | SQLite via Xerial JDBC (Apache-2.0) |
| Crypto | Bouncy Castle (BSL-1.1) |
| UI Desktop | JavaFX (GPL-2.0) |
| UI Mobile | android.widget (Apache-2.0) |
| Build | Gradle multi-project |
| Testing | JUnit 5, Robolectric, Testcontainers |

## Module Structure

```
mailclient/
├── core/                  # Protocol-independent data models, constants
├── core/html-sanitize/    # HTML sanitizer (whitelist-based)
├── core/security/         # SPF/DKIM/DMARC analysis
├── core/model/            # MailMessage, MailAddress data classes
├── protocols/jakarta-mail-imap/  # IMAP implementation via Jakarta Mail
├── protocols/jakarta-mail-smtp/  # SMTP implementation via Jakarta Mail
├── modules/spam-local/    # Heuristic spam scoring
├── modules/spam-clamav/   # ClamAV TCP client for attachment scanning
├── modules/attachment/    # MIME part handling, policy
├── modules/export-eml-mbox/ # Export to .eml and .mbox formats
├── shell-desktop-javafx/  # JavaFX UI shell
└── shell-android/         # Android UI shell
```

## Process Rules

1. Brainwriting phase: capture all requirements and design decisions in a single document.
2. Feed completed modules to Metis AGI for code generation with explicit package names and acceptance tests.
3. Compile check with `javac --release 17`, then functional smoke tests.
4. LLM-judge evaluation on each module.
5. Manual review on Kanban board before marking FERTIG.
6. Push to GitHub after all verification steps pass.