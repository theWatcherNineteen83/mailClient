# Technical Implementation Plan

**Project:** Modular Java Mail Client (`de.metis.mail`)
**Author:** Prometheus (OpenClaw Agent)
**Date:** 11.10.2026
**Status:** Draft

## Architecture

The client follows a layered architecture with clear separation between the core engine and UI shells. The core is headless and platform-agnostic, while UI shells handle user interaction.

```
┌──────────────────────────────────────────────────────────┐
│                     UI Shells                            │
│  ┌─────────────────┐       ┌────────────────────────┐    │
│  │ JavaFX Desktop  │       │   Android UI Shell     │    │
│  │ (GPL-2.0)       │       │   (Apache-2.0)         │    │
│  └────────┬────────┘       └───────────┬────────────┘    │
└───────────┼───────────────────────────────┼──────────────┘
            │ Java SPI / ServiceLoader      │
┌───────────┼───────────────────────────────┼──────────────┐
│           ▼               Core Engine     ▼              │
│  ┌──────────────────────────────────────────────────┐   │
│  │          Core Data Models (core/)                │   │
│  │ MailMessage, MailAddress, MailFolder, etc.       │   │
│  └──────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────┐   │
│  │      HTML Sanitizer (core/html-sanitize/)        │   │
│  │ Whitelist-based HTML sanitizer, no exceptions    │   │
│  └──────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────┐   │
│  │      Security Analysis (core/security/)          │   │
│  │ SPF/DKIM/DMARC header analysis                   │   │
│  └──────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────┐   │
│  │      Protocols (protocols/)                      │   │
│  │ Jakarta Mail IMAP/SMTP implementation            │   │
│  └──────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────┐   │
│  │      Modules (modules/)                          │   │
│  │ spam-local, spam-clamav, attachment, etc.        │   │
│  └──────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────┐   │
│  │      Storage (core/storage/)                     │   │
│  │ SQLite database management                       │   │
│  └──────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

## Module Dependencies

| Module | Depends On |
|---|---|
| core | None |
| core/html-sanitize | core |
| core/security | core |
| protocols/jakarta-mail-imap | core, Jakarta Mail |
| protocols/jakarta-mail-smtp | core, Jakarta Mail |
| modules/spam-local | core |
| modules/spam-clamav | core |
| modules/attachment | core, core/security |
| modules/export-eml-mbox | core |
| shell-desktop-javafx | core, all modules |
| shell-android | core, all modules |

## Build System

**Gradle Multi-Project** with the following structure:

```
mailclient/
├── build.gradle           # Root build script
├── settings.gradle        # Project configuration
├── core/                  # Core library
│   └── build.gradle
├── protocols/
│   ├── jakarta-mail-imap/
│   │   └── build.gradle
│   └── jakarta-mail-smtp/
│       └── build.gradle
├── modules/
│   ├── spam-local/
│   │   └── build.gradle
│   ├── spam-clamav/
│   │   └── build.gradle
│   ├── attachment/
│   │   └── build.gradle
│   └── export-eml-mbox/
│       └── build.gradle
├── shell-desktop-javafx/
│   └── build.gradle
└── shell-android/
    └── build.gradle
```

## Key Design Decisions

### 1. Java 17 LTS as Baseline
- Stable, well-supported Java version
- Android desugaring handles most Java 17 features
- Compatible with all target devices

### 2. Jakarta Mail for Protocols
- Mature IMAP/SMTP implementation
- Apache-2.0 license compatible with our Apache-2.0 license
- Well-tested and stable

### 3. SQLite for Storage
- Single-file database, easy to backup and migrate
- Xerial JDBC driver provides reliable access from Java
- Efficient for large mailboxes with proper indexing

### 4. HTML Sanitization Strategy
- Whitelist-based approach (only allow known safe tags)
- Strip all attributes by default
- Remove all script tags and on* event attributes
- No exception throwing — malformed HTML gets sanitized, not crashed

### 5. Spam Filtering Strategy
- Local heuristic scoring with user-tunable thresholds
- Multiple independent checks: sender mismatch, suspicious TLD, subject analysis, link count, urgency words, missing authentication
- No cloud service — privacy preserved

### 6. ClamAV Integration
- TCP/CLI communication only (no bundled GPL code)
- Optional module — can be enabled/disabled independently
- User configures host:port in settings

## Quality Gates

Every module must pass these stages before being considered complete:

1. **Compilation:** `javac --release 17` must compile all source files without errors
2. **Unit Tests:** JUnit 5 tests must pass (minimum 80% branch coverage)
3. **Integration Tests:** End-to-end scenarios must work (e.g., IMAP connect → fetch → store)
4. **LLM Judging:** Metis AGI LLM judge must pass each module (acceptance criteria)
5. **Manual Review:** Developer manually inspects code and tests before marking FERTIG

## Rollout Plan

### Phase 1: Core Foundation (M0)
- Implement core data models (MailAddress, MailMessage)
- Implement HTML sanitizer
- Implement attachment policy
- Set up build system and repository structure
- Write initial tests

### Phase 2: Protocols (M1)
- Implement IMAP protocol support via Jakarta Mail
- Implement SMTP protocol support via Jakarta Mail
- Implement basic authentication (username/password)
- Test against real IMAP/SMTP servers

### Phase 3: Security & Spam (M2)
- Implement SPF/DKIM/DMARC header analysis
- Implement local spam heuristic scoring
- Implement ClamAV TCP client
- Test security features against known attacks

### Phase 4: Storage & Threading (M3)
- Implement SQLite database schema
- Implement email sync logic
- Implement message threading
- Optimize for large mailboxes

### Phase 5: Desktop UI (M4)
- Implement JavaFX UI shell
- Connect UI to core modules
- Polish desktop experience

### Phase 6: Android UI (M5)
- Implement Android UI shell
- Handle Android-specific concerns (permissions, notifications)
- Polish mobile experience

## Testing Strategy

### Unit Tests (JUnit 5)
- Every public method tested
- Edge cases covered (null, empty, invalid input)
- Mock dependencies for isolation

### Integration Tests (Testcontainers)
- Test against real IMAP/SMTP servers in containers
- Verify protocol compliance
- Test multi-account scenarios

### Functional Tests (Robolectric)
- Test Android UI components in isolation
- Verify event handling and state management

### Performance Tests
- Measure sync time with 100K+ emails
- Profile memory usage
- Identify bottlenecks

## Error Handling

- Failures must be caught and logged at the appropriate level
- User-facing errors must be clear and actionable
- Network errors must retry with exponential backoff
- Database errors must be wrapped in meaningful exceptions