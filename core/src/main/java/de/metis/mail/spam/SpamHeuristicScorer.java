package de.metis.mail.spam;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * Local spam heuristic scoring.
 * Scores 0-100 where higher is more likely spam.
 */
public class SpamHeuristicScorer {

    private static final Logger LOG = Logger.getLogger(SpamHeuristicScorer.class.getName());

    // Heuristic weights
    private static final int SENDER_MISMATCH = 20;
    private static final int SUSPICIOUS_TLD = 10;
    private static final int UPPERCASE_SUBJECT = 15;
    private static final int LINK_COUNT = 10;
    private static final int URGENCY_WORDS = 15;
    private static final int MISSING_AUTH = 20;

    // Regex patterns
    private static final Pattern URL_PATTERN = Pattern.compile("http[s]?://[\\S]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+)\\.([a-zA-Z]{2,})");

    // Urgency words
    private static final String[] URGENCY_WORD_LIST = {
        "sofort", "jetzt", "passwort", "bestaetige", "konto", "gesperrt",
        "urgent", "immediately", "password", "verify", "account", "locked"
    };

    /**
     * Score an email for spam likelihood.
     *
     * @param fromHeader The From header value
     * @param returnPath The Return-Path header value
     * @param subject The email subject
     * @param body The email body
     * @param hasAuth Whether Authentication-Results header is present
     * @return Spam score (0-100)
     */
    public int score(String fromHeader, String returnPath, String subject, String body, boolean hasAuth) {
        int score = 0;
        Map<String, Integer> details = new HashMap<>();

        // Sender mismatch
        if (fromHeader != null && returnPath != null) {
            String fromDomain = extractDomain(fromHeader);
            String returnDomain = extractDomain(returnPath);
            if (fromDomain != null && returnDomain != null && !fromDomain.equals(returnDomain)) {
                score += SENDER_MISMATCH;
                details.put("sender_mismatch", SENDER_MISMATCH);
            }
        }

        // Suspicious TLD (check email addresses AND URL hosts in the body)
        if (body != null) {
            boolean suspicious = false;
            Matcher m = EMAIL_PATTERN.matcher(body);
            while (m.find()) {
                if (isSuspiciousTld(m.group(3).toLowerCase())) {
                    suspicious = true;
                    break;
                }
            }
            if (!suspicious) {
                Matcher u = URL_PATTERN.matcher(body);
                while (u.find()) {
                    String host = u.group().replaceFirst("^https?://", "");
                    int slash = host.indexOf('/');
                    if (slash >= 0) host = host.substring(0, slash);
                    int dot = host.lastIndexOf('.');
                    if (dot >= 0 && dot < host.length() - 1
                            && isSuspiciousTld(host.substring(dot + 1).toLowerCase())) {
                        suspicious = true;
                        break;
                    }
                }
            }
            if (suspicious) {
                score += SUSPICIOUS_TLD;
                details.put("suspicious_tld", SUSPICIOUS_TLD);
            }
        }

        // Uppercase subject
        if (subject != null && !subject.isEmpty()) {
            double upperRatio = countUpperCase(subject) / (double) subject.length();
            if (upperRatio > 0.5) {
                score += UPPERCASE_SUBJECT;
                details.put("uppercase_subject", UPPERCASE_SUBJECT);
            }
        }

        // Link count
        if (body != null) {
            Matcher m = URL_PATTERN.matcher(body);
            int links = 0;
            while (m.find()) {
                links++;
            }
            if (links >= 3) {
                score += LINK_COUNT;
                details.put("link_count", LINK_COUNT);
            }
        }

        // Urgency words
        if (body != null || subject != null) {
            String combined = (body != null ? body : "") + " " + (subject != null ? subject : "");
            for (String word : URGENCY_WORD_LIST) {
                if (combined.toLowerCase().contains(word)) {
                    score += URGENCY_WORDS;
                    details.put("urgency_words", URGENCY_WORDS);
                    break; // Only count once
                }
            }
        }

        // Missing authentication
        if (!hasAuth) {
            score += MISSING_AUTH;
            details.put("missing_auth", MISSING_AUTH);
        }

        LOG.fine("Spam score: " + score + " details: " + details);
        return Math.min(score, 100);
    }

    private String extractDomain(String header) {
        if (header == null) return null;
        // Handle both "Name <email>" and plain "email" formats
        int at = header.indexOf("@");
        if (at < 0) return null;
        int end = header.indexOf('>', at);
        if (end < 0) end = header.indexOf(' ', at);
        if (end < 0) end = header.length();
        return header.substring(at + 1, end).trim().toLowerCase();
    }

    private boolean isSuspiciousTld(String tld) {
        return tld.equals("biz") || tld.equals("click") || tld.equals("link") ||
               tld.equals("page") || tld.equals("site") || tld.equals("top");
    }

    private int countUpperCase(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isUpperCase(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
