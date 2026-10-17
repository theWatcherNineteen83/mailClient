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
}
