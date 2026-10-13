# Feature Specification: Modular Java Mail Client

**Version:** 1.0
**Author:** Prometheus (OpenClaw Agent)
**Date:** 11.10.2026
**Status:** Draft

## What and Why

Build a privacy-focused, modular email client in Java that supports both desktop (Swing; Entscheidung 12.10.2026, siehe spec-ui.md) and mobile (Android) with a shared headless core. The client prioritizes local processing (offline-first), strict security (HTML sanitization, SPF/DKIM/DMARC analysis, attachment policy), and loose coupling of optional features (ClamAV virus scanning via TCP, libpst import via CLI).

The target user is a privacy-conscious individual (1-3 email accounts) who wants a lightweight, extensible client without vendor lock-in or forced online connectivity.

## Requirements

### Core Requirements
- IMAP protocol support for fetching emails
- SMTP protocol support for sending emails
- Local storage of emails in SQLite database
- Offline access to all stored emails
- Multi-account support
- Message threading
- Search functionality
- Email composition and sending

### Security Requirements
- TLS for all network connections (IMAP over SSL/TLS, SMTP over STARTTLS)
- Certificate verification
- HTML sanitization with strict whitelist (no script, no on* attributes)
- Attachment policy engine (block dangerous types like .exe, .bat)
- SPF/DKIM/DMARC header analysis and display
- Local heuristic spam scoring (no cloud service)
- Optional ClamAV integration for attachment virus scanning (live gegen echtes clamd verifiziert, 13.10.2026)
- Passwörter verschlüsselt at rest (AES-256-GCM + PBKDF2, Keyring-Datei mit Rechten 0600)
- Papierkorb-Semantik: Löschen verschiebt in den Papierkorb, Wiederherstellen möglich, endgültiges
  Löschen (einzeln oder Papierkorb leeren) entfernt auch die Anhang-Dateien
- Browser-Vorschau mit restriktiver Content-Security-Policy (keine entfernten Inhalte, kein Tracking)
- Ablageort der Daten wählbar: portabel neben dem Jar oder im Benutzerprofil des Betriebssystems

### Performance Requirements
- Handle large mailboxes (100K+ messages) without performance degradation
- Efficient incremental syncing (only fetch changes)
- SQLite indexing for fast queries

### User Experience Requirements
- Clean, modern UI
- Keyboard shortcuts for power users
- Dark mode
- Customizable layouts

### Extensibility Requirements
- Modular architecture with Java SPI
- Optional modules can be added/removed independently
- Feature flags to toggle capabilities

## User Stories

### US-1: Email Management
As a user, I want to organize my emails into folders so that I can find them easily.
- **Acceptance:** User can create, rename, and delete mail folders

### US-2: Email Search
As a user, I want to search across all my emails by sender, subject, body, or date so that I can find specific messages.
- **Acceptance:** Search returns results in under 2 seconds for 100K+ emails

### US-3: Email Composition
As a user, I want to compose new emails with attachments so that I can communicate effectively.
- **Acceptance:** User can attach files, select recipients, and send

### US-4: Spam Filtering
As a user, I want emails to be automatically classified as spam or ham so that my inbox stays clean.
- **Acceptance:** Local heuristic scoring with user-tunable thresholds

### US-5: Security Analysis
As a user, I want to see SPF/DKIM/DMARC status on each email so that I can verify sender authenticity.
- **Acceptance:** Ampel/Badge im Lesebereich (GREEN/YELLOW/RED/UNKNOWN) inkl. Tooltip je Teilcheck — umgesetzt 13.10.2026 (Schema v3: auth_spf/auth_dkim/auth_dmarc je Mail)

### US-6: Attachment Handling
As a user, I want dangerous attachment types to be blocked or warned about so that I stay safe.
- **Acceptance:** Policy engine blocks .exe, .bat, .cmd by default with customizability

## Non-Goals
- Web login (no OAuth, no Google/Microsoft account integration)
- Business complexity (no mailing lists, shared mailboxes)
- (POP3 ist seit M1 umgesetzt und daher kein Non-Goal mehr)
- Real-time chat or push notifications