package de.metis.mail.security;

import java.util.logging.Logger;

/**
 * SecurityBadge – evaluates SPF/DKIM/DMARC status for mail security badges.
 */
public class SecurityBadge {

    private static final Logger LOG = Logger.getLogger(SecurityBadge.class.getName());

    /**
     * Security evaluation level.
     */
    public enum Level {
        GREEN,
        YELLOW,
        RED,
        UNKNOWN
    }

    /**
     * Evaluates the security level based on SPF, DKIM, and DMARC statuses.
     *
     * @param spf   SPF status (pass, fail, none, or null/empty)
     * @param dkim  DKIM status (pass, fail, none, or null/empty)
     * @param dmarc DMARC status (pass, fail, none, or null/empty)
     * @return The evaluated security level
     */
    public static Level evaluate(String spf, String dkim, String dmarc) {
        LOG.fine("Evaluating security badge: SPF=" + spf + ", DKIM=" + dkim + ", DMARC=" + dmarc);

        boolean hasFail = containsFail(spf, dkim, dmarc);
        boolean allPass = allPass(spf, dkim, dmarc);
        boolean allUnknown = allNullOrNone(spf, dkim, dmarc);

        Level level;
        if (allPass) {
            level = Level.GREEN;
            LOG.fine("All checks passed - GREEN");
        } else if (hasFail) {
            level = Level.RED;
            LOG.fine("At least one check failed - RED");
        } else if (allUnknown) {
            level = Level.UNKNOWN;
            LOG.fine("All checks are null/none/empty - UNKNOWN");
        } else {
            level = Level.YELLOW;
            LOG.fine("No fail, at least one pass or none - YELLOW");
        }

        return level;
    }

    /**
     * Returns a short German UI label for the given level.
     *
     * @param level the security level
     * @return German UI text
     */
    public static String label(Level level) {
        if (level == null) {
            return "Unbekannt";
        }
        switch (level) {
            case GREEN:
                return "Gesichert";
            case YELLOW:
                return "Partiell gesichert";
            case RED:
                return "Nicht gesichert";
            case UNKNOWN:
            default:
                return "Unbekannt";
        }
    }

    /**
     * Returns a tooltip with explanation for each part check.
     *
     * @param level the security level
     * @param spf   SPF status
     * @param dkim  DKIM status
     * @param dmarc DMARC status
     * @return German tooltip text with per-check explanations
     */
    public static String tooltip(Level level, String spf, String dkim, String dmarc) {
        StringBuilder sb = new StringBuilder();
        sb.append("SPF: ").append(describeCheck(spf)).append("\n");
        sb.append("DKIM: ").append(describeCheck(dkim)).append("\n");
        sb.append("DMARC: ").append(describeCheck(dmarc)).append("\n");

        if (level != null) {
            switch (level) {
                case GREEN:
                    sb.append("Alle Prüfungen erfolgreich.");
                    break;
                case YELLOW:
                    sb.append("Teilweise Prüfung erfolgreich, keine Fehlschläge.");
                    break;
                case RED:
                    sb.append("Mindestens eine Prüfung fehlgeschlagen.");
                    break;
                case UNKNOWN:
                default:
                    sb.append("Keine Prüfungsdaten verfügbar.");
                    break;
            }
        }

        return sb.toString().trim();
    }

    private static boolean containsFail(String... values) {
        for (String v : values) {
            if ("fail".equalsIgnoreCase(v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allPass(String... values) {
        for (String v : values) {
            if (!"pass".equalsIgnoreCase(v)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allNullOrNone(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty() && !"none".equalsIgnoreCase(v)) {
                return false;
            }
        }
        return true;
    }

    private static String describeCheck(String value) {
        if (value == null || value.isEmpty()) {
            return "Keine Daten";
        }
        if ("pass".equalsIgnoreCase(value)) {
            return "Bestanden";
        }
        if ("fail".equalsIgnoreCase(value)) {
            return "Fehlgeschlagen";
        }
        if ("none".equalsIgnoreCase(value)) {
            return "Nicht geprüft";
        }
        return "Unklar (" + value + ")";
    }
}