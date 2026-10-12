package de.metis.mail.desktop;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Properties;

/**
 * Internationalisierung: alle UI-Texte kommen aus Sprachdateien.
 * Suchreihenfolge: 1.) externe Datei ./lang/&lt;code&gt;.properties (ohne Build tauschbar),
 * 2.) Klasspath-Ressource /lang/&lt;code&gt;.properties (mitgelieferter Default).
 * Fehlende Schlüssel werden SICHTBAR markiert («key»), damit unübersetzte Texte auffallen.
 */
public final class I18n {

    private static Properties props = new Properties();
    private static String language = "de";

    private I18n() { }

    public static void setLanguage(String code) {
        language = code;
        props = load(code);
    }

    public static String language() { return language; }

    /** Übersetzt; args für MessageFormat-Platzhalter {0}, {1} ... */
    public static String tr(String key, Object... args) {
        String raw = props.getProperty(key);
        if (raw == null) return "\u00ab" + key + "\u00bb";
        return args.length == 0 ? raw : MessageFormat.format(raw, args);
    }

    private static Properties load(String code) {
        Properties p = new Properties();
        Path external = Path.of("lang", code + ".properties");
        if (Files.isRegularFile(external)) {
            try (InputStream in = new FileInputStream(external.toFile())) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                return p;
            } catch (IOException e) {
                System.err.println("I18n: externe Sprachdatei " + external + " lesbar? Fehler: " + e);
            }
        }
        try (InputStream in = I18n.class.getResourceAsStream("/lang/" + code + ".properties")) {
            if (in != null) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                return p;
            }
        } catch (IOException e) {
            System.err.println("I18n: Ressourcen laden fehlgeschlagen: " + e);
        }
        System.err.println("I18n: keine Sprachdatei für '" + code + "' gefunden");
        return p;
    }
}
