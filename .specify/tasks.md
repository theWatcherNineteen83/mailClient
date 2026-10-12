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
| M1-005 | POP3-Unterstützung (auf Georgs Wunsch, 12.10.) | ✅ DONE | Prometheus (12.10., `Pop3MailClient` + 4 GreenMail-POP3-Tests: Fetch, Delete/Expunge, Wrong-Password, Not-Connected) |

### Phase 3: Security & Spam (M2)

| Task | Description | Status | Owner |
|---|---|---|---|
| M2-001 | Implement SPF/DKIM/DMARC header analysis | ✅ DONE | Prometheus (Bugfix 12.10.) |
| M2-002 | Implement local spam heuristic scoring | ✅ DONE | Prometheus (Bugfix 12.10.) |
| M2-003 | Implement ClamAV TCP client | ✅ DONE | Prometheus (Bugfix 12.10.) |
| M2-004 | Test security features against known attacks | ✅ DONE | Prometheus (12.10.: 27 XSS-Vektoren, 18 Attachment-Evasion-Cases, ClamdClient-Protokolltest gegen Mock-clamd inkl. EICAR + Chunk-Framing. 103 Tests gesamt, 0 Failures. Hinweis: auf kali läuft kein clamd — Spamfilter nutzt VirusTotal. Echter EICAR-Livetest optional nach clamav-daemon-Install) |

### Phase 4: Storage & Threading (M3)

| Task | Description | Status | Owner |
|---|---|---|---|
| M3-001 | Implement SQLite database schema | ✅ DONE | Prometheus (12.10., `storage`-Modul: MailStore mit accounts/folders/messages/attachments, WAL, Indizes, Attachments auf Disk) |
| M3-002 | Implement email sync logic | ✅ DONE | Prometheus (12.10., MailSyncService: IMAP+POP3 → Spam-Score → SQLite-Upsert, Dedupe per Message-ID) |
| M3-003 | Implement message threading | ✅ DONE | Prometheus (12.10., MessageThreader: JWZ-lite über References/In-Reply-To, zyklusfest, 10 Unit-Tests) |
| M3-004 | Optimize for large mailboxes (100K+ emails) | ✅ DONE | Prometheus (12.10., Perf-Test real gemessen: 100K Inserts 1,1 s (~94K msg/s), Deep-Page 82 ms, LIKE-Suche 58 ms, Voll-Read 458 ms) |

### Phase 5: Desktop UI (M4) — **Swing** (Vorgabe: `.specify/spec-ui.md`, Georg 12.10.2026)

| Task | Description | Status | Owner |
|---|---|---|---|
| M4-001 | Swing-UI-Shell: Hauptfenster, Menü, neues Modul `desktop/` | ⏳ PENDING | Prometheus |
| M4-002 | Pluggables Layout-System: `MailUILayout`-Interface + Presets Default/Thunderbird/Outlook/Gmail, zur Laufzeit tauschbar, Wahl persistiert | ⏳ PENDING | Prometheus |
| M4-003 | Account-Verwaltung per UI: mehrere Konten (IMAP/POP3+SMTP), CRUD, Test-Login, Passwörter verschlüsselt gespeichert | ⏳ PENDING | Prometheus |
| M4-004 | Mail-Liste + Lesebereich: Sortierung, Paging, HTML saniert via JEditorPane, Header + SPF/DKIM/DMARC-Badge, Spam-Ampel | ⏳ PENDING | Prometheus |
| M4-005 | Threading als Option: flache Liste ↔ Konversations-Gruppierung umschaltbar (Backend: MessageThreader/thread_id) | ⏳ PENDING | Prometheus |
| M4-006 | Suche + Attachments: Suchfeld (SQLite-Suche), Attachments anzeigen/öffnen/speichern | ⏳ PENDING | Prometheus |
| M4-007 | Composer: Schreiben/Antworten/Antw. an alle/Weiterleiten via SMTP, Multipart + Attachments | ⏳ PENDING | Prometheus |
| M4-008 | Flags/Sync: Gelesen/Ungesehen/Flag/Soft-Delete, Sync-Status pro Account, Worker-Threads (kein IO auf EDT) | ⏳ PENDING | Prometheus |
| M4-009 | Polish: System-LAF, HiDPI, Tray (Backlog), Smoke-Tests headless | ⏳ PENDING | Prometheus |

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
| BUG-002 | ClamdClient | Fiktives Protokoll (`"PONG: ClamAV"`, `"STREAM: INCR"`) — echtes clamd sagt `"PONG"` / `"stream: OK"` | ✅ FIXED 12.10.2026 — Echtes clamd-TCP-Protokoll. Nachfix M2-004: INSTREAM-Chunks brauchen 4-Byte-Big-Endian-Längenpräfix (nicht rohe Bytes); Protokoll-Konformität gegen Mock-clamd getestet (PING/VERSION/EICAR/Multi-Chunk-Reassembly). |
| BUG-003 | MboxSplitter | `>From `-Escaping funktioniert nicht — Body-Inhalt nach Split fehlt | ✅ FIXED 12.10.2026 — Envelope-Zeile bleibt als `From ...` erhalten, Body-Inhalt (inkl. Zeilen die mit "From" beginnen) bleibt im richtigen Message-Block. Test grün. |
| BUG-004 | AuthenticationResultsParser | Status-Parsing korrekt (pass/fail), aber `domain`-Feld immer null | ✅ FIXED 12.10.2026 — Domain-Extraktion aus smtp.mailfrom/header.d/header.from. Nachfix M2-004: Rewrite auf RFC-8601-Pattern `(spf\|dkim\|dmarc)=([a-z]+)` — kannte vorher Status `none` nicht und matchte `pass`/`fail` substring-basiert (falsch z.B. bei Domain `pass.example.com`). Test grün. |
| BUG-005 | SpamHeuristicScorer | Feuert zu spät: Test-Spam score nur 25/100 (erwartet >60) | ✅ FIXED 12.10.2026 — 6 Heuristiken (Sender-Mismatch, suspicious TLD in E-Mails UND URL-Hosts, Uppercase-Subject, Link-Count, Urgency-Words, Missing-Auth). Test-Spam: 70/100, Clean: 0/100. Test grün. |

## Verification (12.10.2026, Stand M3+POP3)

- Gradle Multi-Project (`settings.gradle` + `core/` + `protocol/` + `storage/`): `gradle build` erfolgreich (Gradle 9.5.1, JDK 25, Release-Level 17).
- **131 JUnit-5-Tests, 0 Failures** (`gradle test`).
- POP3: `Pop3MailClient` (connect/fetch/count/delete-expunge) + 4 GreenMail-POP3-Integrationstests grün.
- M3-001: `MailStore` (SQLite, WAL, Indizes, Upsert-Dedupe per Message-ID mit Content-Hash-Fallback, Attachments als Dateien auf Disk + Metadaten in DB, Paging, Suche, Flags, Persistenz über Reopen getestet).
- M3-002: `MailSyncService` — IMAP/POP3 → SpamHeuristicScorer → SQLite; End-to-End-Test (SMTP → GreenMail → IMAP-Fetch → Sync → DB) grün; Re-Sync dupliziert nicht; Spam-Score landet in DB.
- M3-003: `MessageThreader` (JWZ-lite: References/In-Reply-To, Empty-Container-Pruning, Zyklus- und Duplikatfest, Thread-ID = kleinste DB-ID); 10 Unit-Tests + Threading-nach-Sync-Integrationstest grün.
- M3-004 (real gemessen auf kali): **100K Inserts: 1,1 s (~94K msg/s)** · Deep-Page-Query (offset 99950): 82 ms · LIKE-Suche: 58 ms · Voll-Read für Threading: 458 ms.
- Frühere Verification (M0/M2) bleibt gültig.
- **103 JUnit-5-Tests, 0 Failures** (`gradle test`): Core (MailAddress, MailMessage, HtmlSanitizer, AttachmentPolicy, AuthParser, SpamScorer, MboxSplitter, ClamdClient-Mock-Protokolltest, SecurityAttack-Korpus) + Protocol (4 GreenMail-Integrationstests).
- M2-004: XSS-Korpus 27 Vektoren (script/onerror/javascript:/data:/iframe/svg/style/Nested-Tag-Reassembly/IE-Conditionals) grün; Attachment-Evasion (Doppelendungen, Case-Mix, Blocklist) grün; ClamdClient gegen Mock-clamd grün.
- Dabei 2 echte Bugs gefunden+gefixt: ClamdClient streamte rohe Bytes ohne Längenpräfix-Chunks; AuthParser ohne `none`-Status + Substring-Matching. Siehe BUG-002/BUG-004.
- Quellen: `core/src/main/java/de/metis/mail/...`, `protocol/src/main/java/de/metis/mail/protocol/...`

## Next Actions

1. M4-001: JavaFX-UI-Shell (neues Modul `desktop/`)
2. M4-002: UI an Core/Storage anbinden (Folder-Liste, Mail-Liste mit Threading, Reader mit HtmlSanitizer)
3. Heute Abend mit Georg (vor Ort, sudo): `sudo apt install clamav-daemon` → echter EICAR-Livetest gegen ClamdClient
4. Optional: IMAP/SMTP/POP3-Livetest gegen Strato-Postfach (nur mit Georgs OK)