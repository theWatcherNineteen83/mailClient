package de.metis.mail.spam;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Local spam heuristic scoring engine.
 * Uses multiple independent signals and sums their weights.
 */
public class SpamHeuristicScorer {

    private static final Logger LOG = Logger.getLogger(SpamHeuristicScorer.class.getName());

    /**
     * Spam signal detected during scoring.
     */
    public static class SpamSignal {
        public final String name;
        public final int weight;
        public final String reason;

        public SpamSignal(String name, int weight, String reason) {
            this.name = name;
            this.weight = weight;
            this.reason = reason;
        }
    }

    /**
     * Score an email based on multiple heuristics.
     *
     * @param email The email message data
     * @return Map containing "score" (int 0-100) and "signals" (list of SpamSignal)
     */
    public Map<String, Object> score(Map<String, Object> email) {
        Map<String, Object> result = new HashMap<>();
        int score = 0;
        java.util.List<SpamSignal> signals = new java.util.ArrayList<>();

        String from = (String) email.get("from");
        String subject = (String) email.get("subject");
        String body = (String) email.get("body");
        String to = (String) email.get("to");
        String sender = (String) email.get("sender");
        boolean hasAttachments = (boolean) email.getOrDefault("has_attachments", false);
        boolean isHtml = (boolean) email.getOrDefault("is_html", false);

        // 1. Sender domain mismatch (high weight)
        if (from != null && to != null && !from.equals(to)) {
            if (!from.contains("@" + to.substring(to.indexOf("@") + 1))) {
                score += 15;
                signals.add(new SpamSignal("sender_mismatch", 15, "From domain differs from recipient domain"));
            }
        }

        // 2. Suspicious TLD (high weight)
        if (from != null) {
            String domain = from.substring(from.indexOf("@") + 1).toLowerCase();
            if (domain.endsWith(".biz") || domain.endsWith(".info") || domain.endsWith(".click") || 
                domain.endsWith(".loan") || domain.endsWith(".trade")) {
                score += 15;
                signals.add(new SpamSignal("suspicious_tld", 15, "Suspicious TLD: " + domain));
            }
        }

        // 3. Subject analysis (medium-high weight)
        if (subject != null && !subject.isEmpty()) {
            // All uppercase subject
            if (subject.equals(subject.toUpperCase()) && subject.length() > 10) {
                score += 10;
                signals.add(new SpamSignal("uppercase_subject", 10, "Subject is all uppercase"));
            }
            
            // Multiple exclamation marks
            if (subject.chars().filter(c -> c == '!').count() > 2) {
                score += 8;
                signals.add(new SpamSignal("excessive_exclamation", 8, "Multiple exclamation marks in subject"));
            }
        }

        // 4. Link density (high weight)
        if (body != null) {
            int linkCount = (int) body.chars().filter(c -> c == 'h').count();
            int httpCount = body.split("http").length - 1;
            if (httpCount > 5) {
                score += 15;
                signals.add(new SpamSignal("high_link_count", 15, "More than 5 links in body"));
            }
        }

        // 5. Urgency words (high weight)
        if (body != null) {
            String lower = body.toLowerCase();
            if (lower.contains("urgent") || lower.contains("asap") || lower.contains("immediately")) {
                score += 12;
                signals.add(new SpamSignal("urgency_words", 12, "Urgency words detected"));
            }
            if (lower.contains("act now") || lower.contains("limited time")) {
                score += 10;
                signals.add(new SpamSignal("urgency_call", 10, "Call to action urgency"));
            }
        }

        // 6. Missing authentication (medium weight)
        if (!email.containsKey("spf") && !email.containsKey("dkim") && !email.containsKey("dmarc")) {
            score += 10;
            signals.add(new SpamSignal("missing_auth", 10, "No SPF/DKIM/DMARC headers found"));
        }

        // 7. Attachments (medium weight)
        if (hasAttachments) {
            score += 5;
            signals.add(new SpamSignal("has_attachments", 5, "Email has attachments"));
        }

        // 8. HTML with suspicious elements
        if (isHtml && body != null && body.contains("<script")) {
            score += 10;
            signals.add(new SpamSignal("html_script", 10, "HTML contains script tags"));
        }

        // Cap score at 100
        score = Math.min(score, 100);

        result.put("score", score);
        result.put("signals", signals);

        LOG.info("Spam score: " + score + "/100 (signals: " + signals.size() + ")");
        return result;
    }
}
