package de.metis.mail.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** M4-004: Vorschau im Systembrowser — Datei-Aufbereitung inkl. restriktiver CSP. */
class PreviewExportTest {

    @TempDir
    Path dir;

    @Test
    void writesUtf8PageWithCspAndContent() throws Exception {
        Path file = new PreviewExport(dir).write("<p>Hallo Georg</p>");
        assertTrue(Files.exists(file));

        String html = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(html.contains("charset=\"UTF-8\""), "UTF-8 muss deklariert sein");
        assertTrue(html.contains("Content-Security-Policy"), "CSP fehlt");
        assertTrue(html.contains("default-src 'none'"), "externe Ladevorgaenge muessen blockiert sein");
        assertTrue(html.contains("<p>Hallo Georg</p>"));
    }

    @Test
    void handlesNullContent() throws Exception {
        Path file = new PreviewExport(dir).write(null);
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("<!DOCTYPE html>"));
    }

    @Test
    void browserAvailabilityCheckNeverThrows() {
        assertDoesNotThrow(PreviewExport::browserAvailable);
        assertFalse(PreviewExport.CSP.isBlank());
    }
}
