package de.metis.mail.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baut sichere SQL-Suchanfragen fuer die Mail-Suche: nur Platzhalter und
 * Parameterlisten, nie zusammengeklebte Nutzereingaben.
 *
 * <p>Ursprung: Metis-Ziel "SearchQueryBuilder" (13.10.2026). Fuer dieses Schema
 * korrigiert: Spalten heissen from_addr/from_name (nicht sender), Anhaenge liegen
 * in der Tabelle attachments (message_pk), LIKE-Wildcards werden mit ESCAPE
 * maskiert, has:attachment nutzt EXISTS.
 */
public final class SearchQueryBuilder {

    /** Immer-wahre Klausel fuer leere Eingaben (kein Filter, kein Nutzertext im SQL). */
    public static final String NEUTRAL = "1=1";

    private static final String ESC = " ESCAPE '\\'";
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^\\s*(from|subject|has:attachment)\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private static final String ANY_ATTACHMENT =
            "EXISTS (SELECT 1 FROM attachments a WHERE a.message_pk = messages.id)";

    /** Erzeugt WHERE-Fragment + Parameter fuer eine Nutzereingabe. */
    public SearchQuery build(String userInput) {
        String input = userInput == null ? "" : userInput.trim();
        if (input.isEmpty()) {
            return new SearchQuery(NEUTRAL, List.of());
        }

        Matcher m = FIELD_PATTERN.matcher(input);
        if (m.matches()) {
            String field = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(2).trim();
            if (!value.isEmpty()) {
                switch (field) {
                    case "from":
                        return like("(from_addr LIKE ?" + ESC + " COLLATE NOCASE OR from_name LIKE ?"
                                + ESC + " COLLATE NOCASE)", value, 2);
                    case "subject":
                        return like("subject LIKE ?" + ESC + " COLLATE NOCASE", value, 1);
                    case "has:attachment":
                        return new SearchQuery(isTrue(value) ? ANY_ATTACHMENT : "NOT " + ANY_ATTACHMENT, List.of());
                    default:
                        break;
                }
            }
            // Praefix ohne Wert (z. B. "from:") -> Rest als Volltext behandeln
            input = input.replaceFirst("^\\s*(from|subject|has:attachment)\\s*:\\s*", "");
            if (input.isEmpty()) {
                return new SearchQuery(NEUTRAL, List.of());
            }
        }
        return like("(subject LIKE ?" + ESC + " COLLATE NOCASE OR from_addr LIKE ?" + ESC
                + " COLLATE NOCASE OR preview LIKE ?" + ESC + " COLLATE NOCASE)", input, 3);
    }

    private static SearchQuery like(String sql, String value, int count) {
        String escaped = escapeLike(value);
        List<String> params = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            params.add("%" + escaped + "%");
        }
        return new SearchQuery(sql, List.copyOf(params));
    }

    private static boolean isTrue(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes")
                || value.equals("1") || value.equalsIgnoreCase("ja");
    }

    /** Prozent und Unterstrich maskieren; das SQL nutzt dazu ESCAPE '\'. */
    public static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** WHERE-Fragment mit Parametern in Aufruf-Reihenfolge. */
    public record SearchQuery(String sql, List<String> parameters) { }
}
