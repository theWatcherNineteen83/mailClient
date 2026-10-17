package de.metis.mail.desktop;

import static org.junit.jupiter.api.Assertions.*;

import de.metis.mail.desktop.CidResolver.InlineImage;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class CidResolverTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    private static final String PNG_B64 = Base64.getEncoder().encodeToString(PNG);

    @Test
    void replacesKnownCidWithDataUri() {
        String out = CidResolver.inline("<img src=\"cid:logo123\" alt=\"L\">",
                List.of(new InlineImage("logo123", "image/png", PNG)));
        assertEquals("<img src=\"data:image/png;base64," + PNG_B64 + "\" alt=\"L\">", out);
    }

    @Test
    void matchesCaseInsensitiveAndUrlEncoded() {
        String out = CidResolver.inline("<img src=\"cid:Image001%40Host\">",
                List.of(new InlineImage("image001@host", "image/jpg; name=x.jpg", PNG)));
        assertTrue(out.contains("src=\"data:image/jpeg;base64,"));
    }

    @Test
    void svgIsNeverInlined() {
        String out = CidResolver.inline("<img src=\"cid:s1\" alt=\"s\">",
                List.of(new InlineImage("s1", "image/svg+xml", "<svg/>".getBytes())));
        assertFalse(out.contains("src="));
        assertTrue(out.contains("alt=\"s\""));
    }

    @Test
    void unknownCidLosesSrc() {
        String out = CidResolver.inline("<img src=\"cid:missing\" alt=\"m\">", List.of());
        assertFalse(out.contains("src="));
    }

    @Test
    void otherSrcAndHtmlWithoutCidStayUntouched() {
        String html = "<p>Hi</p><img src=\"https://example.com/a.gif\">";
        assertEquals(html, CidResolver.inline(html, List.of(new InlineImage("x", "image/png", PNG))));
    }
}
