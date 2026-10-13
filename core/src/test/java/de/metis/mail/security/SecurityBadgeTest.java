package de.metis.mail.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests fuer den von Metis erzeugten SecurityBadge (Metis-Ziel 13.10.2026). */
class SecurityBadgeTest {

    @Test
    void allPassIsGreen() {
        assertEquals(SecurityBadge.Level.GREEN, SecurityBadge.evaluate("pass", "pass", "pass"));
    }

    @Test
    void anyFailIsRed() {
        assertEquals(SecurityBadge.Level.RED, SecurityBadge.evaluate("fail", "pass", "pass"));
        assertEquals(SecurityBadge.Level.RED, SecurityBadge.evaluate("pass", "fail", "pass"));
        assertEquals(SecurityBadge.Level.RED, SecurityBadge.evaluate("pass", "pass", "fail"));
    }

    @Test
    void missingOrNoneIsUnknown() {
        assertEquals(SecurityBadge.Level.UNKNOWN, SecurityBadge.evaluate(null, null, null));
        assertEquals(SecurityBadge.Level.UNKNOWN, SecurityBadge.evaluate("none", "none", "none"));
    }

    @Test
    void mixedWithoutFailIsYellow() {
        assertEquals(SecurityBadge.Level.YELLOW, SecurityBadge.evaluate("pass", "none", "none"));
        assertEquals(SecurityBadge.Level.YELLOW, SecurityBadge.evaluate("none", "pass", "pass"));
    }

    @Test
    void labelsAndTooltipsAreUsable() {
        for (SecurityBadge.Level lvl : SecurityBadge.Level.values()) {
            assertFalse(SecurityBadge.label(lvl).isBlank(), "Label fehlt fuer " + lvl);
            assertFalse(SecurityBadge.tooltip(lvl, "pass", "fail", "none").isBlank(),
                    "Tooltip fehlt fuer " + lvl);
        }
        assertNotEquals(SecurityBadge.label(SecurityBadge.Level.GREEN),
                SecurityBadge.label(SecurityBadge.Level.RED));
    }
}
