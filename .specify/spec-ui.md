# UI-Vorgabe (M4/M5) — Desktop-Client

Stand: 12.10.2026, ausformuliert von Georg, niedergeschrieben von Prometheus.

## 1. Technologie

- **Swing** (Georgs Entscheidung, 12.10.2026).
- Begründung pro Swing: im JDK enthalten (keine Runtime-Abhängigkeit), ausgereift,
  riesiges Ökosystem, System-Look-and-Feel plattformnativ.
- **Abgewogener JavaFX-Vorteil** (dokumentiert für die Entscheidung, nicht als Widerspruch):
  - JavaFX-WebView (WebKit) rendert modernes HTML — Swing hat nur `JEditorPane`
    (HTML 3.2 / CSS-Basis-Support). Moderne HTML-Mails sehen in Swing grober aus.
  - JavaFX hat CSS-Theming, Property-Binding, bessere HiDPI-Skalierung.
  - **Konsequenz für Swing:** HTML-Rendering über `JEditorPane` mit unserem
    `HtmlSanitizer` als Vorstufe (Whitelist-Tags/Attribute passen ohnehin gut zu
    JEditorPanes begrenztem CSS-Support). Qualitätsziel: lesbare Darstellung,
    keine Pixel-Perfektion. Später optional: WebView per JavaFX-Embedding
    (JFXPanel) oder JxBrowser nachrüstbar — Layout-System bleibt davon unberührt.

## 2. Layout-System (Kernanforderung)

- Klassisches 3-Spalten-Layout als Basis: **Ordnerbaum | Mail-Liste | Lesebereich**.
- **Aber: Layouts sind vom Anwender zur Laufzeit tauschbar** (umschaltbar per Menü/Settings).
- Mitgelieferte Layout-Presets:
  1. **Default** — unser klassisches 3-Spalten-Layout
  2. **Thunderbird** — 3-Spalten, Ordnerbaum links breit, Konto-Header, Quick-Filter-Leiste
  3. **Outlook** — Ordner links schmal, Liste+Lesebereich rechts, Lesebereich unten/rechts umschaltbar
  4. **Gmail** — Listen-dominant, Konversationen-Ansicht, Lesebereich als Vollansicht/Overlay
- Technische Umsetzung: `MailUILayout`-Interface (Komponenten-Lieferant + Anordnung),
  Presets als Implementierungen; Umschalten ohne Neustart, Persistenz der Wahl in SQLite.

## 3. Threading

- **Als Option** (umschaltbar pro Ansicht/Account): flache Liste ODER Konversations-Gruppierung.
- Backend steht: `MessageThreader` (M3-003), thread_id in SQLite.

## 4. Account-Verwaltung

- **Mehrere E-Mail-Konten**, vollständig per UI verwaltet (Anlegen/Bearbeiten/Löschen/Test-Login).
- Pro Account: Protokoll (IMAP/POP3), Host/Port/Security (NONE/STARTTLS/SSL), Benutzer,
  Passwort (verschlüsselt gespeichert — nie Klartext in SQLite), SMTP-Einstellungen.
- Sync-Status pro Account sichtbar; manueller + automatischer Abruf.

## 5. Pflicht-Features (erste Version)

- Mail-Liste mit Sortierung (Datum/Absender/Betreff/Spam-Score), Paging für große Postfächer
- Lesebereich: Text + HTML (saniert), Header-Anzeige, SPF/DKIM/DMARC-Status-Badge
- Spam-Anzeige: Score + Ampel/Badge pro Mail (aus M2-Heuristik)
- Suche (Betreff/Absender/Inhalt) über die SQLite-Suche
- Attachments: anzeigen, öffnen (Desktop-Integration), speichern unter
- Schreiben/Antworten/Antworten an alle/Weiterleiten (SMTP, Multipart, Attachments)
- Flag/Gelesen/Ungesehen/Löschen (Soft-Delete lokal, IMAP-Flag-Sync wo möglich)
- Ordnerbaum (IMAP: Server-Ordner; POP3: nur Posteingang + lokale Ordner)

## 6. Nicht in der ersten Version (Backlog)

- Verschlüsselung (S/MIME, PGP), Signatur-Editor, Filter-/Regel-Engine,
  Benachrichtigungen (Tray), Kalenderschnittstellen, Offline-Cache-Strategie über
  Erstsync hinaus, Android-UI (M5) erst nach M4-Fertigstellung.

## 7. Qualitätsrahmen (Constitution gilt)

- Core/Protocol/Storage-Module bleiben UI-frei; `desktop/`-Modul hängt nur an ihnen.
- UI-Code auf dem EDT; Sync/Netz immer in Worker-Threads (SwingWorker/Executor),
  keine blockierenden IO-Aufrufe im EDT.
- Jede Layout-/Feature-Einheit mit Tests (Kopfbereiche: Model-Bindung, Preset-Logik),
  UI-Smoke-Tests headless wo möglich.

## 8. Umsetzungsstand (13.10.2026)

Erledigt: Swing-Shell mit 5 Layout-Presets (inkl. Foxmail), Sprachumschaltung DE/EN,
Design-Menü (plattformgefiltert), Farbschema Hell/Dunkel/Automatisch (SQLite-persistiert),
Konten-Verwaltung (Anlegen mit Strato-Preset + Live-Verbindungstest, **Bearbeiten/Löschen**,
**Konto-Umschalter** in der Toolbar), **echter IMAP/POP3-Sync** mit Statuszeilen-Feedback,
**Papierkorb** (Löschen → Papierkorb, Wiederherstellen, endgültig löschen, Papierkorb leeren),
**Suche in SQL** mit Feld-Präfixen (`from:`, `subject:`, `has:attachment:`),
**SPF/DKIM/DMARC-Ampel** im Leser, **Vorschau wahlweise integriert (JEditorPane) oder im
Standardbrowser des Systems**, **wählbarer Ablageort**, Threading-Umschaltung, Spam-Prozent-Ampel,
Icon/Logo.

Offen (Backlog M4): SMTP-Versand (M4-007), Attachment-UI öffnen/speichern (M4-006), Paging-UI,
HiDPI/Tray, Flag- und Lösch-Sync zum IMAP-Server.

### 8.1 Vorschau im Systembrowser (Implementierungsdetail)

`PreviewExport` schreibt das bereits sanitisierte HTML als UTF-8-Datei mit
`Content-Security-Policy: default-src 'none'; img-src data:; style-src 'unsafe-inline'`
und übergibt sie an `Desktop.browse`. Damit lädt der Browser **keine entfernten Bilder oder
Skripte** nach (kein Tracking-Pixel beim reinen Ansehen). Ist kein Desktop/Browser verfügbar,
bleibt die integrierte Ansicht aktiv und die Statuszeile meldet den Grund.

Menü: **Ansicht → Vorschau öffnen mit** (Integriert / Standardbrowser, in SQLite persistiert) sowie
**Ansicht → Ausgewählte Mail im Browser öffnen** für den Einzelfall.

### 8.2 Ablageort (Implementierungsdetail)

`AppPaths.resolveDataDir()` löst in dieser Reihenfolge auf:

1. `-Dmailclient.data=<Pfad>`
2. `location.txt` im Benutzerprofil (vom Menü geschrieben: `portable`, `user` oder ein Pfad)
3. Marker `portable.txt` neben dem Jar
4. bestehendes `~/MailClient-Daten` (Abwärtskompatibilität)
5. OS-Standard (Windows `%APPDATA%\MailClient`, macOS `~/Library/Application Support/MailClient`,
   Linux `~/.local/share/mailclient`)

Menü: **Datei → Datenordner…** (Portabel / Benutzerverzeichnis / eigener Ordner / Ordner öffnen).
Der Start gibt den benutzten Ordner auf der Konsole aus; Änderungen greifen nach einem Neustart.

### 8.3 Papierkorb (Implementierungsdetail)

`MailStore` bietet `listDeleted`/`countDeleted` (Papierkorb-Ansicht), `restore`,
`purge` (endgültig, inklusive Anhang-Dateien von der Platte) und `purgeDeleted`
(Papierkorb leeren). Die vier Standard-Ordner werden für jedes Konto sichergestellt.
⚠️ Grenze: Das Löschen wirkt **lokal** — der IMAP-Server wird (noch) nicht informiert.
