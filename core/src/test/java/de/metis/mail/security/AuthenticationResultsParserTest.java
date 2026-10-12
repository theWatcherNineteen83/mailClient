package de.metis.mail.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationResultsParserTest {

    private final AuthenticationResultsParser parser = new AuthenticationResultsParser();

    @Test
    void parsesSpfWithDomain() {
        List<AuthenticationResultsParser.AuthResult> results =
                parser.parse("spf=pass smtp.mailfrom=example.com");
        assertEquals(1, results.size());
        AuthenticationResultsParser.AuthResult r = results.get(0);
        assertEquals("spf", r.protocol);
        assertEquals("pass", r.status);
        assertEquals("example.com", r.domain);
    }

    @Test
    void parsesDkimWithDomain() {
        var results = parser.parse("dkim=fail header.d=mail.example.org");
        assertEquals(1, results.size());
        assertEquals("dkim", results.get(0).protocol);
        assertEquals("mail.example.org", results.get(0).domain);
        assertEquals("fail", results.get(0).status);
    }

    @Test
    void parsesDmarcWithDomain() {
        var results = parser.parse("dmarc=softfail header.from=shop.example.biz");
        assertEquals(1, results.size());
        assertEquals("dmarc", results.get(0).protocol);
        assertEquals("shop.example.biz", results.get(0).domain);
    }

    @Test
    void emptyInputGivesEmptyList() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
    }
}
