package de.metis.mail.desktop;

import static org.junit.jupiter.api.Assertions.*;

import de.metis.mail.desktop.CidResolver.InlineImage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.Base64;
import java.util.List;

class CidResolverFileTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @TempDir
    Path dir;

    @Test
    void writesImageAndUsesFileUri() throws Exception {
        String out = CidResolver.solveToFiles("<img src=\"cid:logo123\" alt=\"L\">",
                List.of(new InlineImage("logo123", "image/png", PNG)), dir);
        assertTrue(out.contains("src=\"file:"), "erwartet file:-URI, war: " + out);
        assertFalse(out.contains("data:"));
        // Datei wurde wirklich geschrieben
        Path p = dir.resolve("logo123.png");
        assertTrue(Files.exists(p));
        assertArrayEquals(PNG, Files.readAllBytes(p));
    }

    @Test
    void svgNeverWrittenToFile() {
        String out = CidResolver.solveToFiles("<img src=\"cid:s1\" alt=\"s\">",
                List.of(new InlineImage("s1", "image/svg+xml", "<svg/>".getBytes())), dir);
        assertFalse(out.contains("src="));
        assertTrue(out.contains("alt=\"s\""));
    }

    @Test
    void unsafeCidIsSanitizedInFilename() throws Exception {
        // cid mit Pfad-Traversal-Zeichen darf keinen Pfadbruch erzeugen
        String out = CidResolver.solveToFiles("<img src=\"cid:../evil\" alt=\"e\">",
                List.of(new InlineImage("../evil", "image/png", PNG)), dir);
        assertTrue(out.contains("src=\"file:"));
        Path p = dir.resolve(".._evil.png");
        assertTrue(Files.exists(p), "Dateiname wurde nicht sanitized");
        assertFalse(Files.exists(dir.resolve("../evil.png")), "kein Pfadbruch erlaubt");
    }
}
