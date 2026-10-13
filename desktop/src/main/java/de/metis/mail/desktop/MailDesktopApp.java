package de.metis.mail.desktop;

import de.metis.mail.db.MailStore;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Einstiegspunkt der Desktop-Shell (M4).
 * Datenhaltung: SQLite unter ~/MailClient-Daten; Demo-Bestand wird beim
 * ersten Start befüllt, bis der echte IMAP/POP3-Sync (M1/M3) angebunden wird.
 */
public final class MailDesktopApp {

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(System.getProperty("user.home"), "MailClient-Daten");
        Files.createDirectories(dataDir);
        String jdbc = "jdbc:sqlite:" + dataDir.resolve("mail.sqlite");

        UiSettings settings = new UiSettings(jdbc);
        try {
            LookFeel.apply(System.getProperty("mailclient.laf", settings.get("ui.laf", "auto")));
        } catch (Exception fallback) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
        }
        String lang = System.getProperty("mailclient.lang", settings.get("ui.language", "de"));
        I18n.setLanguage(lang);

        LayoutPreset preset = LayoutPreset.fromString(
                System.getProperty("mailclient.layout", settings.get("ui.layout", "DEFAULT")));

        MailStore store = new MailStore(jdbc, dataDir.resolve("attachments"));
        try {
            store.accountIdByName("Georg Privat");
        } catch (Exception noAccount) {
            DemoData.seed(store);
        }

        SwingUtilities.invokeLater(() -> {
            MailFrame frame = new MailFrame(store, settings, preset);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) {
                    settings.close();
                    try { store.close(); } catch (Exception ignored) { }
                }
            });
            frame.setVisible(true);
            String shot = System.getProperty("mailclient.shot");
            if (shot != null) {
                Timer t = new Timer(1600, e -> {
                    frame.selectFirstMessage();
                    Timer t2 = new Timer(1400, e2 -> {
                        String compose = System.getProperty("mailclient.compose");
                        String dialog = System.getProperty("mailclient.dialog");
                        if (compose == null && dialog == null) {
                            grab(frame, shot);
                            System.exit(0);
                        }
                        // Timer für den Dialog-Schuss VOR dem modalen Öffnen starten
                        // (modale Dialoge blockieren den aufrufenden Code, Timer laufen auf der EDT weiter).
                        String suffix = compose != null ? compose : dialog;
                        Timer t3 = new Timer(3000, e3 -> {
                            grab(frame, shot.replace(".png", "_" + suffix + ".png"));
                            System.exit(0);
                        });
                        t3.setRepeats(false);
                        t3.start();
                        grab(frame, shot);
                        if (compose != null) frame.triggerCompose(compose);
                        else frame.triggerAccountDialog();
                    });
                    t2.setRepeats(false);
                    t2.start();
                });
                t.setRepeats(false);
                t.start();
            }
        });
    }

    private static void grab(MailFrame frame, String path) {
        try {
            Robot r = new Robot();
            BufferedImage img = r.createScreenCapture(new Rectangle(0, 0, 1280, 800));
            javax.imageio.ImageIO.write(img, "png", new File(path));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
