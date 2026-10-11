# Implementation Tasks

**Project:** Modular Java Mail Client (`de.metis.mail`)
**Author:** Prometheus (OpenClaw Agent)
**Date:** 11.10.2026
**Status:** Draft

## Task Breakdown

### Phase 1: Core Foundation (M0)

| Task | Description | Status | Owner |
|---|---|---|---|
| M0-001 | Create Gradle multi-project repository structure | ✅ DONE | Prometheus |
| M0-002 | Implement MailAddress (RFC 5322 parsing, display, validation) | ✅ DONE | Metis |
| M0-003 | Implement MailMessage data model (From/To/Subject/Date/Body) | ✅ DONE | Metis |
| M0-004 | Implement HTML sanitizer (whitelist, no exceptions) | 🔴 BUG | Metis |
| M0-005 | Implement attachment policy (type blocking, size limits) | ✅ DONE | Metis |
| M0-006 | Write unit tests for M0 modules | ⏳ PENDING | Prometheus |

### Phase 2: Protocols (M1)

| Task | Description | Status | Owner |
|---|---|---|---|
| M1-001 | Implement IMAP protocol support via Jakarta Mail | ⏳ PENDING | Prometheus |
| M1-002 | Implement SMTP protocol support via Jakarta Mail | ⏳ PENDING | Prometheus |
| M1-003 | Implement basic authentication (username/password) | ⏳ PENDING | Prometheus |
| M1-004 | Test against real IMAP/SMTP servers | ⏳ PENDING | Prometheus |

### Phase 3: Security & Spam (M2)

| Task | Description | Status | Owner |
|---|---|---|---|
| M2-001 | Implement SPF/DKIM/DMARC header analysis | 🔴 BUG | Metis |
| M2-002 | Implement local spam heuristic scoring | 🔴 BUG | Metis |
| M2-003 | Implement ClamAV TCP client | 🔴 BUG | Metis |
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
| BUG-001 | HtmlSanitizer | `IndexOutOfBoundsException: No group 6` in `processAttributes(HtmlSanitizer.java:204)` — Regex-Gruppenreferenz fehlerhaft | 🔴 KNOWN |
| BUG-002 | ClamdClient | Implements fictional protocol; expects `"PONG: ClamAV"` and `"STREAM: INCR"` — real clamd says `"PONG"` / `"STREAM: OK"` | 🔴 KNOWN |
| BUG-003 | MboxSplitter | `>From ` escaping not working — content after split doesn't contain "From hier" | 🔴 KNOWN |
| BUG-004 | AuthenticationResultsParser | Status parsing correct (PASS/FAIL), but `domain` field always null | 🔴 KNOWN |
| BUG-005 | SpamHeuristicScorer | Fires too late: test spam email scores only 25/100 (expected >60) | 🔴 KNOWN |

## Next Actions

1. Fix all 5 Metis bugs (either manually or re-feed corrected acceptance tests to Metis)
2. Write comprehensive unit tests for all M0 modules
3. Begin M1 protocol implementation