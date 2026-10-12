package de.metis.mail.desktop;

/**
 * Die vier mitgelieferten Layout-Presets (spec-ui.md §2).
 */
public enum LayoutPreset {
    DEFAULT,
    THUNDERBIRD,
    OUTLOOK,
    GMAIL;

    public static LayoutPreset fromString(String s) {
        if (s == null || s.isBlank()) return DEFAULT;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    /** I18n-Schlüssel für Menü-Labels. */
    public String i18nKey() {
        return switch (this) {
            case DEFAULT -> "menu.view.layout.default";
            case THUNDERBIRD -> "menu.view.layout.thunderbird";
            case OUTLOOK -> "menu.view.layout.outlook";
            case GMAIL -> "menu.view.layout.gmail";
        };
    }
}
