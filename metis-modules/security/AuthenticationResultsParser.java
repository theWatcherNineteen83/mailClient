package de.metis.mail.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuthenticationResultsParser {

    private static final Logger LOG = Logger.getLogger(AuthenticationResultsParser.class.getName());

    // Pre-compiled patterns for better performance
    // Pattern to match individual key-value pairs within the header value
    // Format: key="value" OR key=value (value can contain spaces but not quotes unless escaped)
    private static final Pattern PAIR_PATTERN = Pattern.compile(
        "(?i)\\b(spf|dkim|dmarc)\\s*(?:[=:]\\s*)?\"?([^\",;]+)\"?\\b"
    );

    // Pattern to extract the domain from the value
    // Typical values: "pass (example.com: domain of {ip} in SPF list)", "none", "fail (example.com: ...)"
    // We look for the first domain-like token inside parentheses or as the main token.
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
        "\\(\\s*([a-zA-Z0-9.-]+)\\s*:", 
        Pattern.CASE_INSENSITIVE
    );

    // Fallback pattern if no parentheses found: look for domain in the value directly
    private static final Pattern DOMAIN_FALLBACK_PATTERN = Pattern.compile(
        "\\b([a-zA-Z0-9.-]+)\\b"
    );

    public static Map<String, ProtocolResult> parse(String authenticationResultsHeader, String receivedSpfHeader) {
        Map<String, ProtocolResult> results = new HashMap<>();
        
        // Initialize with NONE status by default
        results.put("spf", new ProtocolResult(AuthStatus.NONE, null, null));
        results.put("dkim", new ProtocolResult(AuthStatus.NONE, null, null));
        results.put("dmarc", new ProtocolResult(AuthStatus.NONE, null, null));

        if (authenticationResultsHeader != null) {
            try {
                Map<String, String> rawResults = extractRawResults(authenticationResultsHeader);
                
                // Process SPF
                String spfValue = rawResults.get("spf");
                if (spfValue != null) {
                    ProtocolResult spfResult = parseProtocolValue("spf", spfValue);
                    results.put("spf", spfResult);
                }

                // Process DKIM
                String dkimValue = rawResults.get("dkim");
                if (dkimValue != null) {
                    ProtocolResult dkimResult = parseProtocolValue("dkim", dkimValue);
                    results.put("dkim", dkimResult);
                }

                // Process DMARC
                String dmarcValue = rawResults.get("dmarc");
                if (dmarcValue != null) {
                    ProtocolResult dmarcResult = parseProtocolValue("dmarc", dmarcValue);
                    results.put("dmarc", dmarcResult);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to parse Authentication-Results header", e);
            }
        }

        // Fallback/Override for SPF using Received-SPF header if Authentication-Results didn't provide it
        if (receivedSpfHeader != null) {
            ProtocolResult currentSpf = results.get("spf");
            if (currentSpf != null && currentSpf.getStatus() == AuthStatus.NONE) {
                try {
                    ProtocolResult spfFromReceived = parseReceivedSpf(receivedSpfHeader);
                    results.put("spf", spfFromReceived);
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Failed to parse Received-SPF header", e);
                }
            }
        }

        return results;
    }

    private static Map<String, String> extractRawResults(String headerValue) {
        Map<String, String> rawMap = new HashMap<>();
        String[] segments = headerValue.split(";");
        
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;
            
            // Find the first key: value or key="value"
            int separatorIndex = trimmed.indexOf('=');
            if (separatorIndex == -1) {
                separatorIndex = trimmed.indexOf(':');
            }
            
            if (separatorIndex > 0) {
                String key = trimmed.substring(0, separatorIndex).trim().toLowerCase();
                String value = trimmed.substring(separatorIndex + 1).trim();
                
                // Clean up quotes if present
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                
                if ("spf".equals(key) || "dkim".equals(key) || "dmarc".equals(key)) {
                    rawMap.put(key, value);
                }
            }
        }
        return rawMap;
    }

    private static ProtocolResult parseProtocolValue(String protocol, String value) {
        if (value == null || value.isEmpty()) {
            return new ProtocolResult(AuthStatus.NONE, null, value);
        }

        AuthStatus status = AuthStatus.NONE;
        String rawValue = value;
        
        // Determine status from the beginning of the string
        String statusStr = value.split("\\s")[0].toLowerCase();
        
        switch (statusStr) {
            case "pass":
                status = AuthStatus.PASS;
                break;
            case "fail":
                status = AuthStatus.FAIL;
                break;
            case "softfail":
                status = AuthStatus.SOFTFAIL;
                break;
            case "neutral":
                status = AuthStatus.NEUTRAL;
                break;
            case "none":
                status = AuthStatus.NONE;
                break;
            default:
                status = AuthStatus.NONE;
                break;
        }

        // Extract domain
        String domain = extractDomain(value);
        
        return new ProtocolResult(status, domain, rawValue);
    }

    private static ProtocolResult parseReceivedSpf(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return new ProtocolResult(AuthStatus.NONE, null, headerValue);
        }

        String trimmed = headerValue.trim();
        String[] parts = trimmed.split("\\s+");
        
        AuthStatus status = AuthStatus.NONE;
        String domain = null;
        
        if (parts.length > 0) {
            String statusStr = parts[0].toLowerCase();
            switch (statusStr) {
                case "pass": status = AuthStatus.PASS; break;
                case "fail": status = AuthStatus.FAIL; break;
                case "softfail": status = AuthStatus.SOFTFAIL; break;
                case "neutral": status = AuthStatus.NEUTRAL; break;
                case "none": status = AuthStatus.NONE; break;
                case "temperror": status = AuthStatus.NEUTRAL; break;
                case "permerror": status = AuthStatus.NEUTRAL; break;
            }
        }

        // Try to find domain in parentheses or as "domain="
        Pattern domainPattern = Pattern.compile("domain\\s*[:=]\\s*([a-zA-Z0-9.-]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = domainPattern.matcher(trimmed);
        if (m.find()) {
            domain = m.group(1);
        } else {
            // Fallback: look for domain in parentheses
            Pattern parenPattern = Pattern.compile("\\(\\s*([a-zA-Z0-9.-]+)", Pattern.CASE_INSENSITIVE);
            Matcher pm = parenPattern.matcher(trimmed);
            if (pm.find()) {
                domain = pm.group(1);
            }
        }

        return new ProtocolResult(status, domain, trimmed);
    }

    private static String extractDomain(String value) {
        // Prefer domain inside parentheses as it's the standard RFC 7601 format
        Matcher m = DOMAIN_PATTERN.matcher(value);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }
        
        // Fallback: if no parentheses, try to find a domain-like string
        // This is less reliable but better than null
        Matcher fm = DOMAIN_FALLBACK_PATTERN.matcher(value);
        if (fm.find()) {
            String candidate = fm.group(1);
            // Basic sanity check: must contain a dot and look like a domain
            if (candidate.contains(".") && candidate.matches(".*[a-zA-Z]{2,}")) {
                return candidate.toLowerCase();
            }
        }
        
        return null;
    }

    public enum AuthStatus {
        PASS,
        FAIL,
        SOFTFAIL,
        NEUTRAL,
        NONE
    }

    public static class ProtocolResult {
        private final AuthStatus status;
        private final String domain;
        private final String rawText;

        public ProtocolResult(AuthStatus status, String domain, String rawText) {
            this.status = status;
            this.domain = domain;
            this.rawText = rawText;
        }

        public AuthStatus getStatus() {
            return status;
        }

        public String getDomain() {
            return domain;
        }

        public String getRawText() {
            return rawText;
        }

        @Override
        public String toString() {
            return "ProtocolResult{status=" + status + ", domain='" + domain + "', rawText='" + rawText + "'}";
        }
    }
}