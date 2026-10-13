package de.metis.mail.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Ablageort der Daten (M4): Portabel neben dem Jar oder im Benutzerverzeichnis des
 * Betriebssystems.
 *
 * <p>Aufloesung in dieser Reihenfolge:
 * <ol>
 *   <li>System-Property {@code -Dmailclient.data=<Pfad>} (explizit, gewinnt immer)</li>
 *   <li>Konfigdatei {@code location.txt} (Modus {@code portable}/{@code user} oder absoluter Pfad)</li>
 *   <li>Marker {@code portable.txt} neben dem Jar -&gt; {@code <Jardir>/MailClient-Daten}</li>
 *   <li>bestehendes {@code ~/MailClient-Daten} (Abwaertskompatibilitaet)</li>
 *   <li>OS-Standard: Windows {@code %APPDATA%\MailClient}, macOS {@code ~/Library/Application Support/MailClient},
 *       Linux {@code ~/.local/share/mailclient}</li>
 * </ol>
 */
public final class AppPaths {

    public static final String PROP_DATA = "mailclient.data";
    public static final String PROP_PORTABLE = "mailclient.portable";
    public static final String MODE_PORTABLE = "portable";
    public static final String MODE_USER = "user";

    private static final String PORTABLE_MARKER = "portable.txt";
    private static final String CONFIG_FILE = "location.txt";
    private static final String APP_DIR = "MailClient";
    private static final String DATA_DIR = "MailClient-Daten";

    /** Umgebung fuer die Aufloesung — explizit, damit die Logik testbar bleibt. */
    public record Env(String property, Path jarDir, Path home, String os) { }

    private AppPaths() { }

    /** Echte Umgebung des laufenden Prozesses. */
    public static Env systemEnv() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String prop = System.getProperty(PROP_DATA);
        if (prop == null && "true".equalsIgnoreCase(System.getProperty(PROP_PORTABLE))) {
            prop = null; // Marker unten greift
        }
        return new Env(prop, jarDir(), Path.of(System.getProperty("user.home", ".")), os);
    }

    /** Ordner des laufenden Jars; null, wenn aus Klassen (IDE/Jar-Entwicklung) gestartet. */
    public static Path jarDir() {
        try {
            var src = AppPaths.class.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) return null;
            Path p = Path.of(src.getLocation().toURI());
            if (Files.isRegularFile(p)) return p.getParent();
            return null; // target/classes -> kein Jar-Betrieb
        } catch (Exception e) {
            return null;
        }
    }

    public static Path resolveDataDir() {
        return resolveDataDir(systemEnv());
    }

    public static Path resolveDataDir(Env env) {
        if (env.property() != null && !env.property().isBlank()) {
            return Path.of(env.property()).toAbsolutePath();
        }
        String mode = readMode(env);
        if (MODE_PORTABLE.equals(mode)) {
            return portableDir(env);
        }
        if (MODE_USER.equals(mode)) {
            return userDir(env);
        }
        if (mode != null && !mode.isBlank()) {
            return Path.of(mode).toAbsolutePath();
        }
        if (isPortableMarked(env)) {
            return portableDir(env);
        }
        Path legacy = legacyDir(env);
        if (Files.exists(legacy)) {
            return legacy;
        }
        return isWindows(env) ? userDir(env) : legacy;
    }

    /** Datenordner im Portable-Modus: neben dem Jar (bzw. im Home, wenn kein Jar). */
    public static Path portableDir(Env env) {
        Path base = env.jarDir() != null ? env.jarDir() : env.home();
        return base.resolve(DATA_DIR);
    }

    /** Datenordner im Benutzerprofil des Betriebssystems. */
    public static Path userDir(Env env) {
        if (isWindows(env)) {
            String appData = System.getenv("APPDATA");
            Path base = appData != null && !appData.isBlank()
                    ? Path.of(appData)
                    : env.home().resolve("AppData").resolve("Roaming");
            return base.resolve(APP_DIR);
        }
        if (env.os() != null && env.os().contains("mac")) {
            return env.home().resolve("Library").resolve("Application Support").resolve(APP_DIR);
        }
        return env.home().resolve(".local").resolve("share").resolve("mailclient");
    }

    /** Alter Standardpfad (bis v0.1.4). */
    public static Path legacyDir(Env env) {
        return env.home().resolve(DATA_DIR);
    }

    public static boolean isWindows(Env env) {
        return env.os() != null && env.os().contains("win");
    }

    public static boolean isPortableMarked(Env env) {
        return env.jarDir() != null && Files.exists(env.jarDir().resolve(PORTABLE_MARKER));
    }

    /** Konfigdatei mit der Ablageort-Wahl (immer im Benutzerprofil, nicht im Datenordner). */
    public static Path configFile(Env env) {
        Path base = isWindows(env)
                ? userDir(env)
                : env.home().resolve(".config").resolve("mailclient");
        return base.resolve(CONFIG_FILE);
    }

    public static String readMode(Env env) {
        try {
            Path file = configFile(env);
            if (!Files.exists(file)) return null;
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            return null;
        }
    }

    public static void writeMode(Env env, String mode) throws IOException {
        Path file = configFile(env);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, mode == null ? "" : mode, StandardCharsets.UTF_8);
    }

    /** Setzt/entfernt den Portable-Marker neben dem Jar. */
    public static void setPortableMarker(Env env, boolean on) throws IOException {
        if (env.jarDir() == null) {
            throw new IOException("Kein Jar-Ordner ermittelbar (Start aus Klassen)");
        }
        Path marker = env.jarDir().resolve(PORTABLE_MARKER);
        if (on) {
            Files.writeString(marker, "portable" + System.lineSeparator(), StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(marker);
        }
    }
}
