package de.metis.mail.desktop;

import de.metis.mail.db.MailStore;
import de.metis.mail.db.StoredMessage;
import de.metis.mail.db.SyncItem;
import de.metis.mail.model.MailMessage;
import de.metis.mail.security.AttachmentPolicy;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Schlanker Compose-Dialog: Neue Mail, Antwort, Weiterleitung.
 * Versand (M4-007): SMTP ueber das gewaehlte Konto (de.metis.mail.protocol.SmtpMailClient); ohne hinterlegte SMTP-Daten bleibt der lokale Ablage-Weg in den Gesendet-Ordner erhalten. Anhaenge durchlaufen AttachmentPolicy; gesperrte Endungen werden abgelehnt.
 */
public final class ComposeDialog extends JDialog {

    private final JTextField toField = new JTextField(30);
    private final JTextField subjectField = new JTextField(30);
    private final JTextArea bodyArea = new JTextArea(14, 50);
    private MailStore store;
    private long sentFolderId;
    private String accountName;
    private String fromAddress;
    private Runnable onSaved;

    /** M4-007: Anhaenge des Entwurfs (Name, MIME, Groesse, Bytes). */
    private final java.util.List<MailSender.OutgoingAttachment> attachments = new ArrayList<>();
    private final DefaultListModel<String> attachmentModel = new DefaultListModel<>();
    private final JList<String> attachmentList = new JList<>(attachmentModel);

    private ComposeDialog(Window owner, String title) {
        super(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 8, 4, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = 0;
        grid.add(new JLabel(I18n.tr("compose.to")), c);
        c.gridx = 1; c.weightx = 1;
        grid.add(toField, c);
        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        grid.add(new JLabel(I18n.tr("compose.subject")), c);
        c.gridx = 1; c.weightx = 1;
        grid.add(subjectField, c);
        c.gridx = 0; c.gridy = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        grid.add(new JLabel(I18n.tr("compose.body")), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.BOTH; c.weighty = 1;
        JScrollPane bodyScroll = new JScrollPane(bodyArea);
        bodyScroll.setBorder(BorderFactory.createLineBorder(Theme.border()));
        grid.add(bodyScroll, c);

        // M4-007: Anhaenge
        c.gridx = 0; c.gridy = 3; c.weightx = 0; c.weighty = 0; c.fill = GridBagConstraints.NONE;
        grid.add(new JLabel(I18n.tr("compose.attachments")), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        attachmentList.setVisibleRowCount(3);
        attachmentList.setBorder(BorderFactory.createLineBorder(Theme.border()));
        JScrollPane attScroll = new JScrollPane(attachmentList);
        attScroll.setPreferredSize(new Dimension(360, 66));
        JButton addAtt = new JButton(I18n.tr("compose.attach.add"));
        JButton removeAtt = new JButton(I18n.tr("compose.attach.remove"));
        addAtt.addActionListener(e -> chooseAttachments());
        removeAtt.addActionListener(e -> removeSelectedAttachment());
        JPanel attButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        attButtons.add(addAtt);
        attButtons.add(removeAtt);
        JPanel attPanel = new JPanel(new BorderLayout());
        attPanel.add(attScroll, BorderLayout.CENTER);
        attPanel.add(attButtons, BorderLayout.SOUTH);
        grid.add(attPanel, c);

        JButton send = new JButton(I18n.tr("compose.send"));
        JButton cancel = new JButton(I18n.tr("compose.cancel"));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(send);

        setLayout(new BorderLayout());
        add(grid, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        cancel.addActionListener(e -> dispose());
        send.addActionListener(e -> send());
        pack();
        setLocationRelativeTo(owner);
    }

    // ---------- API für MailFrame ----------

    public static void openNew(Window owner, MailStore store, long sentFolderId, Runnable onSaved) {
        ComposeDialog d = create(owner, store, sentFolderId, onSaved, I18n.tr("compose.new"));
        d.setVisible(true);
    }

    /** Zusatz-Overload mit Konto-Kontext (M4-007): Absender + SMTP-Konto. */
    public static void openNew(Window owner, MailStore store, long sentFolderId,
                               String accountName, String fromAddress, Runnable onSaved) {
        ComposeDialog d = create(owner, store, sentFolderId, onSaved, I18n.tr("compose.new"));
        d.accountName = accountName;
        d.fromAddress = fromAddress;
        d.setVisible(true);
    }

    public static void openReply(Window owner, MailStore store, long sentFolderId,
                                 StoredMessage original, String originalBody, Runnable onSaved) {
        ComposeDialog d = create(owner, store, sentFolderId, onSaved, I18n.tr("compose.reply"));
        d.toField.setText(original.fromAddr() == null ? "" : original.fromAddr());
        String subj = original.subject() == null ? "" : original.subject();
        d.subjectField.setText(subj.startsWith("Re:") ? subj : "Re: " + subj);
        d.bodyArea.setText("\n\n> " + (originalBody == null ? original.preview() : originalBody)
                .replace("\n", "\n> "));
        d.setVisible(true);
    }

    public static void openForward(Window owner, MailStore store, long sentFolderId,
                                   StoredMessage original, String originalBody, Runnable onSaved) {
        ComposeDialog d = create(owner, store, sentFolderId, onSaved, I18n.tr("compose.forward"));
        String subj = original.subject() == null ? "" : original.subject();
        d.subjectField.setText(subj.startsWith("Fw:") ? subj : "Fw: " + subj);
        d.bodyArea.setText("\n\n—— " + I18n.tr("compose.forward.headline") + " ——\n"
                + "Von: " + original.fromName() + " <" + original.fromAddr() + ">\n"
                + "Betreff: " + subj + "\n\n"
                + (originalBody == null ? original.preview() : originalBody));
        d.setVisible(true);
    }

    private static ComposeDialog create(Window owner, MailStore store, long sentFolderId,
                                        Runnable onSaved, String title) {
        ComposeDialog d = new ComposeDialog(owner, title);
        d.store = store;
        d.sentFolderId = sentFolderId;
        d.onSaved = onSaved;
        return d;
    }

    // ---------- Anhaenge (M4-007) ----------

    /** Dateiauswahl + Sicherheitspruefung; gesperrte Endungen kommen nicht in den Entwurf. */
    private void chooseAttachments() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle(I18n.tr("compose.attach.choose"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        AttachmentPolicy policy = AttachmentPolicy.createDefault();
        StringBuilder rejected = new StringBuilder();
        for (File f : fc.getSelectedFiles()) {
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                AttachmentPolicy.Result verdict = policy.checkAttachment(f.getName(), data);
                if (verdict.isBlocked()) {
                    rejected.append(f.getName()).append(": ").append(verdict.reason()).append(System.lineSeparator());
                    continue;
                }
                String mime = probeMimeType(f);
                MailSender.OutgoingAttachment pa = new MailSender.OutgoingAttachment(f.getName(), mime, data);
                attachments.add(pa);
                attachmentModel.addElement(f.getName() + "  (" + humanSize(data.length) + ")");
            } catch (IOException ex) {
                rejected.append(f.getName()).append(": ").append(ex.getMessage()).append(System.lineSeparator());
            }
        }
        if (rejected.length() > 0) {
            JOptionPane.showMessageDialog(this, rejected.toString(),
                    I18n.tr("compose.attach.blocked"), JOptionPane.WARNING_MESSAGE);
        }
    }

    private void removeSelectedAttachment() {
        int[] idx = attachmentList.getSelectedIndices();
        for (int i = idx.length - 1; i >= 0; i--) {
            attachmentModel.remove(i);
            attachments.remove(i);
        }
    }

    private static String probeMimeType(File f) {
        try {
            String t = Files.probeContentType(f.toPath());
            return t != null ? t : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static String humanSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }

    // ---------- Versand (M4-007: SMTP, sonst lokal) ----------

    private void send() {
        String to = toField.getText().trim();
        String subject = subjectField.getText().trim();
        if (to.isEmpty() || subject.isEmpty()) {
            JOptionPane.showMessageDialog(this, I18n.tr("compose.err"),
                    getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        String body = bodyArea.getText();
        String from = fromAddress;
        String acct = accountName;
        setEnabled(false);

        SwingWorker<MailSender.SendResult, Void> w = new SwingWorker<>() {
            @Override protected MailSender.SendResult doInBackground() {
                // 1. SMTP-Versand versuchen (falls Konto SMTP-Daten hat)
                MailSender.SendResult res = acct == null
                        ? MailSender.SendResult.localOnly()
                        : new MailSender(store, acct).send(from, to, subject, body, attachments);

                // 2. Immer in den lokalen Gesendet-Ordner spiegeln
                MailMessage.Builder b = MailMessage.builder();
                if (from != null && !from.isBlank()) b.from("", from);
                for (String addr : to.split("[,;]")) {
                    if (!addr.trim().isEmpty()) b.to("", addr.trim());
                }
                b.subject(subject)
                        .sentDate(Instant.now())
                        .addHeader("Message-ID", "<" + UUID.randomUUID() + "@mailclient.local>")
                        .plainTextBody(body)
                        .setUnseen(false);
                for (MailSender.OutgoingAttachment att : attachments) {
                    b.addAttachment(att.fileName(), att.mimeType(), att.data().length, att.data());
                }
                try {
                    store.upsertMessages(sentFolderId, List.of(new SyncItem(b.build(), 0)));
                } catch (Exception ignored) {
                    // lokale Spiegelung darf den Versand nicht scheitern lassen
                }
                return res;
            }

            @Override protected void done() {
                MailSender.SendResult res;
                try {
                    res = get();
                } catch (Exception ex) {
                    setEnabled(true);
                    JOptionPane.showMessageDialog(ComposeDialog.this,
                            ex.getMessage(), getTitle(), JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (!res.ok()) {
                    setEnabled(true);
                    JOptionPane.showMessageDialog(ComposeDialog.this,
                            I18n.tr("compose.smtp.err", res.error()),
                            getTitle(), JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (onSaved != null) onSaved.run();
                dispose();
            }
        };
        w.execute();
    }
}
