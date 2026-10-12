package de.metis.mail.desktop;

import javax.swing.UIManager;

/**
 * Look-and-Feel-Auswahl (M4): "auto" nimmt das betriebssystemnative Design
 * (Windows -> WindowsLookAndFeel, macOS -> Aqua, Linux -> GTK+).
 * Die weiteren Schlüssel erlauben die explizite Wahl, auch testweise.
 */
public final class LookFeel {

    private LookFeel() { }

    public static String resolveClassName(String key) {
        return switch (key == null ? "auto" : key) {
            case "gtk" -> "com.sun.java.swing.plaf.gtk.GTKLookAndFeel";
            case "windows" -> "com.sun.java.swing.plaf.windows.WindowsLookAndFeel";
            case "nimbus" -> "javax.swing.plaf.nimbus.NimbusLookAndFeel";
            case "metal" -> "javax.swing.plaf.metal.MetalLookAndFeel";
            case "motif" -> "com.sun.java.swing.plaf.motif.MotifLookAndFeel";
            default -> UIManager.getSystemLookAndFeelClassName();
        };
    }

    /** Nur plattformsinnvolle Angebote zeigen: GTK nur auf Linux, Windows-LAF nur auf Windows. */
    public static boolean isAvailable(String key) {
        String os = System.getProperty("os.name", "").toLowerCase();
        return switch (key == null ? "auto" : key) {
            case "gtk" -> os.contains("linux");
            case "windows" -> os.contains("windows");
            default -> true;
        };
    }

    /** Wendet das LAF an; liefert den tatsächlich aktiven Namen. Unverfügbare LAFs werfen klar. */
    public static String apply(String key) throws Exception {
        if (!isAvailable(key)) {
            throw new UnsupportedOperationException(I18n.tr("look.unavailable." + key));
        }
        UIManager.setLookAndFeel(resolveClassName(key));
        return UIManager.getLookAndFeel().getName();
    }
}
