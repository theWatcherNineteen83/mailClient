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
            case "nimbus" -> "javax.swing.plaf.nimbus.NimbusLookAndFeel";
            case "metal" -> "javax.swing.plaf.metal.MetalLookAndFeel";
            case "motif" -> "com.sun.java.swing.plaf.motif.MotifLookAndFeel";
            default -> UIManager.getSystemLookAndFeelClassName();
        };
    }

    /** Wendet das LAF an; liefert den tatsächlich aktiven Namen (GTK kann auf Metal zurückfallen). */
    public static String apply(String key) throws Exception {
        UIManager.setLookAndFeel(resolveClassName(key));
        return UIManager.getLookAndFeel().getName();
    }
}
