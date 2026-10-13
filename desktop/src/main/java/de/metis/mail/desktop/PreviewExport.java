package de.metis.mail.desktop;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * M4-004: Vorschau im Systembrowser des Betriebssystems.
 *
 * <p>Die bereits sanitisierte HTML-Mail wird in eine temporaere Datei geschrieben und
 * ueber {@link Desktop#browse(URI)} an den Standardbrowser uebergeben. Zusaetzlich wird
 * eine restriktive Content-Security-Policy gesetzt: damit laedt der Browser keine
 * entfernten Bilder/Skripte nach (kein Tracking-Pixel beim blossen Ansehen).
 */
public final class PreviewExport {

    /** Blockiert alle externen Ladevorgaenge; erlaubt nur Inline-Styles und data:-Bilder. */
    public static final String CSP =
            "default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:;";

    private final Path dir;

    public PreviewExport(Path dir) {
        this.dir = dir;
    }

    /** Standardordner fuer Browser-Vorschauen (temporaer). */
    public static PreviewExport inTempDir() {
        return new PreviewExport(Path.of(System.getProperty("java.io.tmpdir"), "mailclient-preview"));
    }

    /**
     * Schreibt das sanitisierte HTML in eine Datei und liefert deren Pfad.
     * Der Inhalt bekommt UTF-8-Header und die CSP.
     */
    public Path write(String sanitizedHtml) throws IOException {
        Files.createDirectories(dir);
        String html = sanitizedHtml == null ? "" : sanitizedHtml;
        String page = "<!DOCTYPE html>\n<html><head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">\n"
                + "<title>MailClient Vorschau</title>\n"
                + "</head><body>\n" + html + "\n</body></html>\n";
        Path file = dir.resolve("mail-vorschau.html");
        Files.writeString(file, page, StandardCharsets.UTF_8);
        return file;
    }

    /** true, wenn der Rechner ueberhaupt einen Standardbrowser anbieten kann. */
    public static boolean browserAvailable() {
        try {
            return Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Oeffnet die Datei im Standardbrowser; wirft, wenn das nicht geht. */
    public static void openInBrowser(Path file) throws IOException {
        if (!browserAvailable()) {
            throw new IOException("Kein Standardbrowser verfuegbar");
        }
        Desktop.getDesktop().browse(file.toUri());
    }

    public Path dir() {
        return dir;
    }
}
