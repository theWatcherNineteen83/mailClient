package de.metis.mail.desktop;

import de.metis.mail.db.MailStore;
import de.metis.mail.db.StoredMessage;
import de.metis.mail.security.HtmlSanitizer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.sql.SQLException;
import java.util.List;
import java.util.*;

/**
 * Hauptfenster der Desktop-Shell (M4-001/M4-002).
 * Alle Texte via I18n; Layout zur Laufzeit umschaltbar via LayoutPreset.
 */
public final class MailFrame extends JFrame {

    private final MailStore store;
    private final UiSettings settings;
    private final HtmlSanitizer sanitizer = new HtmlSanitizer();

    private long accountId;
    private final Map<String, Long> folders = new LinkedHashMap<>(); // Anzeige-Name -> Ordner-ID
    private final Map<String, String> folderKeyByDisplay = new HashMap<>(); // Anzeige -> i18n-Schlüssel
    private String currentFolderDisplay;

    // Komponenten (werden je Layout neu angeordnet, nie neu erfunden)
    private final DefaultListModel<String> folderModel = new DefaultListModel<>();
    private final JList<String> folderList = new JList<>(folderModel);
    private final MessageTableModel tableModel = new MessageTableModel();
    private final JTable messageTable = new JTable(tableModel);
    private final JEditorPane reader = new JEditorPane();
    private final JTextField searchField = new JTextField();
    private final JTextField quickFilter = new JTextField();
    private final JLabel accountHeader = new JLabel();
    private final JLabel statusLeft = new JLabel();
    private final JLabel statusRight = new JLabel();
    private final JToolBar toolBar = new JToolBar();
    private final JPanel centerPanel = new JPanel(new BorderLayout());
    private JSplitPane lastSplitA, lastSplitB;

    private LayoutPreset layout;
    private boolean groupedThreads;
    private String lookKey;

    /** Design-Palette: neutrale helle Foxmail-Farben (Grau/Blau/Weiß). */
    private static final Color BG_NEUTRAL = new Color(0xFAFBFC);
    private static final Color ACCENT_BLUE = new Color(0x3D6EB5);
    private static final Color HEADER_GRAY = new Color(0xEDF1F5);

    public MailFrame(MailStore store, UiSettings settings, LayoutPreset preset) {
        this.store = store;
        this.settings = settings;
        this.layout = preset;
        this.groupedThreads = "true".equals(settings.get("group.threads", "false"));
        this.lookKey = System.getProperty("mailclient.laf", settings.get("ui.laf", "auto"));

        setTitle("MailClient — M4-Shell");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 760);
        setLocationRelativeTo(null);

        reader.setEditable(false);
        messageTable.getSelectionModel().addListSelectionListener(e -> onMessageSelected());
        folderList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onFolderSelected();
        });
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { reloadMessages(); }
            public void removeUpdate(DocumentEvent e) { reloadMessages(); }
            public void changedUpdate(DocumentEvent e) { reloadMessages(); }
        });
        quickFilter.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { reloadMessages(); }
            public void removeUpdate(DocumentEvent e) { reloadMessages(); }
            public void changedUpdate(DocumentEvent e) { reloadMessages(); }
        });

        buildMenus();
        buildToolBar();
        add(toolBar, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        try {
            loadData();
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Datenfehler", JOptionPane.ERROR_MESSAGE);
        }
        applyLayout();
        updateStatus();
    }

    /** Setzt Sprache neu und baut Menü/Labels/Inhalte frisch auf. */
    public void switchLanguage(String code) {
        I18n.setLanguage(code);
        settings.put("ui.language", code);
        rebuildFolderLabels();
        buildMenus();
        applyLayout();
        reloadMessages();
        updateStatus();
        repaint();
    }

    public void switchLayout(LayoutPreset p) {
        this.layout = p;
        settings.put("ui.layout", p.name());
        applyLayout();
    }

    public boolean isGroupedThreads() { return groupedThreads; }

    // ---------- Aufbau ----------

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusRight.setHorizontalAlignment(SwingConstants.RIGHT);
        p.add(statusLeft, BorderLayout.WEST);
        p.add(statusRight, BorderLayout.EAST);
        return p;
    }

    private void buildToolBar() {
        toolBar.setFloatable(false);
        toolBar.removeAll();
        JButton newBtn = new JButton(I18n.tr("toolbar.new"));
        JButton readBtn = new JButton(I18n.tr("toolbar.read"));
        JButton delBtn = new JButton(I18n.tr("toolbar.delete"));
        newBtn.addActionListener(e -> showInfo(I18n.tr("menu.file.new")));
        readBtn.addActionListener(e -> markSelectedRead());
        delBtn.addActionListener(e -> showInfo(I18n.tr("menu.edit.delete")));
        toolBar.add(newBtn);
        toolBar.add(readBtn);
        toolBar.add(delBtn);
        toolBar.add(Box.createHorizontalGlue());
        if (layout == LayoutPreset.OUTLOOK || layout == LayoutPreset.GMAIL) {
            searchField.setPreferredSize(new Dimension(280, 28));
            searchField.setBorder(BorderFactory.createTitledBorder(I18n.tr("col.subject") + "/…"));
            toolBar.add(searchField);
        }
        toolBar.revalidate();
    }

    private JMenuBar buildMenus() {
        JMenuBar mb = new JMenuBar();
        setJMenuBar(mb);

        JMenu file = mb.add(new JMenu(I18n.tr("menu.file")));
        file.add(item(I18n.tr("menu.file.new"), () -> showInfo(I18n.tr("menu.file.new"))));
        file.add(item(I18n.tr("menu.file.sync"), () -> showInfo(I18n.tr("menu.file.sync"))));
        file.addSeparator();
        file.add(item(I18n.tr("menu.file.quit"), this::dispose));

        JMenu edit = mb.add(new JMenu(I18n.tr("menu.edit")));
        edit.add(item(I18n.tr("menu.edit.read"), this::markSelectedRead));
        edit.add(item(I18n.tr("menu.edit.delete"), () -> showInfo(I18n.tr("menu.edit.delete"))));

        JMenu view = mb.add(new JMenu(I18n.tr("menu.view")));
        JMenu layouts = new JMenu(I18n.tr("menu.view.layout"));
        view.add(layouts);
        ButtonGroup lg = new ButtonGroup();
        for (LayoutPreset p : LayoutPreset.values()) {
            JRadioButtonMenuItem m = new JRadioButtonMenuItem(I18n.tr(p.i18nKey()), p == layout);
            m.addActionListener(e -> switchLayout(p));
            lg.add(m);
            layouts.add(m);
        }
        JMenu langs = new JMenu(I18n.tr("menu.view.language"));
        view.add(langs);
        ButtonGroup langGroup = new ButtonGroup();
        for (String code : new String[]{"de", "en"}) {
            JRadioButtonMenuItem m = new JRadioButtonMenuItem(
                    "de".equals(code) ? "Deutsch" : "English", code.equals(I18n.language()));
            m.addActionListener(e -> switchLanguage(code));
            langGroup.add(m);
            langs.add(m);
        }
        JMenu looks = new JMenu(I18n.tr("menu.view.look"));
        view.add(looks);
        ButtonGroup lookGroup = new ButtonGroup();
        for (String key : new String[]{"auto", "gtk", "nimbus", "metal"}) {
            JRadioButtonMenuItem m = new JRadioButtonMenuItem(I18n.tr("look." + key), key.equals(lookKey));
            m.addActionListener(e -> switchLookFeel(key));
            lookGroup.add(m);
            looks.add(m);
        }
        view.addSeparator();
        JCheckBoxMenuItem thr = new JCheckBoxMenuItem(I18n.tr("menu.view.threads"), groupedThreads);
        thr.addActionListener(e -> {
            groupedThreads = thr.isSelected();
            settings.put("group.threads", String.valueOf(groupedThreads));
            reloadMessages();
        });
        view.add(thr);

        JMenu help = mb.add(new JMenu(I18n.tr("menu.help")));
        help.add(item(I18n.tr("menu.help.about"), () -> JOptionPane.showMessageDialog(this,
                I18n.tr("about.text"), I18n.tr("about.title"), JOptionPane.INFORMATION_MESSAGE)));
        return mb;
    }

    private JMenuItem item(String text, Runnable action) {
        JMenuItem m = new JMenuItem(text);
        m.addActionListener(e -> action.run());
        return m;
    }

    /** Ordner-Anzeigenamen bei Sprachwechsel aus der DB neu aufbauen. */
    private void rebuildFolderLabels() {
        try {
            loadData();
        } catch (SQLException ex) {
            statusLeft.setText("Fehler: " + ex.getMessage());
        }
    }

    // ---------- Layout ----------

    private void applyLayout() {
        centerPanel.removeAll();
        lastSplitA = null;
        lastSplitB = null;
        JScrollPane foldersScroll = new JScrollPane(folderList);
        foldersScroll.setPreferredSize(new Dimension(200, 100));
        foldersScroll.setBorder(BorderFactory.createEmptyBorder());
        JScrollPane tableScroll = new JScrollPane(messageTable);
        reader.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        reader.setBackground(Color.WHITE);
        JScrollPane readerScroll = new JScrollPane(reader);
        readerScroll.setBorder(BorderFactory.createLineBorder(HEADER_GRAY));

        boolean previewMode = layout == LayoutPreset.FOXMAIL;
        messageTable.setRowHeight(previewMode ? 46 : 20);
        messageTable.setShowGrid(false);
        messageTable.setIntercellSpacing(new Dimension(0, 0));
        messageTable.setSelectionBackground(HEADER_GRAY);
        messageTable.setSelectionForeground(new Color(0x1F2A36));
        messageTable.getTableHeader().setBackground(HEADER_GRAY);
        tableModel.setShowPreview(previewMode);

        JPanel rightCol = new JPanel(new BorderLayout());

        switch (layout) {
            case DEFAULT -> {
                JSplitPane inner = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, readerScroll);
                inner.setResizeWeight(0.45);
                lastSplitA = inner;
                JSplitPane outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, foldersScroll, inner);
                outer.setResizeWeight(0.18);
                lastSplitB = outer;
                centerPanel.add(outer, BorderLayout.CENTER);
            }
            case THUNDERBIRD -> {
                foldersScroll.setPreferredSize(new Dimension(260, 100));
                rightCol.add(accountHeader, BorderLayout.NORTH);
                JPanel listHeader = new JPanel(new BorderLayout());
                quickFilter.setPreferredSize(new Dimension(200, 26));
                listHeader.add(new JLabel(I18n.tr("filter.quick")), BorderLayout.WEST);
                listHeader.add(quickFilter, BorderLayout.CENTER);
                JSplitPane inner = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, readerScroll);
                inner.setResizeWeight(0.4);
                JPanel listWrap = new JPanel(new BorderLayout());
                listWrap.add(listHeader, BorderLayout.NORTH);
                listWrap.add(inner, BorderLayout.CENTER);
                lastSplitA = inner;
                JSplitPane outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, foldersScroll, listWrap);
                outer.setResizeWeight(0.22);
                lastSplitB = outer;
                centerPanel.add(outer, BorderLayout.CENTER);
            }
            case OUTLOOK -> {
                foldersScroll.setPreferredSize(new Dimension(160, 100));
                JPanel topRow = new JPanel(new BorderLayout());
                topRow.add(foldersScroll, BorderLayout.WEST);
                topRow.add(tableScroll, BorderLayout.CENTER);
                JSplitPane vert = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topRow, readerScroll);
                vert.setResizeWeight(0.55);
                lastSplitA = vert;
                centerPanel.add(vert, BorderLayout.CENTER);
            }
            case GMAIL -> {
                foldersScroll.setPreferredSize(new Dimension(180, 100));
                JPanel listWrap = new JPanel(new BorderLayout());
                listWrap.add(tableScroll, BorderLayout.CENTER);
                JSplitPane outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, foldersScroll, listWrap);
                outer.setResizeWeight(0.15);
                lastSplitB = outer;
                centerPanel.add(outer, BorderLayout.CENTER);
            }
            case FOXMAIL -> {
                // Foxmail-Vorbild: drei Spalten — Konto/Ordner | Liste mit Textvorschau | Lesebereich rechts
                foldersScroll.setPreferredSize(new Dimension(210, 100));
                foldersScroll.getViewport().setBackground(BG_NEUTRAL);
                folderList.setBackground(BG_NEUTRAL);
                accountHeader.setForeground(ACCENT_BLUE);
                JPanel leftCol = new JPanel(new BorderLayout());
                leftCol.setBackground(BG_NEUTRAL);
                leftCol.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, HEADER_GRAY));
                leftCol.add(accountHeader, BorderLayout.NORTH);
                leftCol.add(foldersScroll, BorderLayout.CENTER);
                JPanel headBar = new JPanel(new BorderLayout());
                headBar.setBackground(HEADER_GRAY);
                headBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                JLabel folderTitle = new JLabel(currentFolderDisplay == null ? "" : currentFolderDisplay);
                folderTitle.setFont(folderTitle.getFont().deriveFont(Font.BOLD, 13f));
                folderTitle.setForeground(new Color(0x1F2A36));
                headBar.add(folderTitle, BorderLayout.WEST);
                JLabel unreadHint = new JLabel(I18n.tr("foxmail.hint"));
                unreadHint.setForeground(new Color(0x5A6A7A));
                headBar.add(unreadHint, BorderLayout.EAST);
                JPanel listCol = new JPanel(new BorderLayout());
                listCol.add(headBar, BorderLayout.NORTH);
                listCol.add(tableScroll, BorderLayout.CENTER);
                JSplitPane inner = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listCol, readerScroll);
                inner.setResizeWeight(0.48);
                inner.setDividerSize(6);
                lastSplitA = inner;
                JSplitPane outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftCol, inner);
                outer.setResizeWeight(0.17);
                outer.setDividerSize(0);
                lastSplitB = outer;
                centerPanel.add(outer, BorderLayout.CENTER);
            }
        }
        if (lastSplitA != null) lastSplitA.setDividerLocation(0.45);
        if (lastSplitB != null) lastSplitB.setDividerLocation(0.18);
        SwingUtilities.invokeLater(() -> {
            if (lastSplitA != null) lastSplitA.setDividerLocation(layout == LayoutPreset.OUTLOOK ? 420 : 0.45);
            if (lastSplitB != null) lastSplitB.setDividerLocation(200);
        });
        accountHeader.setFont(accountHeader.getFont().deriveFont(Font.BOLD, 13f));
        accountHeader.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        buildToolBar();
        centerPanel.revalidate();
        centerPanel.repaint();
    }

    // ---------- Daten ----------

    private void loadData() throws SQLException {
        accountId = store.accountIdByName("Georg Privat");
        folderModel.clear();
        folders.clear();
        folderKeyByDisplay.clear();
        Map<String, Long> dbFolders = store.foldersOf(accountId);
        for (Map.Entry<String, Long> e : dbFolders.entrySet()) {
            String key = folderKeyFor(e.getKey());
            String display = I18n.tr(key);
            folderKeyByDisplay.put(display, key);
            folders.put(display, e.getValue());
            folderModel.addElement(display);
        }
        currentFolderDisplay = folderModel.isEmpty() ? null : folderModel.get(0);
        folderList.setSelectedValue(currentFolderDisplay, true);
        accountHeader.setText(I18n.tr("account.header", "Georg Privat", "imap.strato.de"));
        reloadMessages();
    }

    private String folderKeyFor(String dbName) {
        return switch (dbName) {
            case "Posteingang" -> "folder.inbox";
            case "Gesendet" -> "folder.sent";
            case "Archiv" -> "folder.archive";
            case "Papierkorb" -> "folder.trash";
            default -> "folder.inbox";
        };
    }

    private void onFolderSelected() {
        String sel = folderList.getSelectedValue();
        if (sel != null) {
            currentFolderDisplay = sel;
            reloadMessages();
            updateStatus();
        }
    }

    private void reloadMessages() {
        Long folderId = currentFolderDisplay == null ? null : folders.get(currentFolderDisplay);
        if (folderId == null) return;
        try {
            List<StoredMessage> rows = store.listMessages(folderId, 500, 0);
            String q = effectiveQuery();
            if (!q.isBlank()) {
                String ql = q.toLowerCase(Locale.ROOT);
                rows = rows.stream().filter(m ->
                        (m.subject() != null && m.subject().toLowerCase(Locale.ROOT).contains(ql))
                                || (m.fromName() != null && m.fromName().toLowerCase(Locale.ROOT).contains(ql))
                                || (m.fromAddr() != null && m.fromAddr().toLowerCase(Locale.ROOT).contains(ql))
                                || (m.preview() != null && m.preview().toLowerCase(Locale.ROOT).contains(ql)))
                        .toList();
            }
            if (groupedThreads) {
                rows = new ArrayList<>(rows);
                rows.sort(Comparator.comparing((StoredMessage m) -> m.threadId() == null ? m.id() : m.threadId())
                        .thenComparing(StoredMessage::sentDate));
            }
            tableModel.setRows(rows, I18n.language());
            updateStatus();
        } catch (SQLException e) {
            statusLeft.setText("Fehler: " + e.getMessage());
        }
    }

    private String effectiveQuery() {
        String a = searchField.getText() == null ? "" : searchField.getText().trim();
        String b = quickFilter.getText() == null ? "" : quickFilter.getText().trim();
        return !a.isEmpty() ? a : b;
    }

    private void onMessageSelected() {
        int v = messageTable.getSelectedRow();
        if (v < 0) return;
        StoredMessage m = tableModel.rowAt(messageTable.convertRowIndexToModel(v));
        try {
            String[] bodies = store.getBodies(m.id());
            String html = bodies != null ? bodies[1] : null;
            String text = bodies != null ? bodies[0] : null;
            if (html != null && !html.isBlank()) {
                reader.setContentType("text/html");
                reader.setText(sanitizer.sanitize(html));
            } else {
                reader.setContentType("text/plain");
                reader.setText((text == null ? "" : text) + "\n\n— Vorschau: " + m.preview());
            }
            reader.setCaretPosition(0);
            statusRight.setText(I18n.tr("status.selected", m.subject()));
            if (m.unseen()) {
                store.setUnseen(m.id(), false);
                reloadMessages();
            }
        } catch (SQLException e) {
            reader.setContentType("text/plain");
            reader.setText("Fehler beim Laden: " + e.getMessage());
        }
    }

    private void markSelectedRead() {
        try {
            for (StoredMessage m : visibleRows()) store.setUnseen(m.id(), false);
            reloadMessages();
        } catch (SQLException ignored) { }
    }

    private List<StoredMessage> visibleRows() {
        List<StoredMessage> out = new ArrayList<>();
        for (int i = 0; i < messageTable.getRowCount(); i++) {
            out.add(tableModel.rowAt(messageTable.convertRowIndexToModel(i)));
        }
        return out;
    }

    private void updateStatus() {
        int n = tableModel.getRowCount();
        statusLeft.setText(I18n.tr("status.count", n, currentFolderDisplay == null ? "-" : currentFolderDisplay));
    }

    private void showInfo(String what) {
        JOptionPane.showMessageDialog(this, what + ": Demo-Shell — " + I18n.tr("about.text"));
    }

    /** Look&Feel zur Laufzeit wechseln und Persistenz aktualisieren. */
    public void switchLookFeel(String key) {
        try {
            String active = LookFeel.apply(key);
            lookKey = key;
            settings.put("ui.laf", key);
            SwingUtilities.updateComponentTreeUI(this);
            applyLayout();
            statusRight.setText(I18n.tr("look.active", active));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    I18n.tr("look.fail", ex.getMessage()), I18n.tr("menu.view.look"), JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Kopfzeilen-Mail selektieren (für automatisierte Screenshots/Tests). */
    public void selectFirstMessage() {
        if (messageTable.getRowCount() > 0) {
            messageTable.setRowSelectionInterval(0, 0);
            messageTable.requestFocusInWindow();
        }
    }

    /** Für Screenshot-Läufe: Layout + Sprache von außen setzen. */
    public void configureFor(String layoutName, String lang) {
        LayoutPreset p = LayoutPreset.fromString(layoutName);
        if (!p.name().equals(layout.name())) switchLayout(p);
        if (lang != null && !lang.equals(I18n.language())) switchLanguage(lang);
    }
}
