package de.metis.mail.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Authentication-Results headers (RFC 8601).
 * Extracts SPF, DKIM, and DMARC verification results including status and domain.
 *
 * <p>Example header value:
 * {@code mx.example.com; spf=pass smtp.mailfrom=example.com; dkim=pass header.d=example.com; dmarc=pass}
 */
public class AuthenticationResultsParser {

    private static final Logger LOG = Logger.getLogger(AuthenticationResultsParser.class.getName());

    /** Matches a method=result pair such as "spf=pass", "dkim=none", "dmarc=softfail". */
    private static final Pattern RESULT_PATTERN =
            Pattern.compile("\\b(spf|dkim|dmarc|dkim-atps)\\s*=\\s*([a-z]+)", Pattern.CASE_INSENSITIVE);

    /** Domain extraction keys in priority order. */
    private static final String[] DOMAIN_KEYS = {"smtp.mailfrom=", "header.d=", "header.from=", "smtp.helo="};

    /**
     * Authentication result for a single protocol.
     */
    public static class AuthResult {
        public final String protocol;
        public final String status;
        public final String domain;

        public AuthResult(String protocol, String status, String domain) {
            this.protocol = protocol;
            this.status = status;
            this.domain = domain;
        }

        @Override
        public String toString() {
            return protocol + ": " + status + " (domain: " + domain + ")";
        }
    }

    /**
     * Parse an Authentication-Results header value.
     *
     * @param headerValue The raw header value
     * @return List of AuthResult objects (empty for null/blank/unparsable input)
     */
    public List<AuthResult> parse(String headerValue) {
        List<AuthResult> results = new ArrayList<>();
        if (headerValue == null || headerValue.isBlank()) {
            return results;
        }

        LOG.fine("Parsing Authentication-Results: " + headerValue);

        // Split on semicolons to get individual authentication result clauses
        String[] parts = headerValue.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            try {
                AuthResult result = parsePart(part);
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                LOG.warning("Failed to parse auth result part: " + part);
            }
        }

        return results;
    }

    private AuthResult parsePart(String part) {
        Matcher m = RESULT_PATTERN.matcher(part);
        if (!m.find()) {
            return null; // no method=result pair => not an auth result clause
        }
        String protocol = m.group(1).toLowerCase(Locale.ROOT);
        String status = m.group(2).toLowerCase(Locale.ROOT);
        String domain = extractDomain(part);
        return new AuthResult(protocol, status, domain);
    }

    private String extractDomain(String part) {
        for (String key : DOMAIN_KEYS) {
            int idx = part.indexOf(key);
            if (idx < 0) continue;
            int start = idx + key.length();
            int end = start;
            while (end < part.length() && !Character.isWhitespace(part.charAt(end))
                    && part.charAt(end) != ';' && part.charAt(end) != ')') {
                end++;
            }
            if (end > start) {
                return part.substring(start, end).trim();
            }
        }
        return null;
    }
}
