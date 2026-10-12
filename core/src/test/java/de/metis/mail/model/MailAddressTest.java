package de.metis.mail.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailAddressTest {

    @Test
    void parsesPlainAddress() {
        MailAddress a = MailAddress.parse("alice@example.com");
        assertEquals("alice", a.getLocalPart());
        assertEquals("example.com", a.getDomain());
        assertNull(a.getDisplayName());
        assertEquals("alice@example.com", a.toAddressString());
        assertEquals("alice@example.com", a.toFormattedString());
    }

    @Test
    void parsesAddressWithDisplayName() {
        MailAddress a = MailAddress.parse("Alice Miller <alice@example.com>");
        assertEquals("Alice Miller", a.getDisplayName());
        assertEquals("alice", a.getLocalPart());
        assertEquals("example.com", a.getDomain());
        assertEquals("Alice Miller <alice@example.com>", a.toFormattedString());
    }

    @Test
    void parsesAddressWithSpecialLocalPartChars() {
        MailAddress a = MailAddress.parse("a!#$%&'*+/=?^_`{|}~-b@example.com");
        assertEquals("a!#$%&'*+/=?^_`{|}~-b", a.getLocalPart());
    }

    @Test
    void rejectsNullOrEmpty() {
        assertThrows(IllegalArgumentException.class, () -> MailAddress.parse(null));
        assertThrows(IllegalArgumentException.class, () -> MailAddress.parse(""));
        assertThrows(IllegalArgumentException.class, () -> MailAddress.parse("   "));
    }

    @Test
    void rejectsMissingAt() {
        assertThrows(IllegalArgumentException.class, () -> MailAddress.parse("alice.example.com"));
    }

    @Test
    void rejectsInvalidDomain() {
        assertThrows(IllegalArgumentException.class, () -> MailAddress.parse("alice@localhost"));
        assertThrows(IllegalArgumentException.class, () -> MailAddress.parse("alice@-bad.example.com"));
    }

    @Test
    void rejectsMalformedAngleBrackets() {
        assertThrows(IllegalArgumentException.class, () -> MailAddress.parse("Alice <alice@example.com"));
        assertThrows(IllegalArgumentException.class, () -> MailAddress.parse("Alice <al>ice@example.com>"));
    }

    @Test
    void rejectsOverlongLocalPart() {
        String local = "a".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> MailAddress.parse(local + "@example.com"));
    }

    @Test
    void equalityIgnoresCaseInAddress() {
        MailAddress a = MailAddress.parse("Alice@Example.COM");
        MailAddress b = MailAddress.parse("alice@example.com");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
