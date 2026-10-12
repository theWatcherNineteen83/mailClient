package de.metis.mail.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-004: Security features tested against known attack vectors.
 * XSS corpus for HtmlSanitizer, evasion patterns for AttachmentPolicy,
 * header spoofing for AuthenticationResultsParser.
 */
class SecurityAttackTest {

    private final HtmlSanitizer sanitizer = new HtmlSanitizer();
    private final AttachmentPolicy policy = AttachmentPolicy.createDefault();
    private final AuthenticationResultsParser parser = new AuthenticationResultsParser();

    // ---------- XSS attack corpus ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert('XSS')</script>",
            "<SCRIPT SRC=http://evil.de/xss.js></SCRIPT>",
            "<img src=x onerror=alert(1)>",
            "<img src=x ONERROR=alert(1)>",
            "<img src=x OnErRoR=alert(1)>",
            "<body onload=alert(1)>",
            "<svg onload=alert(1)>",
            "<a href=\"javascript:alert(1)\">click</a>",
            "<a href=\"JaVaScRiPt:alert(1)\">click</a>",
            "<a href=\" javascript:alert(1)\">click</a>",
            "<a href=\"java\tscript:alert(1)\">click</a>",
            "<iframe src=\"http://evil.de\"></iframe>",
            "<object data=\"http://evil.de/x.swf\"></object>",
            "<embed src=\"http://evil.de/x.swf\">",
            "<base href=\"javascript:alert(1)//\">",
            "<form action=\"javascript:alert(1)\"><input type=submit>",
            "<input onfocus=alert(1) autofocus>",
            "<marquee onstart=alert(1)>",
            "<div style=\"background:url(javascript:alert(1))\">x</div>",
            "<link rel=stylesheet href=\"http://evil.de/x.css\">",
            "<meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\">",
            "<scr<script>ipt>alert(1)</scr</script>ipt>",
            "<p onclick=\"alert(1)\">text</p>",
            "<p onmouseover='alert(1)'>text</p>",
            "<img src=\"data:text/html,<script>alert(1)</script>\">",
            "<!--[if gte IE 4]><script>alert(1)</script><![endif]-->",
            "<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>"
    })
    void xssVectorsAreNeutralized(String attack) {
        String out = sanitizer.sanitize(attack).toLowerCase(Locale.ROOT);
        assertFalse(out.contains("<script"), "script tag survived: " + attack + " -> " + out);
        assertFalse(out.contains("<iframe"), "iframe survived: " + attack);
        assertFalse(out.contains("<object"), "object survived: " + attack);
        assertFalse(out.contains("<embed"), "embed survived: " + attack);
        assertFalse(out.contains("<base"), "base survived: " + attack);
        assertFalse(out.contains("<form"), "form survived: " + attack);
        assertFalse(out.contains("<meta"), "meta survived: " + attack);
        assertFalse(out.contains("<link"), "link survived: " + attack);
        assertFalse(out.contains("<input"), "input survived: " + attack);
        assertFalse(out.contains("<svg"), "svg survived: " + attack);
        assertFalse(out.contains("<style"), "style tag survived: " + attack);
        assertFalse(out.contains("javascript:"), "javascript: URL survived: " + attack + " -> " + out);
        assertFalse(out.contains("data:text/html"), "data:text/html survived: " + attack);
        // No event handler attribute may survive in any remaining tag
        assertFalse(out.matches("(?s).*<[a-z][^>]*\\son[a-z]+\\s*=.*"),
                "event handler survived: " + attack + " -> " + out);
    }

    @Test
    void benignHtmlSurvivesSanitization() {
        String html = "<p>Hallo <b>Welt</b>, <a href=\"https://example.com/page?a=1\">Link</a>"
                + "<img src=\"https://example.com/i.png\" alt=\"Bild\"/><br/>"
                + "<ul><li>eins</li><li>zwei</li></ul></p>";
        String out = sanitizer.sanitize(html);
        assertTrue(out.contains("<b>Welt</b>"));
        assertTrue(out.contains("https://example.com/page?a=1"));
        assertTrue(out.contains("alt=\"Bild\""));
        assertTrue(out.contains("<li>eins</li>"));
    }

    // ---------- AttachmentPolicy evasion attempts ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "invoice.pdf.exe", "invoice.exe.pdf.exe", "REPORT.EXE", "Report.Exe",
            "photo.jpg.scr", "doc.docx.vbs", "setup.msi", "game.com",
            "update.hta", "script.js", "macro.wsf", "shortcut.lnk", "app.jar"
    })
    void dangerousAttachmentsAreBlocked(String fileName) {
        assertTrue(policy.checkAttachment(fileName, null).isBlocked(),
                fileName + " must be blocked");
    }

    @ParameterizedTest
    @ValueSource(strings = {"report.pdf", "photo.JPG", "archive.zip", "notes.txt", "sheet.xlsx"})
    void safeAttachmentsAreAllowed(String fileName) {
        assertEquals(AttachmentPolicy.Verdict.ERLAUBT,
                policy.checkAttachment(fileName, null).verdict(), fileName + " must be allowed");
    }

    @Test
    void noExtensionIsSuspiciousNotAllowed() {
        assertTrue(policy.checkAttachment("README", null).isSuspicious());
        assertTrue(policy.checkAttachment("", null).isSuspicious());
        assertTrue(policy.checkAttachment(null, null).isSuspicious());
    }

    // ---------- Authentication header spoofing ----------

    @Test
    void spoofedHeadersAreParsedNotTrusted() {
        // Parser must faithfully report what the header says — trust decisions
        // happen above it. A 'none' result must not become 'pass'.
        var results = parser.parse("spf=none smtp.mailfrom=evil.biz");
        assertEquals(1, results.size());
        assertEquals("none", results.get(0).status);
        assertEquals("evil.biz", results.get(0).domain);
    }

    @Test
    void garbageHeaderYieldsNoResults() {
        assertTrue(parser.parse("this is not a valid header").isEmpty()
                || parser.parse("this is not a valid header").stream()
                        .allMatch(r -> r.status == null || !r.status.equals("pass")));
    }
}
