package de.metis.mail.desktop;

import de.metis.mail.db.MailStore;
import de.metis.mail.protocol.ImapMailClient;
import de.metis.mail.protocol.MailClientConfig;
import de.metis.mail.protocol.Pop3MailClient;
import de.metis.mail.protocol.SmtpMailClient;

import javax.swing.*;
import java.awt.*;
import java.sql.SQLException;

/**
 * M4-003: Eingabemaske zum Anlegen eines E-Mail-Kontos.
 * Felder: Kontoname, E-Mail-Adresse, Protokoll (IMAP/POP3), Empfangs-Server/Port/
 * Verschlüsselung, SMTP-Server/Port/Verschlüsselung, Benutzer, Kennwort.
 * Verbindungstest läuft im SwingWorker (kein IO auf der EDT).
 */
public final class AccountDialog extends JDialog {

    private enum Sec {
        SSL("account.sec.ssl"), STARTTLS("account.sec.starttls"), NONE("account.sec.none");
        final String key;
        Sec(String key) { this.key = key; }
        @Override public String toString() { return I18n.tr(key); }
    }

    private final JTextField nameField = new JTextField(24);
    private final JTextField emailField = new JTextField(24);
    private final JComboBox<String> protocolBox = new JComboBox<>(new String[]{"IMAP", "POP3"});
    private final JTextField inHost = new JTextField(24);
    private final JTextField inPort = new JTextField(5);
    private final JComboBox<Sec> inSec = new JComboBox<>(Sec.values());
    private final JTextField outHost = new JTextField(24);
    private final JTextField outPort = new JTextField(5);
    private final JComboBox<Sec> outSec = new JComboBox<>(Sec.values());
    private final JTextField userField = new JTextField(24);
    private final JPasswordField passField = new JPasswordField(24);
    private final JButton testBtn = new JButton(I18n.tr("account.test"));
    private final JButton saveBtn = new JButton(I18n.tr("account.save"));
    private final JTextArea resultArea = new JTextArea(4, 40);

    private final MailStore store;
    private String savedName;

    private AccountDialog(Window owner, MailStore store) {
        super(owner, I18n.tr("account.title"), Dialog.ModalityType.APPLICATION_MODAL);
        this.store = store;

        inSec.setSelectedItem(Sec.SSL);
        outSec.setSelectedItem(Sec.SSL);
        applyDefaults();

        // Standard-Ports folgen Protokoll + Verschlüsselung, solange der Nutzer nicht selbst tippt
        protocolBox.addActionListener(e -> applyDefaults());
        inSec.addActionListener(e -> applyDefaults());
        outSec.addActionListener(e -> applyDefaults());

        resultArea.setEditable(false);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setOpaque(false);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 8, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        row = addRow(form, c, row, I18n.tr("account.name"), nameField);
        row = addRow(form, c, row, I18n.tr("account.email"), emailField);
        row = addRow(form, c, row, I18n.tr("account.protocol"), protocolBox);
        addSeparator(form, c, row++, I18n.tr("account.tab.incoming"));
        row = addRow(form, c, row, I18n.tr("account.host"), inHost);
        row = addRow(form, c, row, I18n.tr("account.port"), inPort);
        row = addRow(form, c, row, I18n.tr("account.security"), inSec);
        addSeparator(form, c, row++, I18n.tr("account.tab.outgoing"));
        row = addRow(form, c, row, I18n.tr("account.host"), outHost);
        row = addRow(form, c, row, I18n.tr("account.port"), outPort);
        row = addRow(form, c, row, I18n.tr("account.security"), outSec);
        addSeparator(form, c, row++, I18n.tr("account.user"));
        row = addRow(form, c, row, I18n.tr("account.user"), userField);
        row = addRow(form, c, row, I18n.tr("account.password"), passField);

        JButton preset = new JButton(I18n.tr("account.preset.strato"));
        preset.addActionListener(e -> applyStratoPreset());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(preset);
        buttons.add(testBtn);
        buttons.add(new JSeparator(JSeparator.VERTICAL));
        JButton cancel = new JButton(I18n.tr("account.cancel"));
        buttons.add(cancel);
        buttons.add(saveBtn);

        JPanel south = new JPanel(new BorderLayout());
        JScrollPane resultScroll = new JScrollPane(resultArea);
        resultScroll.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        south.add(resultScroll, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        cancel.addActionListener(e -> dispose());
        testBtn.addActionListener(e -> testConnection());
        saveBtn.addActionListener(e -> save());

        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    /** Öffnet den Dialog modal; liefert den gespeicherten Kontonamen oder null (abgebrochen). */
    public static String open(Window owner, MailStore store) {
        AccountDialog d = new AccountDialog(owner, store);
        d.setVisible(true);
        return d.savedName;
    }

    // ---------- Aufbau-Helfer ----------

    private static int addRow(JPanel p, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        p.add(field, c);
        return row + 1;
    }

    private static void addSeparator(JPanel p, GridBagConstraints c, int row, String title) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JLabel l = new JLabel("<html><b>" + title + "</b><hr size=1></html>");
        l.setForeground(Theme.accent());
        p.add(l, c);
        c.gridwidth = 1;
    }

    /** Port-Vorschläge aus Protokoll + Verschlüsselung (RFC-Standardwerte). */
    private void applyDefaults() {
        boolean imap = "IMAP".equals(protocolBox.getSelectedItem());
        Sec is = (Sec) inSec.getSelectedItem();
        Sec os = (Sec) outSec.getSelectedItem();
        inPort.setText(String.valueOf(is == Sec.SSL ? (imap ? 993 : 995) : (imap ? 143 : 110)));
        outPort.setText(String.valueOf(os == Sec.SSL ? 465 : os == Sec.STARTTLS ? 587 : 25));
    }

    /** Strato-Voreinstellung (Georgs Provider): IMAP 993 SSL, SMTP 465 SSL. */
    private void applyStratoPreset() {
        protocolBox.setSelectedItem("IMAP");
        inHost.setText("imap.strato.de");
        inSec.setSelectedItem(Sec.SSL);
        outHost.setText("smtp.strato.de");
        outSec.setSelectedItem(Sec.SSL);
        String email = emailField.getText().trim();
        if (userField.getText().isBlank()) userField.setText(email);
    }

    // ---------- Logik ----------

    private static MailClientConfig.Security secOf(Sec s) {
        return switch (s) {
            case SSL -> MailClientConfig.Security.SSL;
            case STARTTLS -> MailClientConfig.Security.STARTTLS;
            case NONE -> MailClientConfig.Security.NONE;
        };
    }

    private int port(JTextField f) {
        try {
            int p = Integer.parseInt(f.getText().trim());
            if (p < 1 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void testConnection() {
        String user = userField.getText().trim();
        String pass = new String(passField.getPassword());
        boolean imap = "IMAP".equals(protocolBox.getSelectedItem());
        int ip = port(inPort), op = port(outPort);
        if (inHost.getText().isBlank() || outHost.getText().isBlank() || user.isBlank() || ip < 0 || op < 0) {
            resultArea.setText(I18n.tr("account.err.required"));
            return;
        }
        MailClientConfig inCfg = new MailClientConfig(inHost.getText().trim(), ip,
                user, pass, secOf((Sec) inSec.getSelectedItem()));
        MailClientConfig outCfg = new MailClientConfig(outHost.getText().trim(), op,
                user, pass, secOf((Sec) outSec.getSelectedItem()));

        testBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        resultArea.setText(I18n.tr("account.testing"));
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                StringBuilder sb = new StringBuilder();
                // Empfang
                try {
                    if (imap) {
                        try (ImapMailClient c = new ImapMailClient(inCfg)) {
                            c.connect();
                            sb.append(I18n.tr("account.test.ok", "IMAP " + inCfg.host() + ":" + inCfg.port())).append('\n');
                        }
                    } else {
                        try (Pop3MailClient c = new Pop3MailClient(inCfg)) {
                            c.connect();
                            sb.append(I18n.tr("account.test.ok", "POP3 " + inCfg.host() + ":" + inCfg.port())).append('\n');
                        }
                    }
                } catch (Exception e) {
                    sb.append(I18n.tr("account.test.fail", imap ? "IMAP" : "POP3",
                            String.valueOf(e.getMessage()))).append('\n');
                }
                // Versand
                try (SmtpMailClient c = new SmtpMailClient(outCfg)) {
                    c.connect();
                    sb.append(I18n.tr("account.test.ok", "SMTP " + outCfg.host() + ":" + outCfg.port()));
                } catch (Exception e) {
                    sb.append(I18n.tr("account.test.fail", "SMTP", String.valueOf(e.getMessage())));
                }
                return sb.toString();
            }
            @Override protected void done() {
                try { resultArea.setText(get()); }
                catch (Exception e) { resultArea.setText(I18n.tr("account.test.fail", "?", e.getMessage())); }
                testBtn.setEnabled(true);
                saveBtn.setEnabled(true);
            }
        }.execute();
    }

    private void save() {
        String name = nameField.getText().trim();
        String email = emailField.getText().trim();
        String user = userField.getText().trim();
        int ip = port(inPort), op = port(outPort);
        if (name.isBlank() || email.isBlank() || inHost.getText().isBlank() || user.isBlank() || ip < 0 || op < 0) {
            JOptionPane.showMessageDialog(this, I18n.tr("account.err.required"),
                    I18n.tr("account.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            long id = store.createAccount(name, email,
                    ((String) protocolBox.getSelectedItem()).toLowerCase(),
                    inHost.getText().trim(), ip, ((Sec) inSec.getSelectedItem()).name().toLowerCase(),
                    outHost.getText().trim(), op, ((Sec) outSec.getSelectedItem()).name().toLowerCase(),
                    user, new String(passField.getPassword()));
            // Standard-Ordner wie im Demo-Bestand: Baum zeigt Posteingang/Gesendet/Archiv/Papierkorb
            for (String folder : new String[]{"Posteingang", "Gesendet", "Archiv", "Papierkorb"}) {
                store.getOrCreateFolder(id, folder);
            }
            savedName = name;
            dispose();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, I18n.tr("account.err.save", e.getMessage()),
                    I18n.tr("account.title"), JOptionPane.ERROR_MESSAGE);
        }
    }
}
