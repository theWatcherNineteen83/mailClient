package de.metis.mail.desktop;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Farbschema Hell/Dunkel/Automatisch — die einzige Quelle für UI-Farben.
 * Logik ist headless-testbar (ThemeTest); OS-Erkennung läuft pro Aufruf mit kurzem Timeout.
 */
public final class Theme {

    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    public static final String AUTO = "auto";

    private static String mode = AUTO;
    private static boolean dark; // aufgelöst aus mode + OS

    private Theme() { }

    /** Reine Logik: welches Schema gilt für Modus + OS-Vorgabe? */
    public static boolean resolvesToDark(String mode, boolean osDark) {
        return switch (mode == null ? AUTO : mode) {
            case DARK -> true;
            case LIGHT -> false;
            default -> osDark;
        };
    }

    public static String mode() { return mode; }
    public static boolean isDark() { return dark; }

    /** Setzt den Modus und löst ihn gegen die OS-Vorgabe auf. */
    public static void set(String newMode) {
        mode = switch (newMode == null ? AUTO : newMode) {
            case LIGHT, DARK -> newMode;
            default -> AUTO;
        };
        dark = resolvesToDark(mode, mode.equals(AUTO) && detectOsDark());
    }

    // ---------- Palette ----------

    public static Color listBg()    { return dark ? new Color(0x24282E) : new Color(0xFAFBFC); }
    public static Color surface()   { return dark ? new Color(0x1E2226) : Color.WHITE; }
    public static Color header()    { return dark ? new Color(0x2E343C) : new Color(0xEDF1F5); }
    public static Color accent()    { return dark ? new Color(0x7FB2E8) : new Color(0x3D6EB5); }
    public static Color text()      { return dark ? new Color(0xE6E8EA) : new Color(0x1F2A36); }
    public static Color textSoft()  { return dark ? new Color(0xA8B4C0) : new Color(0x5A6A7A); }
    public static Color border()    { return dark ? new Color(0x3A424C) : new Color(0xC8D2DC); }

    /** CSS für die HTML-Leseansicht: dunkler Grund, heller Text, Links hellblau. */
    public static String readerCss() {
        return dark ? "body{background:#1E2226;color:#E6E8EA;}a{color:#7FB2E8;}"
                    : "";
    }

    /** Kontrast nach WCAG-artiger Relativ-Luminanz — wird im Test geprüft. */
    public static double contrast(Color a, Color b) {
        double l1 = luminance(a), l2 = luminance(b);
        double hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * chan(c.getRed()) + 0.7152 * chan(c.getGreen()) + 0.0722 * chan(c.getBlue());
    }

    private static double chan(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    // ---------- OS-Erkennung ----------

    /** Windows: AppsUseLightTheme; GNOME/KDE: gsettings color-scheme; macOS: AppleInterfaceStyle. */
    public static boolean detectOsDark() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                String out = exec("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                return out != null && out.contains("0x0");
            }
            if (os.contains("mac")) {
                String out = exec("defaults", "read", "-g", "AppleInterfaceStyle");
                return out != null && out.trim().equalsIgnoreCase("Dark");
            }
            String scheme = exec("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            if (scheme != null && scheme.contains("dark")) return true;
            String gtk = exec("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
            return gtk != null && gtk.toLowerCase().contains("dark");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return null;
        }
        return p.exitValue() == 0 ? sb.toString() : null;
    }
}
