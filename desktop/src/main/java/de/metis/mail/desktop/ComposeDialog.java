package de.metis.mail.desktop;

import de.metis.mail.db.MailStore;
import de.metis.mail.db.StoredMessage;
import de.metis.mail.db.SyncItem;
import de.metis.mail.model.MailMessage;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Schlanker Compose-Dialog: Neue Mail, Antwort, Weiterleitung.
 * Versand: lokal in den Gesendet-Ordner (SMTP-Versand kommt mit Konto-Anbindung, M4-004).
 */
public final class ComposeDialog extends JDialog {

    private final JTextField toField = new JTextField(30);
    private final JTextField subjectField = new JTextField(30);
    private final JTextArea bodyArea = new JTextArea(14, 50);
    private MailStore store;
    private long sentFolderId;
    private Runnable onSaved;

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
        bodyScroll.setBorder(BorderFactory.createLineBorder(new Color(0xD4dbe2)));
        grid.add(bodyScroll, c);

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

    // ---------- Versand (lokal) ----------

    private void send() {
        String to = toField.getText().trim();
        String subject = subjectField.getText().trim();
        if (to.isEmpty() || subject.isEmpty()) {
            JOptionPane.showMessageDialog(this, I18n.tr("compose.err"),
                    getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        String body = bodyArea.getText();
        setEnabled(false);

        SwingWorker<Void, Void> w = new SwingWorker<>() {
            @Override protected Void doInBackground() throws Exception {
                MailMessage msg = MailMessage.builder()
                        .from("Georg", "g_seher@sumone.eu")
                        .to("", to)
                        .subject(subject)
                        .sentDate(Instant.now())
                        .addHeader("Message-ID", "<" + UUID.randomUUID() + "@mailclient.local>")
                        .plainTextBody(body)
                        .setUnseen(false)
                        .build();
                store.upsertMessages(sentFolderId, List.of(new SyncItem(msg, 0)));
                return null;
            }

            @Override protected void done() {
                try {
                    get();
                    if (onSaved != null) onSaved.run();
                    dispose();
                } catch (Exception ex) {
                    setEnabled(true);
                    JOptionPane.showMessageDialog(ComposeDialog.this,
                            ex.getMessage(), getTitle(), JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        w.execute();
    }
}
