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
