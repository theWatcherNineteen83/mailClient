# Brainwriting: Java-Mail-Client „Modular"

> **Kurzfassung:** Ein quelloffener Mail-Client in nativem Java. Ein 100 % reiner Java-Kern (Protokolle, Modell, Sync, Storage) läuft unverändert auf Windows, Linux und Android – ausgetauscht wird **nur die UI-Schale** (JavaFX für Desktop, Java-Views für Android). Optional Module (GPG, Virenscan, Suche, Export …) rasten per SPI in den Kern ein.

*Erstellt: 10.10.2026 · Stand: Durchgang 5 (11.10.2026: §11 Referenz-Clients, §12 Wiederverwendung Prometheus-Spamfilter)*

---

## 1. Feste Entscheidungen

| Frage | Entscheidung | Konsequenz |
|---|---|---|
| Sprache | **Nativ Java** (JDK 17 LTS) | UI-Schalen in Java; Compose/Kotlin nicht nötig |
| UI-Strategie | **Kern zu 100 % Java; nur das Frontend wird getauscht** | Core darf *niemals* `android.*`, JavaFX- oder Swing-Klassen importieren |
| Desktop-UI | **JavaFX** (Alternative: Swing) | Sieht besser aus; JPackage für MSIX/AppImage/deb |
| Android-UI | **Klassische `android.widget`-Views in Java** | Kein Compose, kein Kotlin; minSdk 26–28 |
| Nutzergruppe | **Privatgebrauch** (1–3 Accounts) | Kein Web-Login, keine Business-Exoten (SPF/DKIM-Komplexität bleibt aber, siehe Security) |
| Verteilung | **Open Source** | **Apache-2.0** empfohlen – kompatibel mit Bouncy Castle, Jakarta Mail, Xerial-SQLite |
| Protokolle | **IMAP + SMTP** als Kern | POP3 gestrichen (kann später als Modul zurück); JMAP = Watchlist |
| Code-Dokumentation | **Deutsch**, zusätzlich gerne **chinesische Kommentare** | Z. B. `// 说明：UIDVALIDITY 校验……` als zweite Dokumentationsschicht |
| Build | **Gradle Multi-Project**, Module als eigene Artefakte | Jedes Modul versioniert, per `ServiceLoader`/SPI einrastbar |
| Storage | **SQLite (Xerial JDBC)** | Gleiche DB-Datei auf beiden Plattformen, Offline-first |

---

## 2. Architektur

```
┌─────────────────────────────┐        ┌─────────────────────────────┐
│  ui-desktop (JavaFX)        │        │  ui-android (Java, Views)   │
└──────────────┬──────────────┘        └──────────────┬──────────────┘
               │  Events (Observer/Bus)               │
               ▼                                       ▼
        ┌─────────────────────────────────────────────────────┐
        │            CORE  (reines java.*, headless)          │
        │  model · config · storage-sqlite · sync-engine      │
        │  security (SPF/DKIM/DMARC) · html-sanitize          │
        │  backup · NotificationBus · spi                     │
        └───────────────▲─────────────────────────────────────┘
                        │  SPI (ServiceLoader)
        ┌───────────────┴─────────────────────────────────────┐
        │  PROTOCOLS: jakarta-mail (IMAP, SMTP)               │
        │  MODULES (optional): search-fts · gpg · spam-local  │
        │  notifications · export · clams-client              │
        └─────────────────────────────────────────────────────┘
```

### Die drei Regeln, die „nur die Schale tauschen" möglich machen

1. **Core kennt kein UI** – keine JavaFX-/Swing-`android.*`-Klassen. Alles über eine **Event-Bus- + SPI-API** (`MailReceived`, `SyncFinished`, `AccountError`, `NotificationRequested`).
2. **SQLite für alles Persistente** – gleiche DB auf beiden Plattformen, kein Plattform-Code.
3. **Core ist headless-testbar** – die gesamte Sync-Logik läuft in JUnit *ohne UI*; Robolectric-Tests auf Android prüfen exakt dasselbe Verhalten.

### Projekt-Layout (Gradle)

```
mail-client/
├── core/
│   ├── model               (Mail, Address, Anhang, Folder, Label)
│   ├── config              (Accounts; Passwörter via Keystore / Android-KeyStore)
│   ├── storage-sqlite      (Xerial-JDBC, Offline-first, Sync-State je Mail)
│   ├── sync-engine         (IMAP-IDLE, UIDVALIDITY, Konflikt-Regeln)
│   ├── security            (SPF/DKIM/DMARC-Verifizierung – siehe §6)
│   ├── html-sanitize       (geputztes HTML für integrierte Anzeige)
│   ├── backup              (Export/Import des Gesamt-Backups)
│   └── spi                 (Events + Modul-API; die EINZIGEN importierbaren Core-Klassen)
│
├── protocols/
│   ├── jakarta-mail-imap   (XOAUTH2, IDLE, UIDVALIDITY)
│   └── jakarta-mail-smtp
│
├── ui-desktop              (JavaFX – oder Swing als Leichtgewichts-Alternative)
├── ui-android              (Java, android.widget, minSdk 26–28)
│
└── modules/                (optional, per ServiceLoader + Feature-Flag)
    ├── search-fts          (SQLite Full-Text)
    ├── gpg                 (Bouncy Castle: signieren + verschlüsseln)
    ├── spam-local          (lokal, heuristisch – keine Cloud)
    ├── notifications       (Desktop: Tray/System-Notifier; Android: NotificationChannel)
    ├── export-eml-mbox     (inkl. Thunderbird/Outlook-Import-Export)
    └── clamd-client        (Virenscan – siehe §6)
```

**Kein Modul erzwungen** – jedes ist per Feature-Flag und Abhängigkeit zuschaltbar.

---

## 3. Backup & Migration

**Prinzip:** Der gesamte Client-Zustand ist **ein Archiv**:

```
backup.zip
├── data.sqlite          (alle Mails, Sync-States)
├── config.json          (Accounts, Einstellungen)
├── keyring.enc          (Passwörter, verschlüsselt)
└── manifest.json        (Version, Timestamp, Checksummen)
```

- **Anlegen** = Export-Dialog, Zielort frei wählbar (USB-Stick, Cloud, E-Mail-Anhang-Selbstversand)
- **Migration = ZIP einspielen** auf neuem Rechner/Phone → fertig
- Automatisch zusätzlich: `config` bei Account-Änderung ins Backup (Rollback-Sicherheit)
- Auf Android: Backup in den App-eigenen privaten Ordner schreiben, per `SAF` (Files App) exportieren

---

## 4. HTML-Anzeige: zwei Modi

*(a) Im Systembrowser* oder *(b) integriert, sicher*:

| Modus | Desktop | Android |
|---|---|---|
| **Systembrowser** | `java.awt.Desktop.browse()` | `Intent.ACTION_VIEW` |
| **Integriert (sicher)** | JavaFX WebView (WebKit) | `android.webkit.WebView` |

Die **Sanitisierung läuft im Core** (`html-sanitize`), nicht in der Schale:
- Alle `<script>` Blöcke geloscht
- `on*=` Event-Handlers neutralisiert
- `data:`-Bilder, `javascript:`-Links → `text/javascript:` entfernt, Links nicht-auto-geladen (Bilder per Hand laden)
- Ergebnis: Identisches Verhalten auf beiden Plattformen, obwohl die Rendering-Schalen verschiedene Engine

**Kern-Regel:** Sanitize-Logik in Core, Render-Logik in der Schale.

---

## 5. Outlook / Thunderbird: Import & Export

| Richtung | Mechanik | Aufwand |
|---|---|---|
| **Outlook (`.pst`) ein** | `libpst` (GPL) als **externe CLI** (loses Coupling, wie ClamAV) – kein Embed in JVM | mittel |
| **Thunderbird ein** | Mbox-Dateien aus `Thunderbird/Profiles/<prof>/Mail/` **direkt lesen** (eigener Mbox-Parser, reines Java) | klein |
| **In `.pst` exportieren** | Kein OSS-PST-Schreibprogramm → **Umweg: `.eml`-Ordner** (Outlook importiert EML direkt, Thunderbird ebenso) oder Mbox | Umweg |
| **In Thunderbird exportieren** | Mbox + `config.json` in Thunderbird-Profil-Format; oder einfache Mbox-Rundreise | klein |

**Faustregel:** `.eml` ist der universelle Export-Format, das beide Mail-Programme verstehen. **PST-Schreiben wird bewusst *nicht* gebastelt** – EML ist pragmatisch genug.

---

## 6. Security

### 6.1 Absender-Verifikation (SPF / DKIM / DMARC)

| Prüfe | Wie | Ergebnis in UI |
|---|---|---|
| **SPF** (IP-Listen) | DNS-Query `TXT <domain>` + eigene Implementierung (`spf-check`-Klasse) | Badge `✓` / `⚠` / `✗` |
| **DKIM** (Signatur) (Bouncy Castle, BSL-1.1 = Apache-kompatibel) `M-From-Domain` | Badge + Tooltip „Signatur gehört …" | |
| **DMARC** (Alignment) (DNS `TXT _dmarc.<domain>` + eigene Parser) | Badge für „wem gehört die Signatur" | |
| **Resultat** | Dreifach-Badge: `✓` grün, `⚠` gelb, `✗` rot + Tooltip mit Details | Klickable: Details-Ansicht (DNS-Logge, geprüfte Werte) |

Alles in Core → identisch auf Desktop und Android.

### 6.2 Virenscanner

**Kostenlose Option – die einzige Embedded-Fähige ist ClamAV:**

| Option | Typ | Preis | OS | Lizenz | Einbau |
|---|---|---|---|---|---|
| **ClamAV (`clamd`)** | lokaler Daemon | frei | Linux/Windows | **GPL v2** | **TCP-Ausgeliefert, loose coupling** (Port 3310) |
| **Clamwin** | Windows-AV-GUI | frei (Privat) | nur Windows | GPL | Windows-only, GUI statt CLI |
| **VirusTotal / Jotti** | Cloud-API | frei (Quoten) | jede OS mit Netz | proprietär | nur „zweite Meinung" als Add-on |
| **AVGL / AVG / Bitdefender** | Endpoint-Agent | frei | nur Win/macOS | proprietär | ✘ – keine Embed-API |

**Lizenz-Lösung:** ClamAV ist **GPL v2** (nicht Apache-kompatibel im embeden-Sinne), kommt also **in ein eigenes Modul**, das sich per **TCP auf `clamd`** (Port 3310) spricht. Keine GPL-Code-Mischung im Core – wie Thunderbird/K-9 Mail auch.

### 6.3 Eigene Heuristik (reines Java, läuft ohne ClamAV – v. a. auf Android)

- **Anhang-Erweiterungen sperren:** `.exe, .msi, .scr, .com, .pif, .vbs, .js, .jsm, .hta, .wsf, .lnk, .jar` → rote Markung „nicht ausführbar"
- **HTML-Detektion:** inline `<script>`, `on*=`-Handler, `data:`-URLs → „Verdächtig" markieren
- **Hash-Check:** SHA-256-Anhänge gegen lokale Signatur-DB (optional aus Netz aktualisierbar)
- **URL-Check:** Links auf Verdachts-TLDs (`file://`, `javascript:`, Verdachts-Vorkürzer) → „nicht vertrauenswürdig"

→ **Die App ist selbstschützend, auch ohne ClamAV.** ClamAV ist dann nur noch ein „vollständiges Antivirus".

### 6.4 GPG

- **Bouncy Castle** (BSL-1.1) – Apache-kompatibel
- **Zwei Modi:** (a) nur unterschreiben (einfach) – (b) signieren & verschlüsseln (Vollversion)
- Schlüssel-Verwaltung: eigene GPG-Implementation in Core (kein GnuPG-Embed)

---

## 7. Risiken & Lösungen

| Risiko | Lösung |
|---|---|
| **IMAP-IDLE auf Android zieht Akku** | `WorkManager`-Scheduled-Sync + optionales IDLE im Foreground; NotificationChannel für neue Mails |
| **XOAUTH2** (Gmail/Outlook/Proton) ist komplex | Früh in Core, nicht als Modul; `TokenSource`-Interface, das UI-agnostisch ist |
| **Android Java-17-Features nicht auf älteren API-Levels** | `minSdk = 28`; `jdk*`-Librarys per D8 desugar |
| **JavaFX-Lizenz auf Linux** (JavaFX-Runtime = GPL) | JPackage/`java -jar` für Desktop-Phase; später MSIX/AppImage |
| **PST-schreibrn** | Nicht gemacht – EML/Outlook-Roundround ist pragmatisch |
| **Konflikt bei Sync** (Offline-Änderung) | `uidvalidity`-Tracking pro Folder, `sync-state`-Spalte, Regeln: Server gewinnt Ordnerstruktur; lokale Änderungen als Pending |
| **GPL (ClamAV/libpst) im Apache-Core** | Loose coupling: beide als externe CLI/Daemon, kein Code-Gem |

---

## 8. Meilensteine (Teilzeit-Taktigkeit, keine harten Fristen)

| Meile | Inhalt | Grobe Wochen |
|---|---|---|
| **M0 · Spike** | Core headless, 1 Konto, IMAP-Sync senden; CLI-Test; Robolectric-Stellungsprüfung (Kern läuft auf Android) | 1–2 |
| **M1 · Core** | SQLite-Storage, Modell vollständig, Config-UI-frei, SPF/DKIM/DMARC-Module, Sanitize | 2 |
| **M2 · Desktop-MVP** | JavaFX-Schale: Kontoverwaltung, Posteingang lesen/schreiben/antworten, Suche, GPG-Signierung | 2–3 |
| **M3 · Android-Shell** | Activity/ListView; Batterie-Policy; Benachrichtigungen; HTML-zwei-Modi | 2–3 |
| **M4 · Module** | Search-FTS, GPG-Voll, Spam-Local, Export/Import (EML/Mbox/PST via libpst) | 2 |
| **M5 · Packaging & CI** | MSIX/AppImage/deb; Robolectric-CI; GPG + ClamAV Integration; Backup-/Migration-End-to-End | 1–2 |

---

## 9. Offene Fragen

1. **Projekt-Name** – Working-Title gesucht: `mailclient-java`? `postfink`? `briefbote`?
2. **GPG** – jetzt Vollversion (signieren + verschlüsseln) oder erst nur Signatur?
3. **Desktop-Benachrichtigungen:** Tray/System-Notifier, oder nur in der App?
4. **Ziel-Plattform (Desktop):** nur Windows+Linux, oder auch macOS? (Beeinflusst JPackage-Konfiguration)
5. **Release-Kanal:** GitHub Releases? Oder auch eigene Website + AUR (Arch) / Homebrew-Formul?

---

## 10. Nächste Schritte

> Stand 12.10.2026: M0–M3 fertig (131+ Tests grün), M4-Shell (Swing) läuft bereits mit Demo-Daten, Layouts DE/EN, Design-Menü, Compose lokal.

- Farbschema **Hell/Dunkel/Automatisch** in der UI verdrahten (`Theme.java` steht, Menü + Persistenz + Farb-Refactoring fehlen)
- `ThemeTest` als JUnit-Test (Kontrast + Erkennungslogik)
- GitHub-Actions-Build (`gradle build` als Status-Check)
- M4-003: echte Konto-Anbindung (IMAP/POP3 via MailSyncService) statt Demo-Daten
- M4-006/007: Attachment-UI + SMTP-Versand aus dem Composer

---

## 11. Referenz: Bestehende Mail-Clients (Daten & Lehren)

> Ergänzt 11.10.2026, quellgeprüft (GitHub/Manpages/Mozilla-Support). Zeigt, wie etablierte Clients exakt die Probleme lösen, die in §2–§7 entschieden wurden.

### 11.1 K-9 Mail / Thunderbird for Android — **der wichtigste Architektur-Vorläufer**

- **Stack:** Java/Kotlin, **Apache-2.0**, Gradle Multi-Module (`core` / `mail-common` / `ui-*` getrennt) — direkte Bestätigung unseres Core/Schalen-Splits. Seit Juni 2022 Teil der Thunderbird-Familie, Basis von „Thunderbird for Android".
- **GPG:** *nicht* eingebaut, sondern per **externer App OpenKeychain** über IPC. Trick dabei: die Client-Bibliothek `openpgp-api-lib` ist **Apache-2.0**, obwohl OpenKeychain selbst GPLv3+ ist — Loose-Coupling über Prozessgrenzen hält die Lizenz sauber. **Lehre für uns:** PGP-Anbieter als Interface (`PgpProvider`) designen: Desktop = Bouncy Castle intern, Android = wahlweise BC intern *oder* OpenKeychain via OpenPGP-API.
- **Push/Akku:** IMAP-IDLE nur im Foreground-Service, sonst periodischer Sync — exakt unsere §7-Batterie-Policy.
- **Storage:** SQLite, lokale + serverseitige Suche.

### 11.2 Mozilla Thunderbird (Desktop)

- **Stack:** Gecko/C++/JS, MPL-2.0. Storage: mbox/Maildir im Profil + `.msf`-Indexdateien.
- **GPG:** seit v78 **eingebautes OpenPGP via RNP** (C++-Lib). GnuPG wurde bewusst abgeklemmt — Mozilla selbst nennt **Lizenzgründe** („no longer using GnuPG by default to avoid licensing issues"). **Bestätigt unsere §6.4-Entscheidung** „eigene Implementation, kein GnuPG-Embed"; unser Äquivalent zu RNP ist Bouncy Castle.
- **HTML:** Remote-Inhalte standardmäßig blockiert, manuelles Nachladen pro Mail („Remote content laden") — identisch zu unserem §4-Zwei-Modi-Ansatz; Sanitize/Policy im Kern, Rendering in der Engine.
- **PST:** nur **Import** (Assistent nutzt readpst/libpst); PST-Export bietet auch Thunderbird nicht. Unsere §5-Entscheidung „PST schreiben = nein, EML reicht" ist Industriestandard.

### 11.3 Claws Mail — Vorlage für ClamAV- und Renderer-Loose-Coupling

- **Stack:** C/GTK, GPLv3, extrem leichtgewichtig; Plugins sind ladbare Module (wie unser SPI/ServiceLoader).
- **ClamAV-Plugin:** Manpage sagt wörtlich: *„Due license restrictions no clamav library is used and it is required that the ClamAV daemon (clamd) is running on some computer, either localhost **or remote**."* → 1:1 unsere §6.2-Strategie (TCP, kein GPL-Code im Kern). **Zusatz-Lehre:** clamd darf remote sein → unser `clamd-client`-Modul sollte `host:port` konfigurierbar machen; dann kann auch die **Android-App einen clamd auf dem NAS** (z. B. Docker auf miniedi) nutzen.
- **HTML-Rendering:** zwei Plugins — „Dillo" (startet externen Mini-Browser-Prozess mit Spezialoptionen, sicher eingebettet) und „Fancy" (WebKit-GTK). Vorläufer unseres Prinzips „Render-Logik austauschbar, Policy im Kern".

### 11.4 libpst / readpst (PST-Import, Lizenz-Fakten)

- **Lizenz: GPL-2.0-or-later** (bestätigt über Homebrew-Formula/Kali-Paket) → externer CLI-Aufruf ist Pflicht, Einbetten in Apache-2.0-Code verboten. Unser §5-Ansatz (externe CLI wie ClamAV) bleibt korrekt.
- `readpst` konvertiert PST → **mbox, MH, KMail-Format oder Einzel-EMLs**; dazu `pst2ldif` (Kontakte → LDIF). Für unseren Import reicht: readpst → EML/Mbox → eigener Parser.
- Alternative ohne GPL: Microsofts **pstsdk** (C++, Apache-2.0) — nur relevant, falls wir je PST *ohne* externe CLI lesen wollen; für Java gibt es keine gepflegte OSS-Lösung mit kompatibler Lizenz.

### 11.5 Übernahme-Entscheidungen aus der Referenz (Delta zu Durchgang 3)

1. **`PgpProvider`-Interface** in `core/spi`: Implementierungen `bc-gpg` (Desktop/Android intern) und optional `openkeychain` (Android, via Apache-2.0-API-Lib) — K-9-Muster.
2. **`clamd-client` mit konfigurierbarem Host/Port** (nicht nur localhost) — Claws-Muster; ermöglicht NAS-Scanning für Android.
3. **readpst bleibt externe CLI** (GPL-2.0+ bestätigt); Import-Pipeline: readpst → EML/Mbox → `export-eml-mbox`-Parser wiederverwenden.
4. **Thunderbird bestätigt zweifach:** kein GnuPG-Embed (Lizenz) und kein PST-Export (Pragmatik) — unsere Entscheidungen sind deckungsgleich mit dem Marktführer.

### 11.6 Quellen

- github.com/thunderbird/thunderbird-android (K-9/Thunderbird for Android, Module, Apache-2.0)
- deepwiki.com/thunderbird/thunderbird-android → Security and Legal (openpgp-api-lib Apache-2.0 vs. OpenKeychain GPLv3+)
- support.mozilla.org „OpenPGP in Thunderbird – HOWTO and FAQ" (GnuPG-Abschaltung aus Lizenzgründen); blog.thunderbird.net „OpenPGP in Thunderbird 78"; rnpgp.org / github.com/rnpgp/rnp
- manpages.ubuntu.com `claws-mail-clamd-plugin` (Zitat Lizenzbeschränkung, remote clamd); github.com/eworm-de/claws-mail (Dillo/Fancy-Plugins)
- formulae.brew.sh/formula/libpst (GPL-2.0-or-later); man.archlinux.org `readpst(1)`; kali.org/tools/libpst

---

## 12. Wiederverwendung: Prometheus-Spamfilter (eigener Bestand)

> Geprüft 11.10.2026: `prometheus-spamfilter` (CLI, ~1.850 LOC) + `prometheus-spamfilter-web` (~3.500 LOC), beide reines Java (`--release 11`), Deps: jakarta.mail 2.0.1, Jackson 2.15, OkHttp 4.12. **Eigener Code → Relizenzierung auf Apache-2.0 unproblematisch.** Java-11-Code läuft unter JDK 17 und ist D8-desugar-fähig → Android-tauglich.

### 12.1 Was direkt in welche Modul-Position wandert

| Spamfilter-Bestandteil | Größe | Ziel in „Modular" | Anmerkung |
|---|---|---|---|
| `SpamAnalyzer` + `SpamFilterEngine` (Score 0–100, Pattern-/Content-Heuristik, suspiciousIndicators) | ~1.100 LOC | `modules/spam-local` **und** Kern-Badges | ist exakt die geplante „eigene Heuristik" aus §6.3 — schon fertig, nur refaktorieren (Score-Tabelle konfigurierbar machen) |
| `HeaderAnalysis`/`ValidationResult` (SPF/DKIM/DMARC aus `Received-SPF` + `Authentication-Results`) | ~170 LOC | `core/security` | **wichtige Erkenntnis:** der Bestand wertet Header aus, keine eigene DNS-Prüfung — s. 12.2 |
| `ImapConnector` (jakarta.mail-Anbindung, Header-Extraktion) | 333 LOC | `protocols/jakarta-mail-imap` | Erfahrungscode; für Modular auf Jakarta Mail 2.1/Angus Mail + XOAUTH2 anheben |
| `EmlParser` (EML ohne IMAP lesen) | 153 LOC | `modules/export-eml-mbox` | Import-Pipeline (readpst → EML → Parser) kann direkt darauf aufsetzen |
| `VirusTotalScanner` (VT-API v3: Hash-Lookup → Upload → Polling, Anhang-Scan) | 386 LOC | neues Modul `virustotal` | deckt §6.2 „Cloud nur als zweite Meinung" — bereits implementiert inkl. Key-Datei-Handling |
| `WhoisLookup` + `WebReportGen` (Report-HTML, Polizei-Formular Stage 3) | ~1.070 LOC | optionales Modul `phishing-report` | Desktop-first; WHOIS-Daten auch für Badge-Tooltips nützlich |

### 12.2 Korrektur/Verfeinerung zu §6.1 (durch Bestandscode belegt)

Der Spamfilter prüft SPF/DKIM/DMARC **nicht per eigenem DNS**, sondern liest `Received-SPF` und `Authentication-Results` — das Ergebnis, das der empfangende Mailserver bereits eingetragen hat. Für einen Client ist das der **zuverlässigere** Weg (der Client sieht die originale SMTP-Verbindungs-IP ohnehin nicht, nur Rekonstruktion aus `Received:`-Ketten). Daraus wird ein **Zwei-Stufen-Design** für `core/security`:

1. **Stufe 1 (Port aus Spamfilter):** Header-Parsing `Authentication-Results`/`Received-SPF` → Badges. Getestet, sofort verfügbar, funktioniert bei Strato/Gmail/Outlook.
2. **Stufe 2 (Neuentwicklung, Fallback):** eigene DKIM-Signaturprüfung mit Bouncy Castle + SPF/DMARC-DNS-Lookups, wenn der Server keine `Authentication-Results` liefert oder manipulierte Header verdächtig sind. Bleibt als M1-Ziel, rutscht aber hinter Stufe 1.

### 12.3 Auswirkungen auf Meilensteine (§8)

- **M1 wird deutlich billiger:** `core/security` (Stufe 1) + `spam-local`-Grundgerüst + EML-Parsing existieren bereits → Schätzung 2 Wochen → **~1 Woche**.
- **M4:** `virustotal`-Modul = Port statt Neubau; `phishing-report` als Stretch-Goal.
- **M0-Spike** kann mit dem bekannten jakarta.mail-Code (`ImapConnector`) starten — weniger Risiko beim ersten Kontakt mit IDLE/UIDVALIDITY.

### 12.4 Migrations-Bedingungen (Sauberkeit)

- Paket-Rename `prome.spamfilter.*` → neues Modul-Schema; toter Code (WebServer/SessionManager/HTTP-Schicht) bleibt draußen — Modular braucht davon nichts (UI kommt aus der Schale).
- `config.properties`-Muster → in `core/config` überführen; VT-Key wandert in den Keystore/keyring.enc (§3), nicht mehr als Klartext-Datei.
- Jackson/OkHttp: OkHttp nur im `virustotal`-Modul behalten (Android-tauglich); Kern bevorzugt `java.net.http.HttpClient` (JDK 17) → eine Abhängigkeit weniger.
