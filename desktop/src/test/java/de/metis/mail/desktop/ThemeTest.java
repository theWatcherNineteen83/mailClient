package de.metis.mail.desktop;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Theme-Logik headless geprüft: Modus-Auflösung, Normalisierung,
 * WCAG-artiger Kontrast der Paletten (Hell + Dunkel) und Reader-CSS.
 */
class ThemeTest {

    // ---------- resolvesToDark: reine Logik ----------

    @Test
    void darkModeAlwaysResolvesDark() {
        assertTrue(Theme.resolvesToDark(Theme.DARK, false));
        assertTrue(Theme.resolvesToDark(Theme.DARK, true));
    }

    @Test
    void lightModeAlwaysResolvesLight() {
        assertFalse(Theme.resolvesToDark(Theme.LIGHT, true));
        assertFalse(Theme.resolvesToDark(Theme.LIGHT, false));
    }

    @Test
    void autoModeFollowsOs() {
        assertTrue(Theme.resolvesToDark(Theme.AUTO, true));
        assertFalse(Theme.resolvesToDark(Theme.AUTO, false));
    }

    @Test
    void nullAndUnknownModeFallBackToAuto() {
        assertTrue(Theme.resolvesToDark(null, true));
        assertFalse(Theme.resolvesToDark(null, false));
        assertEquals(Theme.resolvesToDark(Theme.AUTO, true), Theme.resolvesToDark("quatsch", true));
        assertEquals(Theme.resolvesToDark(Theme.AUTO, false), Theme.resolvesToDark("quatsch", false));
    }

    // ---------- set(): Normalisierung ----------

    @Test
    void setNormalizesUnknownModeToAuto() {
        Theme.set("unbekannt");
        assertEquals(Theme.AUTO, Theme.mode());
        Theme.set(null);
        assertEquals(Theme.AUTO, Theme.mode());
    }

    @Test
    void setExplicitModesStick() {
        Theme.set(Theme.DARK);
        assertEquals(Theme.DARK, Theme.mode());
        assertTrue(Theme.isDark());

        Theme.set(Theme.LIGHT);
        assertEquals(Theme.LIGHT, Theme.mode());
        assertFalse(Theme.isDark());

        Theme.set(Theme.AUTO); // Zustand für Folgetests neutral halten
        assertEquals(Theme.AUTO, Theme.mode());
    }

    // ---------- Kontrast: Text muss auf Grund lesbar sein (WCAG AA ≈ 4.5) ----------

    @Test
    void lightPaletteHasReadableContrast() {
        Theme.set(Theme.LIGHT);
        assertContrastOk();
    }

    @Test
    void darkPaletteHasReadableContrast() {
        Theme.set(Theme.DARK);
        assertContrastOk();
        Theme.set(Theme.AUTO);
    }

    private void assertContrastOk() {
        assertTrue(Theme.contrast(Theme.text(), Theme.surface()) >= 4.5,
                "Text/Surface-Kontrast zu gering: " + Theme.contrast(Theme.text(), Theme.surface()));
        assertTrue(Theme.contrast(Theme.text(), Theme.listBg()) >= 4.5,
                "Text/ListBg-Kontrast zu gering: " + Theme.contrast(Theme.text(), Theme.listBg()));
        assertTrue(Theme.contrast(Theme.textSoft(), Theme.surface()) >= 3.0,
                "TextSoft/Surface-Kontrast zu gering: " + Theme.contrast(Theme.textSoft(), Theme.surface()));
    }

    @Test
    void contrastOfBlackWhiteIs21() {
        assertEquals(21.0, Theme.contrast(Color.BLACK, Color.WHITE), 0.05);
        assertEquals(1.0, Theme.contrast(Color.WHITE, Color.WHITE), 0.001);
    }

    // ---------- Reader-CSS ----------

    @Test
    void readerCssOnlyInDarkMode() {
        Theme.set(Theme.DARK);
        assertFalse(Theme.readerCss().isBlank());
        assertTrue(Theme.readerCss().contains("background"));
        Theme.set(Theme.LIGHT);
        assertEquals("", Theme.readerCss());
        Theme.set(Theme.AUTO);
    }

    @Test
    void osDetectionRunsHeadlessWithoutCrash() {
        // Darf auf jeder Maschine (auch ohne gsettings/registry) einfach einen
        // Boolean liefern — entscheidend ist, dass nichts wirft oder hängt.
        boolean dark = Theme.detectOsDark();
        assertTrue(dark || !dark);
    }
}
