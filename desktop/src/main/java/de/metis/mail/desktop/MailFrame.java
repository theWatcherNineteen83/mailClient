package de.metis.mail.desktop;

import de.metis.mail.db.MailStore;
import de.metis.mail.db.StoredMessage;
import de.metis.mail.db.SyncService;
import de.metis.mail.security.HtmlSanitizer;
import de.metis.mail.security.SecurityBadge;

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
    private final JTextField searchField = new PlaceholderTextField("search.placeholder");
    private final JTextField quickFilter = new PlaceholderTextField("filter.quick");
    private final JLabel accountHeader = new JLabel();
    /** M4-003: Konto-Umschalter — Wechsel lädt Ordner und Mails des gewählten Kontos. */
    private final JComboBox<String> accountBox = new JComboBox<>();
    private boolean populatingAccounts;
    private String currentAccountName;
    /** M4-006: Anhang-Leiste unter dem Leser (Name, Groesse, Oeffnen/Speichern). */
    private final DefaultListModel<MailStore.StoredAttachment> attachmentModel = new DefaultListModel<>();
    private final JList<MailStore.StoredAttachment> attachmentList = new JList<>(attachmentModel);
    private final JPanel attachmentPanel = new JPanel(new BorderLayout());
    private JPanel readerHost;
    private final JLabel attachmentLabel = new JLabel();

    /** M4-004: SPF/DKIM/DMARC-Ampel ueber dem Leser. */
    private final JLabel securityBadge = new JLabel();
    private final JLabel statusLeft = new JLabel();
    private final JLabel statusRight = new JLabel();
    private final JToolBar toolBar = new JToolBar();
    private final JPanel centerPanel = new JPanel(new BorderLayout());
    private JSplitPane lastSplitA, lastSplitB;

    private LayoutPreset layout;
    /** M4-004: Vorschau-Modus — integriert (Java) oder im Standardbrowser des Systems. */
    private String previewMode;
    /** M4-004: eine Vorschau-Instanz pro Programmstart (kein Leck durch wiederholtes inTempDir()). */
    private final PreviewExport preview = PreviewExport.inTempDir();
    private boolean groupedThreads;
    private String lookKey;
    private String themeKey;

    public MailFrame(MailStore store, UiSettings settings, LayoutPreset preset) {
        this.store = store;
        this.settings = settings;
        this.layout = preset;
        this.groupedThreads = "true".equals(settings.get("group.threads", "false"));
        this.lookKey = System.getProperty("mailclient.laf", settings.get("ui.laf", "auto"));
        if (!LookFeel.isAvailable(this.lookKey)) this.lookKey = "auto";
        this.themeKey = System.getProperty("mailclient.theme", settings.get("ui.theme", Theme.AUTO));
        Theme.set(this.themeKey);
        this.previewMode = System.getProperty("mailclient.preview", settings.get("ui.preview", "integrated"));

        setTitle("MailClient");
        setIconImages(loadAppIcons());
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

    /** Sumone-Logo (Ressource icons/app_<n>.png) in allen Fenstergößen. */
    private static java.util.List<Image> loadAppIcons() {
        java.util.List<Image> out = new java.util.ArrayList<>();
        for (int size : new int[]{16, 24, 32, 48, 128, 256}) {
            try (java.io.InputStream in = MailFrame.class.getResourceAsStream("/icons/app_" + size + ".png")) {
                if (in != null) {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
                    if (img != null) out.add(img);
                }
            } catch (Exception ignored) { }
        }
        return out;
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
        accountBox.setToolTipText(I18n.tr("account.switch"));
        accountBox.setPreferredSize(new Dimension(200, 28));
        accountBox.setMaximumSize(new Dimension(220, 28));
        if (accountBox.getItemListeners().length == 0) {
            accountBox.addActionListener(e -> onAccountSelected());
        }
        toolBar.add(accountBox);
        toolBar.addSeparator();
        JButton newBtn = new JButton(I18n.tr("toolbar.new"));
        JButton replyBtn = new JButton(I18n.tr("toolbar.reply"));
        JButton fwdBtn = new JButton(I18n.tr("toolbar.forward"));
        JButton readBtn = new JButton(I18n.tr("toolbar.read"));
        JButton delBtn = new JButton(I18n.tr("toolbar.delete"));
        newBtn.addActionListener(e -> openCompose());
        replyBtn.addActionListener(e -> replyToSelected());
        fwdBtn.addActionListener(e -> forwardSelected());
        readBtn.addActionListener(e -> markSelectedRead());
        delBtn.addActionListener(e -> deleteSelected());
        for (JButton b : new JButton[]{newBtn, replyBtn, fwdBtn, readBtn, delBtn}) {
            b.setFocusable(false);
            toolBar.add(b);
        }
        toolBar.add(Box.createHorizontalGlue());
        if (layout == LayoutPreset.OUTLOOK || layout == LayoutPreset.GMAIL) {
            searchField.setPreferredSize(new Dimension(280, 28));
            searchField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0xC8D2DC)),
                    BorderFactory.createEmptyBorder(2, 4, 2, 4)));
            toolBar.add(searchField);
        }
        toolBar.revalidate();
    }

    private JMenuBar buildMenus() {
        JMenuBar mb = new JMenuBar();
        setJMenuBar(mb);

        JMenu file = mb.add(new JMenu(I18n.tr("menu.file")));
        file.add(item(I18n.tr("menu.file.new"), this::openCompose));
        file.add(item(I18n.tr("menu.file.addaccount"), this::openAccountDialog));
        file.add(item(I18n.tr("menu.file.editaccount"), this::editAccount));
        file.add(item(I18n.tr("menu.file.deleteaccount"), this::deleteAccount));
        file.add(item(I18n.tr("menu.file.sync"), this::syncAllAccounts));
        file.addSeparator();
        file.add(item(I18n.tr("menu.file.datadir"), this::showDataDirDialog));
        file.addSeparator();
        file.add(item(I18n.tr("menu.file.quit"), this::dispose));

        JMenu edit = mb.add(new JMenu(I18n.tr("menu.edit")));
        edit.add(item(I18n.tr("menu.edit.reply"), this::replyToSelected));
        edit.add(item(I18n.tr("menu.edit.forward"), this::forwardSelected));
        edit.addSeparator();
        edit.add(item(I18n.tr("menu.edit.read"), this::markSelectedRead));
        edit.add(item(I18n.tr("menu.edit.delete"), this::deleteSelected));
        edit.add(item(I18n.tr("menu.edit.restore"), this::restoreSelected));
        edit.add(item(I18n.tr("menu.edit.emptytrash"), this::emptyTrash));

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
        JMenu themes = new JMenu(I18n.tr("menu.view.theme"));
        view.add(themes);
        ButtonGroup themeGroup = new ButtonGroup();
        for (String key : new String[]{Theme.LIGHT, Theme.DARK, Theme.AUTO}) {
            JRadioButtonMenuItem m = new JRadioButtonMenuItem(I18n.tr("theme." + key), key.equals(Theme.mode()));
            m.addActionListener(e -> switchTheme(key));
            themeGroup.add(m);
            themes.add(m);
        }
        JMenu looks = new JMenu(I18n.tr("menu.view.look"));
        view.add(looks);
        ButtonGroup lookGroup = new ButtonGroup();
        for (String key : new String[]{"auto", "gtk", "windows", "nimbus", "metal"}) {
            if (!LookFeel.isAvailable(key)) continue;
            JRadioButtonMenuItem m = new JRadioButtonMenuItem(I18n.tr("look." + key), key.equals(lookKey));
            m.addActionListener(e -> switchLookFeel(key));
            lookGroup.add(m);
            looks.add(m);
        }
        JMenu previews = new JMenu(I18n.tr("menu.view.preview"));
        view.add(previews);
        ButtonGroup previewGroup = new ButtonGroup();
        for (String key : new String[]{"integrated", "browser"}) {
            JRadioButtonMenuItem m = new JRadioButtonMenuItem(I18n.tr("preview." + key), key.equals(previewMode));
            m.addActionListener(e -> switchPreviewMode(key));
            previewGroup.add(m);
            previews.add(m);
        }
        view.add(item(I18n.tr("menu.view.openbrowser"), this::openSelectedInBrowser));
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
        reader.setBackground(Theme.surface());
        reader.setForeground(Theme.text());
        reader.setCaretColor(Theme.text());
        JScrollPane readerScroll = new JScrollPane(reader);
        readerScroll.setBorder(BorderFactory.createLineBorder(Theme.border()));
        securityBadge.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        securityBadge.setFont(securityBadge.getFont().deriveFont(Font.BOLD, 11f));
        readerScroll.setColumnHeaderView(securityBadge);
        buildAttachmentPanel(readerScroll);

        boolean previewMode = layout == LayoutPreset.FOXMAIL;
        messageTable.setRowHeight(previewMode ? 46 : 20);
        messageTable.setShowGrid(false);
        messageTable.setIntercellSpacing(new Dimension(0, 0));
        messageTable.setBackground(Theme.surface());
        messageTable.setForeground(Theme.text());
        messageTable.setSelectionBackground(Theme.header());
        messageTable.setSelectionForeground(Theme.text());
        messageTable.getTableHeader().setBackground(Theme.header());
        messageTable.getTableHeader().setForeground(Theme.text());
        folderList.setBackground(Theme.listBg());
        folderList.setForeground(Theme.text());
        folderList.setSelectionBackground(Theme.header());
        folderList.setSelectionForeground(Theme.text());
        accountHeader.setForeground(Theme.accent());
        statusLeft.setForeground(Theme.textSoft());
        statusRight.setForeground(Theme.textSoft());
        messageTable.getColumnModel().getColumn(4).setCellRenderer(new SpamPercentRenderer());
        messageTable.getColumnModel().getColumn(4).setPreferredWidth(64);
        messageTable.getTableHeader().setToolTipText(I18n.tr("col.spam.tip"));
        tableModel.setShowPreview(previewMode);

        JPanel rightCol = new JPanel(new BorderLayout());

        switch (layout) {
            case DEFAULT -> {
                JSplitPane inner = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, readerHost);
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
                JSplitPane inner = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, readerHost);
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
                JSplitPane vert = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topRow, readerHost);
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
                foldersScroll.getViewport().setBackground(Theme.listBg());
                JPanel leftCol = new JPanel(new BorderLayout());
                leftCol.setBackground(Theme.listBg());
                leftCol.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.border()));
                leftCol.add(accountHeader, BorderLayout.NORTH);
                leftCol.add(foldersScroll, BorderLayout.CENTER);
                JPanel headBar = new JPanel(new BorderLayout());
                headBar.setBackground(Theme.header());
                headBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                JLabel folderTitle = new JLabel(currentFolderDisplay == null ? "" : currentFolderDisplay);
                folderTitle.setFont(folderTitle.getFont().deriveFont(Font.BOLD, 13f));
                folderTitle.setForeground(Theme.text());
                headBar.add(folderTitle, BorderLayout.WEST);
                JLabel unreadHint = new JLabel(I18n.tr("foxmail.hint"));
                unreadHint.setForeground(Theme.textSoft());
                headBar.add(unreadHint, BorderLayout.EAST);
                JPanel listCol = new JPanel(new BorderLayout());
                listCol.add(headBar, BorderLayout.NORTH);
                listCol.add(tableScroll, BorderLayout.CENTER);
                JSplitPane inner = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listCol, readerHost);
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

    /** Konten in den Umschalter füllen; aktuelles Konto bleibt gewählt (M4-003). */
    private void refreshAccountBox(List<MailStore.StoredAccount> all) {
        populatingAccounts = true;
        try {
            String keep = currentAccountName;
            accountBox.removeAllItems();
            for (MailStore.StoredAccount a : all) {
                accountBox.addItem(a.name());
            }
            if (keep != null && all.stream().anyMatch(a -> a.name().equals(keep))) {
                accountBox.setSelectedItem(keep);
            } else if (!all.isEmpty()) {
                accountBox.setSelectedIndex(0);
            }
        } finally {
            populatingAccounts = false;
        }
    }

    private void loadData() throws SQLException {
        List<MailStore.StoredAccount> all = store.listAccounts();
        final String preferred = currentAccountName;
        String target = preferred != null && all.stream().anyMatch(a -> a.name().equals(preferred))
                ? preferred
                : (all.isEmpty() ? null : all.get(0).name());
        refreshAccountBox(all);
        if (target == null) {
            currentAccountName = null;
            accountId = -1;
            folderModel.clear();
            folders.clear();
            folderKeyByDisplay.clear();
            currentFolderDisplay = null;
            tableModel.setRows(new ArrayList<>(), I18n.language());
            accountHeader.setText(I18n.tr("account.none"));
            updateStatus();
            return;
        }
        loadAccount(target, all);
    }

    /** Ordner + Mails eines Kontos laden (Konto-Umschalter). */
    private void loadAccount(String accountName, List<MailStore.StoredAccount> all) throws SQLException {
        currentAccountName = accountName;
        accountId = store.accountIdByName(accountName);
        // Standard-Ordner sicherstellen — auch fuer Demo- und Bestandskonten (M4-008)
        for (String folder : new String[]{"Posteingang", "Gesendet", "Archiv", "Papierkorb"}) {
            store.getOrCreateFolder(accountId, folder);
        }
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
        MailStore.StoredAccount acc = all == null ? null
                : all.stream().filter(a -> a.name().equals(accountName)).findFirst().orElse(null);
        String host = acc == null || acc.host() == null ? "-" : acc.host();
        accountHeader.setText(I18n.tr("account.header", accountName, host));
        reloadMessages();
    }

    /** Konto-Umschalter: Wechsel lädt Ordner + Mails des gewählten Kontos. */
    private void onAccountSelected() {
        if (populatingAccounts) return;
        Object sel = accountBox.getSelectedItem();
        if (sel == null) return;
        String name = String.valueOf(sel);
        if (name.equals(currentAccountName)) return;
        try {
            loadAccount(name, store.listAccounts());
            updateStatus();
        } catch (SQLException e) {
            statusLeft.setText(I18n.tr("sync.fail", name, e.getMessage()));
        }
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
            // Suche laeuft in SQL (Platzhalter + Parameter ueber SearchQueryBuilder),
            // inklusive Feld-Praefixen from: / subject: / has:attachment.
            // Im Papierkorb zeigen wir die soft-geloeschten Mails (M4-008).
            String q = effectiveQuery();
            List<StoredMessage> rows;
            if (isTrashSelected()) {
                rows = store.listDeleted(folderId, 500, 0);
                if (!q.isBlank()) {
                    String needle = q.toLowerCase(Locale.ROOT);
                    rows = rows.stream().filter(m ->
                            (m.subject() != null && m.subject().toLowerCase(Locale.ROOT).contains(needle))
                                    || (m.fromName() != null && m.fromName().toLowerCase(Locale.ROOT).contains(needle))
                                    || (m.fromAddr() != null && m.fromAddr().toLowerCase(Locale.ROOT).contains(needle)))
                            .toList();
                }
            } else {
                rows = q.isBlank()
                        ? store.listMessages(folderId, 500, 0)
                        : store.search(folderId, q, 500);
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

    /** M4-006: Leiste unter dem Leser mit Anhaengen (Oeffnen = temporaer speichern und starten). */
    private void buildAttachmentPanel(JScrollPane readerScroll) {
        attachmentLabel.setFont(attachmentLabel.getFont().deriveFont(Font.BOLD, 11f));
        attachmentLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        attachmentList.setVisibleRowCount(3);
        attachmentList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof MailStore.StoredAttachment a) {
                    setText(a.fileName() + "  (" + humanSize(a.size()) + ")");
                    setToolTipText(a.mimeType() + (a.onDisk() ? "" : " · nur Metadaten"));
                }
                return this;
            }
        });
        attachmentList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) openSelectedAttachment();
            }
        });
        JButton openBtn = new JButton(I18n.tr("attach.open"));
        JButton saveBtn = new JButton(I18n.tr("attach.save"));
        openBtn.addActionListener(e -> openSelectedAttachment());
        saveBtn.addActionListener(e -> saveSelectedAttachment());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        buttons.add(openBtn);
        buttons.add(saveBtn);

        attachmentPanel.add(attachmentLabel, BorderLayout.NORTH);
        attachmentPanel.add(new JScrollPane(attachmentList), BorderLayout.CENTER);
        attachmentPanel.add(buttons, BorderLayout.SOUTH);
        attachmentPanel.setVisible(false);

        JPanel right = new JPanel(new BorderLayout());
        right.add(readerScroll, BorderLayout.CENTER);
        right.add(attachmentPanel, BorderLayout.SOUTH);
        readerHost = right;
    }

    /** Anhaenge der gewaehlten Mail laden (nur Metadaten aus der DB, kein Datei-IO auf der EDT). */
    private void loadAttachments(StoredMessage m) {
        attachmentModel.clear();
        try {
            for (MailStore.StoredAttachment a : store.listAttachments(m.id())) {
                attachmentModel.addElement(a);
            }
        } catch (SQLException e) {
            // Anhaenge sind optional: Fehler nicht in die UI durchreichen
        }
        int n = attachmentModel.getSize();
        attachmentLabel.setText(n == 0 ? "" : " " + I18n.tr("attach.count", n) + " ");
        attachmentPanel.setVisible(n > 0);
    }

    private MailStore.StoredAttachment selectedAttachment() {
        return attachmentList.getSelectedValue();
    }

    private void openSelectedAttachment() {
        MailStore.StoredAttachment a = selectedAttachment();
        if (a == null) { statusLeft.setText(I18n.tr("attach.noselection")); return; }
        new SwingWorker<java.nio.file.Path, Void>() {
            @Override protected java.nio.file.Path doInBackground() throws Exception {
                return writeTempAttachment(a);
            }
            @Override protected void done() {
                try {
                    java.nio.file.Path file = get();
                    if (file == null) { statusLeft.setText(I18n.tr("attach.nodesk")); return; }
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(file.toFile());
                        statusLeft.setText(I18n.tr("attach.opened", a.fileName()));
                    } else {
                        statusLeft.setText(I18n.tr("attach.noopen"));
                    }
                } catch (Exception e) {
                    statusLeft.setText(I18n.tr("attach.err", e.getMessage()));
                }
            }
        }.execute();
    }

    private void saveSelectedAttachment() {
        MailStore.StoredAttachment a = selectedAttachment();
        if (a == null) { statusLeft.setText(I18n.tr("attach.noselection")); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(a.fileName()));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File target = fc.getSelectedFile();
        new SwingWorker<byte[], Void>() {
            @Override protected byte[] doInBackground() throws Exception {
                return store.readAttachment(a);
            }
            @Override protected void done() {
                try {
                    byte[] data = get();
                    if (data == null) { statusLeft.setText(I18n.tr("attach.nodesk")); return; }
                    java.nio.file.Files.write(target.toPath(), data);
                    statusLeft.setText(I18n.tr("attach.saved", target.getAbsolutePath()));
                } catch (Exception e) {
                    statusLeft.setText(I18n.tr("attach.err", e.getMessage()));
                }
            }
        }.execute();
    }

    /** Anhang in ein Temp-Verzeichnis schreiben (fuer Desktop.open). */
    private java.nio.file.Path writeTempAttachment(MailStore.StoredAttachment a) throws Exception {
        byte[] data = store.readAttachment(a);
        if (data == null) return null;
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("mailclient-att-");
        java.nio.file.Path file = dir.resolve(a.fileName());
        java.nio.file.Files.write(file, data);
        return file;
    }

    private static String humanSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024.0));
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
                String clean = CidResolver.solveToFiles(sanitizer.sanitize(html), loadInlineImages(m), preview.dir(), String.valueOf(m.id()));
                String css = Theme.readerCss();
                reader.setText(css.isEmpty() ? clean : "<style>" + css + "</style>" + clean);
                if ("browser".equals(previewMode)) {
                    openPreviewInBrowser(clean);
                }
            } else {
                reader.setContentType("text/plain");
                reader.setText((text == null ? "" : text) + "\n\n— Vorschau: " + m.preview());
            }
            reader.setBackground(Theme.surface());
            reader.setForeground(Theme.text());
            reader.setCaretPosition(0);
            statusRight.setText(I18n.tr("status.selected", m.subject()));
            updateSecurityBadge(m);
            loadAttachments(m);
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

    // ---------- Aktionen: Compose, Antwort, Weiterleitung, Löschen ----------

    private StoredMessage selectedMessage() {
        int v = messageTable.getSelectedRow();
        return v < 0 ? null : tableModel.rowAt(messageTable.convertRowIndexToModel(v));
    }

    private long sentFolderId() {
        Long id = folders.get(I18n.tr("folder.sent"));
        return id == null ? 0L : id;
    }

    private void openCompose() {
        // M4-007: Absender + SMTP-Konto aus dem aktiven Konto ableiten
        String from = null;
        try {
            if (currentAccountName != null) {
                for (MailStore.StoredAccount a : store.listAccounts()) {
                    if (a.name().equals(currentAccountName)) { from = a.email(); break; }
                }
            }
        } catch (SQLException ignored) { }
        ComposeDialog.openNew(this, store, sentFolderId(), currentAccountName, from, this::reloadMessages);
    }

    /** M4-003: Konto-Eingabemaske öffnen; nach Speichern Statusmeldung. */
    private void openAccountDialog() {
        String name = AccountDialog.open(this, store);
        if (name != null) {
            currentAccountName = name;
            try {
                loadData();
            } catch (SQLException e) {
                statusLeft.setText(I18n.tr("account.err.save", e.getMessage()));
            }
            statusLeft.setText(I18n.tr("account.saved", name));
        }
    }

    /** Name des im Umschalter gewählten Kontos (oder null, wenn keins existiert). */
    private String selectedAccountName() {
        Object sel = accountBox.getSelectedItem();
        if (sel != null) return String.valueOf(sel);
        return currentAccountName;
    }

    /** M4-003: gewähltes Konto bearbeiten (Felder vorbefüllt, Speichern aktualisiert). */
    private void editAccount() {
        String name = selectedAccountName();
        if (name == null) {
            showInfo(I18n.tr("account.none"));
            return;
        }
        try {
            MailStore.StoredAccount acc = store.listAccounts().stream()
                    .filter(a -> a.name().equals(name)).findFirst().orElse(null);
            if (acc == null) {
                statusLeft.setText(I18n.tr("account.none"));
                return;
            }
            String saved = AccountDialog.openEdit(this, store, acc);
            if (saved != null) {
                currentAccountName = saved;
                loadData();
                statusLeft.setText(I18n.tr("account.saved", saved));
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, I18n.tr("account.err.save", e.getMessage()),
                    I18n.tr("account.title.edit"), JOptionPane.ERROR_MESSAGE);
        }
    }

    /** M4-003: gewähltes Konto löschen (Rückfrage) — Ordner/Mails gehen per CASCADE mit. */
    private void deleteAccount() {
        String name = selectedAccountName();
        if (name == null) {
            showInfo(I18n.tr("account.none"));
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this, I18n.tr("account.delete.confirm", name),
                I18n.tr("account.delete.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        try {
            store.deleteAccount(store.accountIdByName(name));
            if (name.equals(currentAccountName)) currentAccountName = null;
            loadData();
            updateStatus();
            statusLeft.setText(I18n.tr("account.deleted", name));
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, I18n.tr("account.err.delete", e.getMessage()),
                    I18n.tr("account.delete.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void replyToSelected() {
        withBodyOfSelected((m, body) ->
                ComposeDialog.openReply(this, store, sentFolderId(), m, body, this::reloadMessages));
    }

    private void forwardSelected() {
        withBodyOfSelected((m, body) ->
                ComposeDialog.openForward(this, store, sentFolderId(), m, body, this::reloadMessages));
    }

    /** Textkörper async laden (kein DB-IO auf der EDT), dann Dialog öffnen. */
    private void withBodyOfSelected(java.util.function.BiConsumer<StoredMessage, String> action) {
        StoredMessage m = selectedMessage();
        if (m == null) {
            statusRight.setText(I18n.tr("status.noselection"));
            return;
        }
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                String[] b = store.getBodies(m.id());
                if (b == null) return null;
                return b[0] != null && !b[0].isBlank() ? b[0] : b[1];
            }
            @Override protected void done() {
                try { action.accept(m, get()); }
                catch (Exception ignored) { action.accept(m, null); }
            }
        }.execute();
    }

    private void deleteSelected() {
        StoredMessage m = selectedMessage();
        if (m == null) {
            statusRight.setText(I18n.tr("status.noselection"));
            return;
        }
        boolean inTrash = isTrashSelected();
        if (inTrash) {
            int ok = JOptionPane.showConfirmDialog(this, I18n.tr("delete.confirm", m.subject()),
                    I18n.tr("delete.confirm.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return;
        }
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                if (inTrash) {
                    store.purge(m.id());
                } else {
                    store.setDeleted(m.id(), true);
                }
                return null;
            }
            @Override protected void done() {
                try { get(); }
                catch (Exception e) {
                    JOptionPane.showMessageDialog(MailFrame.this,
                            e.getMessage(), I18n.tr("toolbar.delete"), JOptionPane.ERROR_MESSAGE);
                    return;
                }
                statusLeft.setText(inTrash
                        ? I18n.tr("status.purged", m.subject())
                        : I18n.tr("status.deleted", m.subject()));
                reloadMessages();
            }
        }.execute();
    }

    /** Spam-Spalte als Prozentanzeige mit Ampelfarben (spec: 0–100, >60 Spam, <30 sauber). */
    private static final class SpamPercentRenderer extends javax.swing.table.DefaultTableCellRenderer {
        SpamPercentRenderer() { setHorizontalAlignment(SwingConstants.CENTER); }
        @Override public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            int score = value instanceof Integer i ? i : 0;
            setText(score + " %");
            if (!isSelected) {
                setForeground(score > 60 ? new Color(0xC0392B)
                        : score >= 30 ? new Color(0xB4650A) : new Color(0x2E7D32));
            }
            setToolTipText(I18n.tr("col.spam.tip"));
            return this;
        }
    }

    /** M4: Ablageort der Daten waehlen (portabel neben dem Jar oder im Benutzerprofil). */
    private void showDataDirDialog() {
        AppPaths.Env env = AppPaths.systemEnv();
        java.nio.file.Path current = AppPaths.resolveDataDir();
        String info = I18n.tr("datadir.current", current.toAbsolutePath().toString())
                + "\n\n" + I18n.tr("datadir.hint");
        Object[] options = {
                I18n.tr("datadir.portable"),
                I18n.tr("datadir.user"),
                I18n.tr("datadir.custom"),
                I18n.tr("datadir.open"),
                I18n.tr("account.cancel")
        };
        int choice = JOptionPane.showOptionDialog(this, info, I18n.tr("datadir.title"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[4]);
        try {
            switch (choice) {
                case 0 -> {
                    AppPaths.writeMode(env, AppPaths.MODE_PORTABLE);
                    dataDirNote(AppPaths.portableDir(env));
                }
                case 1 -> {
                    AppPaths.writeMode(env, AppPaths.MODE_USER);
                    dataDirNote(AppPaths.userDir(env));
                }
                case 2 -> {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    chooser.setDialogTitle(I18n.tr("datadir.custom"));
                    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                        java.nio.file.Path chosen = chooser.getSelectedFile().toPath().toAbsolutePath();
                        AppPaths.writeMode(env, chosen.toString());
                        dataDirNote(chosen);
                    }
                }
                case 3 -> {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(current.toFile());
                    } else {
                        statusLeft.setText(I18n.tr("preview.failed", "Desktop nicht verfuegbar"));
                    }
                }
                default -> { }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, I18n.tr("datadir.err", String.valueOf(e.getMessage())),
                    I18n.tr("datadir.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void dataDirNote(java.nio.file.Path target) {
        JOptionPane.showMessageDialog(this,
                I18n.tr("datadir.saved", target.toAbsolutePath().toString()),
                I18n.tr("datadir.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    /** M4-004: Vorschau-Modus umschalten (integriert / Standardbrowser), persistiert. */
    private void switchPreviewMode(String key) {
        this.previewMode = key;
        settings.put("ui.preview", key);
        statusLeft.setText(I18n.tr("preview." + key));
        StoredMessage m = selectedMessage();
        if (m != null) {
            onMessageSelected();
        }
    }

    /** Ersetzt cid:-Verweise durch eingebettete Bilder der Mail (nach dem Sanitizer aufrufen). */
    private List<CidResolver.InlineImage> loadInlineImages(StoredMessage m) throws SQLException {
        List<CidResolver.InlineImage> images = new ArrayList<>();
        for (MailStore.StoredAttachment att : store.listAttachments(m.id())) {
            if (att.contentId() == null || att.size() > 5_000_000) {
                continue;
            }
            try {
                byte[] data = store.readAttachment(att);
                if (data != null) {
                    images.add(new CidResolver.InlineImage(att.contentId(), att.mimeType(), data));
                }
            } catch (java.io.IOException e) {
                // Bild nicht lesbar: der Verweis verliert dann sein src
            }
        }
        return images;
    }

    /** Schreibt die sanitisierte Mail in eine temporaere Datei und oeffnet sie im Systembrowser. */
    private void openPreviewInBrowser(String sanitizedHtml) {
        try {
            var file = preview.write(sanitizedHtml);
            PreviewExport.openInBrowser(file);
            statusLeft.setText(I18n.tr("preview.opened", file.getFileName().toString()));
        } catch (Exception e) {
            statusLeft.setText(I18n.tr("preview.failed", String.valueOf(e.getMessage())));
        }
    }

    /** Menueaktion: ausgewaehlte Mail bewusst im Systembrowser zeigen. */
    private void openSelectedInBrowser() {
        StoredMessage m = selectedMessage();
        if (m == null) {
            statusRight.setText(I18n.tr("status.noselection"));
            return;
        }
        try {
            String[] bodies = store.getBodies(m.id());
            String html = bodies != null ? bodies[1] : null;
            String content = html != null && !html.isBlank()
                    ? CidResolver.inline(sanitizer.sanitize(html), loadInlineImages(m))
                    : "<pre>" + escapeHtml(bodies != null ? bodies[0] : "") + "</pre>";
            openPreviewInBrowser(content);
        } catch (SQLException e) {
            statusLeft.setText(I18n.tr("preview.failed", String.valueOf(e.getMessage())));
        }
    }

    private static String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** true, wenn der aktuell gewaehlte Ordner der Papierkorb ist. */
    private boolean isTrashSelected() {
        return "folder.trash".equals(folderKeyByDisplay.get(currentFolderDisplay));
    }

    /** M4-008: Mail aus dem Papierkorb zurueckholen. */
    private void restoreSelected() {
        StoredMessage m = selectedMessage();
        if (m == null) {
            statusRight.setText(I18n.tr("status.noselection"));
            return;
        }
        if (!isTrashSelected()) {
            statusLeft.setText(I18n.tr("status.nottrash"));
            return;
        }
        try {
            store.restore(m.id());
            statusLeft.setText(I18n.tr("status.restored", m.subject()));
            reloadMessages();
        } catch (SQLException e) {
            statusLeft.setText(I18n.tr("sync.fail", m.subject(), e.getMessage()));
        }
    }

    /** M4-008: Papierkorb des aktuellen Kontos endgueltig leeren. */
    private void emptyTrash() {
        Long folderId = currentFolderDisplay == null ? null : folders.get(currentFolderDisplay);
        if (folderId == null || !isTrashSelected()) {
            statusLeft.setText(I18n.tr("status.nottrash"));
            return;
        }
        try {
            int pending = store.countDeleted(folderId);
            if (pending == 0) {
                statusLeft.setText(I18n.tr("trash.empty.none"));
                return;
            }
            int ok = JOptionPane.showConfirmDialog(this, I18n.tr("trash.empty.confirm", pending),
                    I18n.tr("trash.empty.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return;
            int removed = store.purgeDeleted(folderId);
            statusLeft.setText(I18n.tr("status.trash.emptied", removed));
            reloadMessages();
        } catch (SQLException e) {
            statusLeft.setText(I18n.tr("sync.fail", "-", e.getMessage()));
        }
    }

    private List<StoredMessage> visibleRows() {
        List<StoredMessage> out = new ArrayList<>();
        for (int i = 0; i < messageTable.getRowCount(); i++) {
            out.add(tableModel.rowAt(messageTable.convertRowIndexToModel(i)));
        }
        return out;
    }

    /** M4-004: Badge aus den gespeicherten SPF/DKIM/DMARC-Werten der Mail setzen. */
    private void updateSecurityBadge(StoredMessage m) {
        try {
            String[] auth = store.authResults(m.id());
            SecurityBadge.Level level = SecurityBadge.evaluate(auth[0], auth[1], auth[2]);
            securityBadge.setText(" " + I18n.tr("security.badge", SecurityBadge.label(level)) + " ");
            securityBadge.setForeground(badgeColor(level));
            securityBadge.setToolTipText(SecurityBadge.tooltip(level, auth[0], auth[1], auth[2]));
            securityBadge.setOpaque(true);
            securityBadge.setBackground(Theme.header());
        } catch (SQLException e) {
            securityBadge.setText("");
            securityBadge.setToolTipText(null);
        }
    }

    private static Color badgeColor(SecurityBadge.Level level) {
        return switch (level) {
            case GREEN -> new Color(0x2E7D32);
            case YELLOW -> new Color(0xB4650A);
            case RED -> new Color(0xC62828);
            case UNKNOWN -> new Color(0x607D8B);
        };
    }

    private void updateStatus() {
        int n = tableModel.getRowCount();
        statusLeft.setText(I18n.tr("status.count", n, currentFolderDisplay == null ? "-" : currentFolderDisplay));
    }

    /**
     * M4-003/M4-008: Alle Konten mit echten Zugangsdaten im Hintergrund abrufen.
     * Netzwerk-IO läuft im SwingWorker, die Statuszeile zeigt Fortschritt und Ergebnis.
     */
    private void syncAllAccounts() {
        List<MailStore.StoredAccount> accounts;
        try {
            accounts = store.listAccounts().stream().filter(SyncService::hasCredentials).toList();
        } catch (SQLException e) {
            statusLeft.setText(I18n.tr("sync.fail", "-", e.getMessage()));
            return;
        }
        if (accounts.isEmpty()) {
            statusLeft.setText(I18n.tr("sync.noaccounts"));
            return;
        }
        statusLeft.setText(I18n.tr("sync.running"));
        final SyncService service = new SyncService();
        new SwingWorker<List<SyncService.SyncResult>, Void>() {
            @Override
            protected List<SyncService.SyncResult> doInBackground() {
                List<SyncService.SyncResult> out = new ArrayList<>();
                for (MailStore.StoredAccount acc : accounts) {
                    out.add(service.syncAccount(store, acc));
                }
                return out;
            }

            @Override
            protected void done() {
                StringBuilder sb = new StringBuilder();
                try {
                    for (SyncService.SyncResult r : get()) {
                        if (sb.length() > 0) sb.append("  ·  ");
                        sb.append(r.ok()
                                ? I18n.tr("sync.done", r.account(), r.fetched())
                                : I18n.tr("sync.fail", r.account(), r.error()));
                    }
                } catch (Exception e) {
                    sb.setLength(0);
                    sb.append(I18n.tr("sync.fail", "-", String.valueOf(e.getMessage())));
                }
                statusLeft.setText(sb.toString());
                reloadMessages();
            }
        }.execute();
    }

    private void showInfo(String what) {
        JOptionPane.showMessageDialog(this, what + ": Demo-Shell — " + I18n.tr("about.text"));
    }

    /** Farbschema (Hell/Dunkel/Automatisch) zur Laufzeit wechseln, persistieren, neu aufbauen. */
    public void switchTheme(String key) {
        themeKey = key;
        Theme.set(key);
        settings.put("ui.theme", key);
        SwingUtilities.updateComponentTreeUI(this);
        applyLayout();
        reloadMessages();
        int v = messageTable.getSelectedRow();
        if (v >= 0) onMessageSelected();
        statusRight.setText(I18n.tr("theme.active", I18n.tr("theme." + Theme.mode())));
        repaint();
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

    /** Für Screenshot-Läufe: Compose-Aktionen auslösbar machen. */
    public void triggerCompose(String mode) {
        switch (mode) {
            case "reply" -> replyToSelected();
            case "forward" -> forwardSelected();
            default -> openCompose();
        }
    }

    /** Für Screenshot-Läufe: Konto-Dialog öffnen (modal, Timer für Shot vorher starten). */
    public void triggerAccountDialog() {
        openAccountDialog();
    }

    /** Für Screenshot-Läufe: Layout + Sprache von außen setzen. */
    public void configureFor(String layoutName, String lang) {
        LayoutPreset p = LayoutPreset.fromString(layoutName);
        if (!p.name().equals(layout.name())) switchLayout(p);
        if (lang != null && !lang.equals(I18n.language())) switchLanguage(lang);
    }
}
