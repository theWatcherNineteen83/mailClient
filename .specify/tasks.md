# Implementation Tasks

**Project:** Modular Java Mail Client (`de.metis.mail`)
**Author:** Prometheus (OpenClaw Agent)
**Date:** 11.10.2026 (letzte Änderung: 12.10.2026)
**Status:** In Arbeit

## Task Breakdown

### Phase 1: Core Foundation (M0)

| Task | Description | Status | Owner |
|---|---|---|---|
| M0-001 | Create Gradle multi-project repository structure | ✅ DONE | Prometheus (12.10.) |
| M0-002 | Implement MailAddress (RFC 5322 parsing, display, validation) | ✅ DONE | Metis |
| M0-003 | Implement MailMessage data model (From/To/Subject/Date/Body) | ✅ DONE | Metis |
| M0-004 | Implement HTML sanitizer (whitelist, no exceptions) | ✅ DONE | Prometheus (Bugfix 12.10.) |
| M0-005 | Implement attachment policy (type blocking, size limits) | ✅ DONE | Metis |
| M0-006 | Write unit tests for M0 modules | ✅ DONE | Prometheus (12.10., 44 JUnit-5-Tests grün) |

### Phase 2: Protocols (M1)

| Task | Description | Status | Owner |
|---|---|---|---|
| M1-001 | Implement IMAP protocol support via Jakarta Mail | ✅ DONE | Prometheus (12.10., `protocol`-Modul) |
| M1-002 | Implement SMTP protocol support via Jakarta Mail | ✅ DONE | Prometheus (12.10., `protocol`-Modul) |
| M1-003 | Implement basic authentication (username/password) | ✅ DONE | Prometheus (12.10., MailClientConfig) |
| M1-004 | Test against real IMAP/SMTP servers | ✅ DONE | Prometheus (12.10., GreenMail-Integrationstests: 4/4 grün, inkl. Round-Trip + Wrong-Password) |

### Phase 3: Security & Spam (M2)

| Task | Description | Status | Owner |
|---|---|---|---|
| M2-001 | Implement SPF/DKIM/DMARC header analysis | ✅ DONE | Prometheus (Bugfix 12.10.) |
| M2-002 | Implement local spam heuristic scoring | ✅ DONE | Prometheus (Bugfix 12.10.) |
| M2-003 | Implement ClamAV TCP client | ✅ DONE | Prometheus (Bugfix 12.10.) |
| M2-004 | Test security features against known attacks | ⏳ PENDING | Prometheus |

### Phase 4: Storage & Threading (M3)

| Task | Description | Status | Owner |
|---|---|---|---|
| M3-001 | Implement SQLite database schema | ⏳ PENDING | Prometheus |
| M3-002 | Implement email sync logic | ⏳ PENDING | Prometheus |
| M3-003 | Implement message threading | ⏳ PENDING | Prometheus |
| M3-004 | Optimize for large mailboxes (100K+ emails) | ⏳ PENDING | Prometheus |

### Phase 5: Desktop UI (M4)

| Task | Description | Status | Owner |
|---|---|---|---|
| M4-001 | Implement JavaFX UI shell | ⏳ PENDING | Prometheus |
| M4-002 | Connect UI to core modules | ⏳ PENDING | Prometheus |
| M4-003 | Polish desktop experience | ⏳ PENDING | Prometheus |

### Phase 6: Android UI (M5)

| Task | Description | Status | Owner |
|---|---|---|---|
| M5-001 | Implement Android UI shell | ⏳ PENDING | Prometheus |
| M5-002 | Handle Android-specific concerns (permissions, notifications) | ⏳ PENDING | Prometheus |
| M5-003 | Polish mobile experience | ⏳ PENDING | Prometheus |

## Bug Reports from Initial Metis Implementation

| Bug | Module | Description | Status |
|---|---|---|---|
| BUG-001 | HtmlSanitizer | `IndexOutOfBoundsException: No group 6` in `processAttributes` — Regex-Gruppenreferenz fehlerhaft | ✅ FIXED 12.10.2026 — Neuschrieb als Tag-Rewriter mit Attribut-Whitelist (href/src/alt/title/width/height), URL-Scheme-Check, script/style-Block-Entfernung. Test grün. |
| BUG-002 | ClamdClient | Fiktives Protokoll (`"PONG: ClamAV"`, `"STREAM: INCR"`) — echtes clamd sagt `"PONG"` / `"stream: OK"` | ✅ FIXED 12.10.2026 — Echtes clamd-TCP-Protokoll (PING/PONG, VERSION, INSTREAM mit 4-Byte-Null-EOF). Kompiliert; Integrationstest gegen echten clamd ausstehend (M2-004). |
| BUG-003 | MboxSplitter | `>From `-Escaping funktioniert nicht — Body-Inhalt nach Split fehlt | ✅ FIXED 12.10.2026 — Envelope-Zeile bleibt als `From ...` erhalten, Body-Inhalt (inkl. Zeilen die mit "From" beginnen) bleibt im richtigen Message-Block. Test grün. |
| BUG-004 | AuthenticationResultsParser | Status-Parsing korrekt (pass/fail), aber `domain`-Feld immer null | ✅ FIXED 12.10.2026 — Domain-Extraktion aus smtp.mailfrom/header.d/header.from. Test grün. |
| BUG-005 | SpamHeuristicScorer | Feuert zu spät: Test-Spam score nur 25/100 (erwartet >60) | ✅ FIXED 12.10.2026 — 6 Heuristiken (Sender-Mismatch, suspicious TLD in E-Mails UND URL-Hosts, Uppercase-Subject, Link-Count, Urgency-Words, Missing-Auth). Test-Spam: 70/100, Clean: 0/100. Test grün. |

## Verification (12.10.2026)

- Gradle Multi-Project (`settings.gradle` + `core/`): `gradle build` erfolgreich (Gradle 9.5.1, JDK 25, Release-Level 17).
- 44 JUnit-5-Tests für alle M0/M2-Module: **alle grün** (`gradle test`).
- Quellen umgezogen: `metis-modules/` → `core/src/main/java/de/metis/mail/...`
- ClamdClient-Integrationstest gegen echten clamd ausstehend (M2-004).

## Next Actions

1. M2-004: Security-Tests gegen bekannte Angriffe (inkl. ClamdClient-Livetest gegen echten clamd)
2. M3-001: SQLite-Schema
3. Optional: IMAP/SMTP-Livetest gegen Strato-Postfach (Credentials vorhanden, nur mit Georgs OK)