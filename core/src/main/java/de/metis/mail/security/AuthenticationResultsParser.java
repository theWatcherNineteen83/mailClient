package de.metis.mail.security;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Parser for Authentication-Results headers.
 * Extracts SPF, DKIM, and DMARC verification results.
 */
public class AuthenticationResultsParser {

    private static final Logger LOG = Logger.getLogger(AuthenticationResultsParser.class.getName());

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
     * @return List of AuthResult objects
     */
    public List<AuthResult> parse(String headerValue) {
        List<AuthResult> results = new ArrayList<>();
        if (headerValue == null || headerValue.isEmpty()) {
            return results;
        }

        LOG.fine("Parsing Authentication-Results: " + headerValue);

        // Split on semicolons to get individual authentication results
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
        // Format: smtp.mailfrom=example.com (spf)
        // Format: header.d=example.com (dkim)
        // Format: header.from=example.com (dmarc)
        
        // Extract protocol and domain
        String protocol = null;
        String domain = null;
        String status = null;

        // Look for "smtp.mailfrom=" (SPF)
        if (part.contains("smtp.mailfrom=")) {
            protocol = "spf";
            int idx = part.indexOf("smtp.mailfrom=");
            int end = part.indexOf(' ', idx);
            if (end == -1) end = part.length();
            domain = part.substring(idx + 14, end).trim();
            status = extractStatus(part);
        }
        // Look for "header.d=" (DKIM)
        else if (part.contains("header.d=")) {
            protocol = "dkim";
            int idx = part.indexOf("header.d=");
            int end = part.indexOf(' ', idx);
            if (end == -1) end = part.length();
            domain = part.substring(idx + 9, end).trim();
            status = extractStatus(part);
        }
        // Look for "header.from=" (DMARC)
        else if (part.contains("header.from=")) {
            protocol = "dmarc";
            int idx = part.indexOf("header.from=");
            int end = part.indexOf(' ', idx);
            if (end == -1) end = part.length();
            domain = part.substring(idx + 12, end).trim();
            status = extractStatus(part);
        }

        if (protocol != null) {
            return new AuthResult(protocol, status, domain);
        }

        return null;
    }

    private String extractStatus(String part) {
        // Look for status=... 
        if (part.contains("status=")) {
            int idx = part.indexOf("status=");
            int end = part.indexOf(' ', idx);
            if (end == -1) end = part.length();
            return part.substring(idx + 7, end).trim();
        }
        // Look for pass/fail/softfail
        if (part.contains("pass")) return "pass";
        if (part.contains("fail")) return "fail";
        if (part.contains("softfail")) return "softfail";
        if (part.contains("neutral")) return "neutral";
        if (part.contains("temperror")) return "temperror";
        if (part.contains("permerror")) return "permerror";
        
        return "unknown";
    }
}
