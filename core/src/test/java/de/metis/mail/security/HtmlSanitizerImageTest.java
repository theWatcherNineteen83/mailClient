package de.metis.mail.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HtmlSanitizerImageTest {

    private static final String PNG =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private final HtmlSanitizer sanitizer = new HtmlSanitizer();

    private String clean(String html) {
        return sanitizer.sanitize(html);
    }

    @Test
    void keepsEmbeddedRasterImages() {
        String out = clean("<img src=\"data:image/png;base64," + PNG + "\" alt=\"x\">");
        assertTrue(out.contains("src=\"data:image/png;base64," + PNG + "\""));
    }

    @Test
    void keepsCidImages() {
        assertTrue(clean("<img src=\"cid:logo123\" alt=\"Logo\">").contains("src=\"cid:logo123\""));
    }

    @Test
    void blocksSvgDataImages() {
        assertFalse(clean("<img src=\"data:image/svg+xml;base64,PHN2Zy8+\" alt=\"s\">").contains("src="));
    }

    @Test
    void blocksHtmlDataImages() {
        assertFalse(clean("<img src=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\" alt=\"h\">")
                .contains("src="));
    }

    @Test
    void blocksDataUriInLinks() {
        assertFalse(clean("<a href=\"data:image/png;base64," + PNG + "\">x</a>").contains("href="));
    }

    // ---------- M4-010: Vorschau-Modus gegen Tracking-Pixel ----------

    @Test
    void previewModeStripsRemoteImageSources() {
        String out = sanitizer.sanitize(
                "<p>x</p><img src=\"https://tracker.example/p.gif\" alt=\"Pixel\">", true);
        assertFalse(out.contains("tracker.example"), out);
        assertFalse(out.contains("src="), out);
        assertTrue(out.contains("alt=\"Pixel\""), "Alt-Text bleibt erhalten: " + out);
        assertTrue(out.contains("<p>x</p>"), "Inhalt bleibt: " + out);
        org.junit.jupiter.api.Assertions.assertEquals(1, sanitizer.lastRemoteImagesStripped());
    }

    @Test
    void previewModeKeepsInlineAndCidImages() {
        String out = sanitizer.sanitize(
                "<img src=\"cid:logo123\" alt=\"L\">"
                + "<img src=\"data:image/png;base64," + PNG + "\" alt=\"d\">", true);
        assertTrue(out.contains("src=\"cid:logo123\""), out);
        assertTrue(out.contains("data:image/png;base64"), out);
        org.junit.jupiter.api.Assertions.assertEquals(0, sanitizer.lastRemoteImagesStripped());
    }

    @Test
    void previewModeStripsHttpAndFtpButNotLinks() {
        String out = sanitizer.sanitize(
                "<img src=\"http://a.example/1.png\" alt=\"a\">"
                + "<img src=\"ftp://b.example/2.png\" alt=\"b\">"
                + "<a href=\"https://ok.example/\">link</a>", true);
        assertFalse(out.contains("a.example"), out);
        assertFalse(out.contains("b.example"), out);
        assertTrue(out.contains("href=\"https://ok.example/\""), "Links bleiben: " + out);
    }

    @Test
    void defaultModeStillAllowsRemoteImages() {
        String out = sanitizer.sanitize("<img src=\"https://cdn.example/logo.png\" alt=\"l\">");
        assertTrue(out.contains("cdn.example"), out);
    }
}
