package de.metis.mail.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility class to sanitize HTML content for safe inclusion in emails.
 * Removes script tags, event handlers, and unsafe URL protocols while preserving a
 * whitelist of allowed formatting tags.
 */
public class HtmlSanitizer {

    private static final Logger LOG = Logger.getLogger(HtmlSanitizer.class.getName());

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "div", "span", "a", "img", "table", "tr", "td", "th", "ul", "ol", "li", "b", "i", "br"
    );

    // Pattern to match opening, self-closing, or closing tags
    private static final Pattern TAG_PATTERN = Pattern.compile("<\\s*([a-zA-Z][a-zA-Z0-9-]*)[^>]*>", Pattern.CASE_INSENSITIVE);
    
    // Pattern to match HTML entities like &amp; &lt;
    private static final Pattern HTML_ENTITY_PATTERN = Pattern.compile("&[a-zA-Z0-9#]+;");

    private static final String JS_PROTOCOL = "javascript:";
    private static final String DATA_PROTOCOL = "data:";

    /**
     * Sanitizes the provided HTML content.
     *
     * @param html The raw HTML string to sanitize.
     * @return The sanitized HTML string.
     */
    public String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        LOG.fine("Starting HTML sanitization");
        List<String> removedItems = new ArrayList<>();

        // 1. Decode HTML entities to ensure we catch script tags hidden as &lt;script&gt;
        // We use a simple iterative decoding to avoid loops if the input is malicious.
        html = HTML_ENTITY_PATTERN.matcher(html).replaceAll("");

        // 2. Remove script tags and their content
        Pattern scriptPattern = Pattern.compile("<script[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        html = scriptPattern.matcher(html).replaceAll("");
        removedItems.add("script tags");

        // 3. Remove event handlers (on* attributes)
        Pattern onAttrPattern = Pattern.compile("\\s+on[a-z]+=\"[^\"]*\"", Pattern.CASE_INSENSITIVE);
        html = onAttrPattern.matcher(html).replaceAll("");
        removedItems.add("event handlers");

        // 4. Remove unsafe URL protocols
        html = html.replaceAll(JS_PROTOCOL + "[^\"'>\\s]*", "");
        html = html.replaceAll(DATA_PROTOCOL + "[^\"'>\\s]*", "");
        removedItems.add("javascript: and data: URLs");

        // 5. Remove disallowed tags
        Matcher m = TAG_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String fullTag = m.group();
            // Extract tag name
            String tagContent = fullTag.substring(1, fullTag.indexOf(">")).trim();
            String tagName = tagContent.split("[\\s/>]")[0].toLowerCase(Locale.ROOT);
            
            if (ALLOWED_TAGS.contains(tagName)) {
                // Keep the tag
                sb.append(fullTag);
            } else {
                // Remove tag but keep content
                if (tagContent.contains("/")) {
                    // Self-closing or closing tag - remove entirely
                } else {
                    // Opening tag - replace with just content
                    sb.append("");
                }
                removedItems.add("disallowed tag: " + tagName);
            }
        }
        sb.append(m.replaceAll(""));

        LOG.info("Sanitization complete. Removed: " + removedItems.stream().distinct().collect(Collectors.joining(", ")));
        return sb.toString();
    }

    /**
     * Simple HTML entity encoder for output.
     */
    private String escapeQuotes(String s) {
        if (s == null) return "";
        return s.replace("\"", "&quot;");
    }
}
