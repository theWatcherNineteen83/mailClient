package de.metis.mail.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlSanitizerTest {

    private final HtmlSanitizer sanitizer = new HtmlSanitizer();

    @Test
    void removesScriptBlocks() {
        String out = sanitizer.sanitize("<p>hi</p><script>alert(1)</script>");
        assertFalse(out.toLowerCase().contains("script"));
        assertFalse(out.contains("alert"));
        assertTrue(out.contains("<p>hi</p>"));
    }

    @Test
    void removesEventHandlersQuotedAndUnquoted() {
        String out = sanitizer.sanitize("<p onclick=\"x\">hi</p><img src=\"http://e.de/i.png\" onerror=bad()/>");
        assertFalse(out.toLowerCase().contains("onclick"));
        assertFalse(out.toLowerCase().contains("onerror"));
        assertTrue(out.contains("http://e.de/i.png"), "safe src must survive");
    }

    @Test
    void removesJavascriptAndDataUrls() {
        String out = sanitizer.sanitize("<a href=\"javascript:alert(1)\">L</a><img src=\"data:text/html,<script>\"/>");
        assertFalse(out.toLowerCase().contains("javascript:"));
        assertFalse(out.toLowerCase().contains("data:"));
    }

    @Test
    void keepsAllowedTagsAndSafeAttributes() {
        String html = "<div><a href=\"https://example.com\" title=\"t\">link</a><b>bold</b><br/></div>";
        String out = sanitizer.sanitize(html);
        assertTrue(out.contains("<a href=\"https://example.com\" title=\"t\">"));
        assertTrue(out.contains("<b>bold</b>"));
        assertTrue(out.contains("<br/>"));
    }

    @Test
    void removesDisallowedTagsButKeepsText() {
        String out = sanitizer.sanitize("<iframe src=\"http://evil.de\"></iframe>text<marquee>m</marquee>");
        assertFalse(out.toLowerCase().contains("iframe"));
        assertFalse(out.toLowerCase().contains("marquee"));
        assertTrue(out.contains("text"));
        assertTrue(out.contains("m"));
    }

    @Test
    void removesStyleBlocksAndComments() {
        String out = sanitizer.sanitize("<style>body{}</style><!-- c --><p>ok</p>");
        assertFalse(out.toLowerCase().contains("style"));
        assertFalse(out.contains("<!--"));
        assertTrue(out.contains("<p>ok</p>"));
    }

    @Test
    void nullAndEmptyReturnEmpty() {
        assertEquals("", sanitizer.sanitize(null));
        assertEquals("", sanitizer.sanitize(""));
    }

    @Test
    void keepsMailtoAndCidSchemes() {
        String out = sanitizer.sanitize("<a href=\"mailto:a@b.de\">m</a><img src=\"cid:img1\"/>");
        assertTrue(out.contains("mailto:a@b.de"));
        assertTrue(out.contains("cid:img1"));
    }
}
