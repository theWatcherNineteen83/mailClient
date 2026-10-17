package de.metis.mail.desktop;

import java.awt.Desktop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;

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

    /**
     * Standardordner fuer Browser-Vorschauen (temporaer).
     *
     * <p>Legt pro Start ein zufaellig benanntes Verzeichnis an, das nur der eigene
     * Nutzer lesen/eintreten darf (rwx------ auf POSIX) und rueumt es beim Beenden
     * vollstaendig auf. Das verhindert, dass ein zweiter lokaler Nutzer den
     * vorhersehbaren Pfad vorab anlegt oder fremde Mails mitliest und dass die
     * letzte Vorschau dauerhaft liegen bleibt.
     */
    public static PreviewExport inTempDir() {
        try {
            Path base = Path.of(System.getProperty("java.io.tmpdir"));
            Path d = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                    ? Files.createTempDirectory(base, "mailclient-preview-",
                        PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")))
                    : Files.createTempDirectory(base, "mailclient-preview-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (Stream<Path> s = Files.walk(d)) {
                    s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // Best-effort cleanup beim Herunterfahren
                }
            }));
            return new PreviewExport(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
