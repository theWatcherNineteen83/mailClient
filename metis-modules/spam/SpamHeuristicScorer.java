package de.metis.mail.spam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class SpamHeuristicScorer {

    private static final Logger LOG = Logger.getLogger(SpamHeuristicScorer.class.getName());

    private static final Map<String, Integer> DEFAULT_WEIGHTS = new HashMap<>();
    private static final List<String> SUSPICIOUS_TLDS = List.of("ru", "cn", "kr", "tr", "xyz", "top", "click", "link", "gq", "cf");
    private static final List<String> URGENCY_WORDS = List.of("dringend", "urgent", "sofort", "immediate", "now", "action", "verify", "password", "pin", "code", "account", "suspend", "blocked", "win", "gewonnen", "lottery", "prize", "gift", "free", "gratis", "bonus");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\S]+|www\\.[\\S]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_RESULT_HEADER = Pattern.compile("^authentication-results\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_HEADER = Pattern.compile("^from\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETURN_PATH_HEADER = Pattern.compile("^return-path\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBJECT_HEADER = Pattern.compile("^subject\\s*:", Pattern.CASE_INSENSITIVE);

    static {
        DEFAULT_WEIGHTS.put("sender_mismatch", 20);
        DEFAULT_WEIGHTS.put("suspicious_tld", 15);
        DEFAULT_WEIGHTS.put("uppercase_subject", 10);
        DEFAULT_WEIGHTS.put("link_count", 10);
        DEFAULT_WEIGHTS.put("urgency_words", 15);
        DEFAULT_WEIGHTS.put("missing_auth", 15);
    }

    private final Map<String, Integer> weights;

    public SpamHeuristicScorer() {
        this.weights = new HashMap<>(DEFAULT_WEIGHTS);
    }

    public SpamHeuristicScorer(Map<String, Integer> customWeights) {
        this.weights = new HashMap<>(DEFAULT_WEIGHTS);
        if (customWeights != null) {
            this.weights.putAll(customWeights);
        }
    }

    public int score(String headerText, String body) {
        List<SpamIndicator> indicators = new ArrayList<>();
        int totalScore = 0;

        String fromAddress = extractEmailFromHeader(headerText, FROM_HEADER);
        String returnPathAddress = extractEmailFromHeader(headerText, RETURN_PATH_HEADER);
        String subject = extractHeaderValue(headerText, SUBJECT_HEADER);

        // 1. Check Sender Mismatch (From vs Return-Path)
        if (fromAddress != null && returnPathAddress != null) {
            String fromDomain = extractDomain(fromAddress);
            String returnPathDomain = extractDomain(returnPathAddress);
            if (fromDomain != null && returnPathDomain != null && !fromDomain.equals(returnPathDomain)) {
                int weight = weights.getOrDefault("sender_mismatch", 20);
                totalScore += weight;
                indicators.add(new SpamIndicator("sender_mismatch", fromDomain + " != " + returnPathDomain, weight));
                LOG.fine("Sender mismatch detected: " + fromDomain + " vs " + returnPathDomain);
            }
        }

        // 2. Check Suspicious TLDs in From Address
        if (fromAddress != null) {
            String domain = extractDomain(fromAddress);
            if (domain != null) {
                String tld = extractTld(domain);
                if (tld != null && SUSPICIOUS_TLDS.contains(tld.toLowerCase())) {
                    int weight = weights.getOrDefault("suspicious_tld", 15);
                    totalScore += weight;
                    indicators.add(new SpamIndicator("suspicious_tld", "TLD: " + tld, weight));
                    LOG.fine("Suspicious TLD detected: " + tld);
                }
            }
        }

        // 3. Check Uppercase Subject
        if (subject != null && !subject.isEmpty()) {
            String upperSubject = subject.toUpperCase();
            long upperCount = upperSubject.chars().filter(Character::isUpperCase).count();
            long totalChars = upperSubject.chars().filter(c -> Character.isLetterOrDigit(c)).count();
            if (totalChars > 0 && (upperCount / (double) totalChars) >= 0.5) {
                int weight = weights.getOrDefault("uppercase_subject", 10);
                totalScore += weight;
                indicators.add(new SpamIndicator("uppercase_subject", "Subject: " + subject, weight));
                LOG.fine("Uppercase subject detected: " + subject);
            }
        }

        // 4. Check Link Count in Body
        if (body != null) {
            var matcher = URL_PATTERN.matcher(body);
            int linkCount = 0;
            while (matcher.find()) {
                linkCount++;
            }
            if (linkCount >= 3) {
                int weight = weights.getOrDefault("link_count", 10);
                int addedScore = Math.min(weight, linkCount); // Cap score contribution
                totalScore += addedScore;
                indicators.add(new SpamIndicator("link_count", "Links: " + linkCount, addedScore));
                LOG.fine("High link count detected: " + linkCount);
            }
        }

        // 5. Check Urgency Words in Subject and Body
        String combinedText = (subject != null ? subject : "") + " " + (body != null ? body : "");
        String lowerText = combinedText.toLowerCase();
        int urgencyCount = 0;
        for (String word : URGENCY_WORDS) {
            if (lowerText.contains(word)) {
                urgencyCount++;
            }
        }
        if (urgencyCount >= 2) {
            int weight = weights.getOrDefault("urgency_words", 15);
            int addedScore = Math.min(weight, urgencyCount * 3);
            totalScore += addedScore;
            indicators.add(new SpamIndicator("urgency_words", "Count: " + urgencyCount, addedScore));
            LOG.fine("Urgency words detected: " + urgencyCount);
        }

        // 6. Check Missing Authentication-Results
        boolean hasAuthResults = false;
        String[] headerLines = headerText.split("\\r?\\n");
        for (String line : headerLines) {
            if (AUTH_RESULT_HEADER.matcher(line.trim()).matches()) {
                hasAuthResults = true;
                break;
            }
        }
        if (!hasAuthResults) {
            int weight = weights.getOrDefault("missing_auth", 15);
            totalScore += weight;
            indicators.add(new SpamIndicator("missing_auth", "No Authentication-Results header", weight));
            LOG.fine("Missing Authentication-Results header");
        }

        // Cap score at 100
        int finalScore = Math.min(100, totalScore);
        LOG.fine("Final spam score: " + finalScore + " with " + indicators.size() + " indicators");

        // Store indicators for retrieval (simple approach: return score, indicators via side-channel or static? 
        // Requirement says "Return Score plus List of Indicators". 
        // Since score() returns int, I need a method that returns both. 
        // Let's create a record or a simple wrapper? Or maybe the prompt implies the method should return a composite?
        // "Return Score plus List of Indicators" usually implies a result object.
        // Let's refactor to return a ScoreResult object.
        
        // Wait, the goal says "Return Score plus List of Indicators". 
        // If I change the signature, it's fine as long as it's meaningful.
        // Let's create a public static class SpamScoreResult.
        
        return finalScore; 
    }
    
    // Better approach: Return a result object
    public SpamScoreResult evaluate(String headerText, String body) {
        List<SpamIndicator> indicators = new ArrayList<>();
        int totalScore = 0;

        String fromAddress = extractEmailFromHeader(headerText, FROM_HEADER);
        String returnPathAddress = extractEmailFromHeader(headerText, RETURN_PATH_HEADER);
        String subject = extractHeaderValue(headerText, SUBJECT_HEADER);

        // 1. Check Sender Mismatch
        if (fromAddress != null && returnPathAddress != null) {
            String fromDomain = extractDomain(fromAddress);
            String returnPathDomain = extractDomain(returnPathAddress);
            if (fromDomain != null && returnPathDomain != null && !fromDomain.equals(returnPathDomain)) {
                int weight = weights.getOrDefault("sender_mismatch", 20);
                totalScore += weight;
                indicators.add(new SpamIndicator("sender_mismatch", fromDomain + " != " + returnPathDomain, weight));
            }
        }

        // 2. Check Suspicious TLDs
        if (fromAddress != null) {
            String domain = extractDomain(fromAddress);
            if (domain != null) {
                String tld = extractTld(domain);
                if (tld != null && SUSPICIOUS_TLDS.contains(tld.toLowerCase())) {
                    int weight = weights.getOrDefault("suspicious_tld", 15);
                    totalScore += weight;
                    indicators.add(new SpamIndicator("suspicious_tld", "TLD: " + tld, weight));
                }
            }
        }

        // 3. Check Uppercase Subject
        if (subject != null && !subject.isEmpty()) {
            String upperSubject = subject.toUpperCase();
            long upperCount = upperSubject.chars().filter(Character::isUpperCase).count();
            long totalChars = upperSubject.chars().filter(c -> Character.isLetterOrDigit(c)).count();
            if (totalChars > 0 && (upperCount / (double) totalChars) >= 0.5) {
                int weight = weights.getOrDefault("uppercase_subject", 10);
                totalScore += weight;
                indicators.add(new SpamIndicator("uppercase_subject", "Subject: " + subject, weight));
            }
        }

        // 4. Check Link Count
        if (body != null) {
            var matcher = URL_PATTERN.matcher(body);
            int linkCount = 0;
            while (matcher.find()) {
                linkCount++;
            }
            if (linkCount >= 3) {
                int weight = weights.getOrDefault("link_count", 10);
                int addedScore = Math.min(weight, linkCount);
                totalScore += addedScore;
                indicators.add(new SpamIndicator("link_count", "Links: " + linkCount, addedScore));
            }
        }

        // 5. Check Urgency Words
        String combinedText = (subject != null ? subject : "") + " " + (body != null ? body : "");
        String lowerText = combinedText.toLowerCase();
        int urgencyCount = 0;
        for (String word : URGENCY_WORDS) {
            if (lowerText.contains(word)) {
                urgencyCount++;
            }
        }
        if (urgencyCount >= 2) {
            int weight = weights.getOrDefault("urgency_words", 15);
            int addedScore = Math.min(weight, urgencyCount * 3);
            totalScore += addedScore;
            indicators.add(new SpamIndicator("urgency_words", "Count: " + urgencyCount, addedScore));
        }

        // 6. Check Missing Authentication-Results
        boolean hasAuthResults = false;
        String[] headerLines = headerText.split("\\r?\\n");
        for (String line : headerLines) {
            if (AUTH_RESULT_HEADER.matcher(line.trim()).matches()) {
                hasAuthResults = true;
                break;
            }
        }
        if (!hasAuthResults) {
            int weight = weights.getOrDefault("missing_auth", 15);
            totalScore += weight;
            indicators.add(new SpamIndicator("missing_auth", "No Authentication-Results header", weight));
        }

        int finalScore = Math.min(100, totalScore);
        return new SpamScoreResult(finalScore, indicators);
    }

    private String extractEmailFromHeader(String headerText, Pattern headerPattern) {
        if (headerText == null) return null;
        String[] lines = headerText.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (headerPattern.matcher(trimmed).matches()) {
                String value = trimmed.substring(headerPattern.pattern().length()).trim();
                // Extract email from value
                var matcher = DOMAIN_PATTERN.matcher(value);
                if (matcher.find()) {
                    String domain = matcher.group();
                    // Find local part
                    int atIndex = value.indexOf('@');
                    if (atIndex > -1) {
                        return value.substring(atIndex + 1).split("<|>")[0].trim();
                    }
                    return domain;
                }
            }
        }
        return null;
    }

    private String extractHeaderValue(String headerText, Pattern headerPattern) {
        if (headerText == null) return null;
        String[] lines = headerText.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (headerPattern.matcher(trimmed).matches()) {
                return trimmed.substring(headerPattern.pattern().length()).trim();
            }
        }
        return null;
    }

    private String extractDomain(String email) {
        if (email == null) return null;
        int atIndex = email.indexOf('@');
        if (atIndex > -1 && atIndex < email.length() - 1) {
            return email.substring(atIndex + 1);
        }
        return null;
    }

    private String extractTld(String domain) {
        if (domain == null) return null;
        int lastDot = domain.lastIndexOf('.');
        if (lastDot > -1 && lastDot < domain.length() - 1) {
            return domain.substring(lastDot + 1);
        }
        return null;
    }

    public record SpamIndicator(String type, String detail, int points) {}
    
    public record SpamScoreResult(int score, List<SpamIndicator> indicators) {
        public boolean isSpam() {
            return score >= 50; // Arbitrary threshold
        }
    }
}