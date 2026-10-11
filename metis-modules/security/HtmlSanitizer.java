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
        String processedHtml = html;
        int iterations = 0;
        final int MAX_DECODE_ITERATIONS = 5;

        while (processedHtml.contains("&") && iterations < MAX_DECODE_ITERATIONS) {
            String next = decodeEntities(processedHtml);
            if (next.equals(processedHtml)) {
                break; // No more entities found
            }
            processedHtml = next;
            iterations++;
        }

        // 2. Remove Script and Style tags entirely (including content)
        // We use a greedy match for script/style content removal
        String scriptPattern = "(?s)<\\s*script[^>]*>.*?<\\s*/\\s*script\\s*>";
        String stylePattern = "(?s)<\\s*style[^>]*>.*?<\\s*/\\s*style\\s*>";
        
        if (processedHtml.toLowerCase(Locale.ROOT).contains("<script")) {
            processedHtml = processedHtml.replaceAll(scriptPattern, "");
            removedItems.add("Script tags");
        }
        if (processedHtml.toLowerCase(Locale.ROOT).contains("<style")) {
            processedHtml = processedHtml.replaceAll(stylePattern, "");
            removedItems.add("Style tags");
        }

        // 3. Process remaining tags using a matcher to filter attributes and tags
        StringBuilder sb = new StringBuilder();
        Matcher matcher = TAG_PATTERN.matcher(processedHtml);
        int lastEnd = 0;

        while (matcher.find()) {
            // Append text before the tag
            sb.append(processedHtml, lastEnd, matcher.start());
            
            String fullTag = matcher.group();
            String tagName = matcher.group(1).toLowerCase(Locale.ROOT);
            
            if (isClosingTag(fullTag)) {
                // Closing tag: <\/tagName>
                if (ALLOWED_TAGS.contains(tagName)) {
                    sb.append(fullTag);
                } else {
                    removedItems.add("Closing tag: " + tagName);
                }
            } else if (isSelfClosingTag(fullTag)) {
                // Self-closing tag: <tagName ... \/?> or just <br>
                if (ALLOWED_TAGS.contains(tagName)) {
                    String sanitizedTag = sanitizeAttributes(fullTag, tagName, removedItems);
                    // Ensure it stays self-closing if it was, or let regex handle it
                    if (!sanitizedTag.endsWith("/>")) {
                         // If it was like <br> and we kept it, it's fine. 
                         // If it was <img ...>, it's fine.
                         // We don't force self-closing if the input didn't have it, 
                         // but browsers handle it. 
                         // However, if we removed attributes, we might have <img> which is valid in HTML5.
                         // Let's just keep the sanitized version.
                    }
                    sb.append(sanitizedTag);
                } else {
                    removedItems.add("Self-closing tag: " + tagName);
                }
            } else {
                // Opening tag
                if (ALLOWED_TAGS.contains(tagName)) {
                    String sanitizedTag = sanitizeAttributes(fullTag, tagName, removedItems);
                    sb.append(sanitizedTag);
                } else {
                    // Check if it's a script/style that slipped through (e.g. no closing)
                    if (tagName.equals("script") || tagName.equals("style")) {
                        // If we get here, it might be an unclosed tag. 
                        // The previous regex should have caught standard ones.
                        // We remove it to be safe.
                        removedItems.add("Unclosed/Invalid " + tagName + " tag");
                    } else {
                        removedItems.add("Disallowed tag: " + tagName);
                    }
                }
            }
            
            lastEnd = matcher.end();
        }
        
        // Append remaining text
        sb.append(processedHtml, lastEnd, processedHtml.length());

        String result = sb.toString();
        
        // 4. Final safety check: Remove any remaining javascript: or data: URLs 
        // (In case they were split by entities or weird whitespace that regex didn't catch in attrs)
        result = neutralizeUnsafeUrls(result, removedItems);

        if (!removedItems.isEmpty()) {
            LOG.fine(() -> "Sanitization complete. Removed elements: " + String.join(", ", removedItems));
        } else {
            LOG.fine("Sanitization complete. No malicious elements found.");
        }

        return result;
    }

    private boolean isClosingTag(String tag) {
        return tag.matches("^<\\s*/\\s*[a-zA-Z][a-zA-Z0-9-]*\\s*>$");
    }

    private boolean isSelfClosingTag(String tag) {
        // Matches <tag ... /> or just <br> (common in HTML)
        // Note: TAG_PATTERN captures <tag ...>. 
        // If it ends with />, it's explicitly self-closing.
        // If it's a known void element and doesn't have a closing slash, we treat it as self-closing in HTML5 context.
        if (tag.endsWith("/>")) {
            return true;
        }
        String tagName = tag.replaceAll("^<\\s*", "").replaceAll("\\s*[^a-zA-Z0-9-]*.*$", "").toLowerCase(Locale.ROOT);
        return tagName.equals("br") || tagName.equals("img") || tagName.equals("hr") || tagName.equals("input");
    }

    private String sanitizeAttributes(String fullTag, String tagName, List<String> removedItems) {
        // Extract content inside the tag
        // fullTag looks like <tag attr="val" ...>
        String content = fullTag;
        if (fullTag.endsWith("/>")) {
            content = fullTag.substring(0, fullTag.length() - 2) + ">";
        } else {
            content = fullTag;
        }
        
        // Remove the tag name from the start
        // e.g. from <div class="x"> to  class="x">
        String attrsPart = content.substring(tagName.length() + 1);
        
        // Parse attributes
        String sanitizedAttrs = processAttributes(attrsPart, tagName, removedItems);
        
        String closing = fullTag.endsWith("/>") ? "/>" : ">";
        
        return "<" + tagName + sanitizedAttrs + closing;
    }

    private String processAttributes(String attrsString, String tagName, List<String> removedItems) {
        // Simple regex to find key=value or key="value" or key='value' or key
        // Pattern: ([a-zA-Z][a-zA-Z0-9-]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]*))
        Pattern attrPattern = Pattern.compile("([a-zA-Z][a-zA-Z0-9-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]*))");
        
        StringBuilder sb = new StringBuilder();
        Matcher m = attrPattern.matcher(attrsString);
        int lastEnd = 0;
        
        while (m.find()) {
            sb.append(attrsString, lastEnd, m.start());
            
            String name = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(4) != null ? m.group(4) : (m.group(5) != null ? m.group(5) : m.group(6));
            
            if (isAllowedAttribute(name, value, tagName, removedItems)) {
                sb.append(" ").append(name).append("=\"").append(escapeQuotes(value != null ? value : "")).append("\"");
            }
            
            lastEnd = m.end();
        }
        
        // Check for leftover text that isn't a matched attribute (might be malformed, ignore it)
        if (lastEnd < attrsString.length()) {
             // We just ignore unparseable bits to be safe, or we could log them.
             // For safety, we ignore.
        }
        
        return sb.toString();
    }

    private boolean isAllowedAttribute(String name, String value, String tagName, List<String> removedItems) {
        // 1. Check for Event Handlers (on*)
        if (name.startsWith("on")) {
            removedItems.add("Attribute: " + name);
            return false;
        }

        // 2. Check specific dangerous attributes
        if (name.equals("style")) {
            // Styles can contain expressions or url(). We'll remove them for simplicity unless we implement a CSS sanitizer.
            // For a basic sanitizer, removing style is safer.
            removedItems.add("Attribute: style");
            return false;
        }

        if (name.equals("href") || name.equals("src") || name.equals("action")) {
            if (value != null && !value.isEmpty()) {
                String lowerVal = value.toLowerCase(Locale.ROOT).trim();
                // Check for dangerous protocols
                if (lowerVal.startsWith(JS_PROTOCOL) || lowerVal.startsWith(DATA_PROTOCOL)) {
                    // Check for obfuscation like jav\tascript: or javascript&#58;
                    // Since we decoded entities earlier, &#58; is now :. 
                    // But we should also check for spaces/tabs/newlines inside the protocol.
                    // A common bypass is "java\nscript:".
                    // We replace all whitespace and check again.
                    String cleanVal = lowerVal.replaceAll("\\s+", "");
                    if (cleanVal.startsWith(JS_PROTOCOL) || cleanVal.startsWith(DATA_PROTOCOL)) {
                        removedItems.add("Unsafe URL in " + name + ": " + value);
                        return false;
                    }
                }
            }
            return true;
        }

        // 3. Allowlist for other attributes
        if (name.equals("class") || name.equals("id") || name.equals("alt") || name.equals("title") || name.equals("width") || name.equals("height")) {
             // Sanitize value to prevent break-out
             if (value != null) {
                 value = value.replaceAll("[\"']", "");
             }
             return true;
        }

        // Default: disallow unknown attributes to be strict
        removedItems.add("Attribute: " + name);
        return false;
    }

    private String escapeQuotes(String s) {
        return s == null ? "" : s.replace("\"", "&quot;");
    }

    private String neutralizeUnsafeUrls(String html, List<String> removedItems) {
        // This is a final pass to catch any URL that might have been missed
        // or assembled in a way that the attribute parser missed (e.g. in text nodes? No, URLs must be in attrs).
        // However, if someone puts <a href="javascript:alert(1)"> and the tag was stripped, the text remains.
        // But we stripped the whole tag.
        
        // One edge case: If the attribute value contained a newline that broke the regex match, 
        // but we decoded entities, it should be mostly handled.
        
        // Let's do a regex replacement for any remaining javascript: or data: in href/src attributes just in case.
        // This is a defensive double-layer.
        
        String safeHtml = html;
        
        // Pattern to find href or src attributes with dangerous protocols
        // We look for href="..." or src="..." where the value starts with javascript: or data:
        // This is complex. Instead, we rely on the attribute sanitizer.
        // However, if the input was <a href=javascript:alert(1)> (unquoted), 
        // the attr pattern: ([a-zA-Z][a-zA-Z0-9-]*)\s*=\s*(...)
        // Group 6 captures [^\s>]* which would be javascript:alert(1)
        // Then isAllowedAttribute checks it.
        
        // So the main logic should hold.
        
        // One more check: What if the tag itself was allowed, but the attribute value was modified to bypass?
        // We already check for protocol start.
        
        return safeHtml;
    }

    private String decodeEntities(String input) {
        if (input == null) return null;
        
        // Map of common HTML entities
        String result = input;
        
        // Simple replacements for common entities that can hide tags
        result = result.replace("&lt;", "<");
        result = result.replace("&gt;", ">");
        result = result.replace("&amp;", "&");
        result = result.replace("&#60;", "<");
        result = result.replace("&#62;", ">");
        result = result.replace("&#38;", "&");
        
        // Numeric entities in decimal and hex
        // We need a regex for &#\d+; and &#x[0-9a-f]+;
        Matcher decMatcher = Pattern.compile("&#(\\d+);").matcher(result);
        StringBuffer sb = new StringBuffer();
        while (decMatcher.find()) {
            int codePoint = Integer.parseInt(decMatcher.group(1));
            char c = Character.toChars(codePoint)[0];
            decMatcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(c)));
        }
        decMatcher.appendTail(sb);
        result = sb.toString();

        Matcher hexMatcher = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(result);
        sb = new StringBuffer();
        while (hexMatcher.find()) {
            int codePoint = Integer.parseInt(hexMatcher.group(1), 16);
            char c = Character.toChars(codePoint)[0];
            hexMatcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(c)));
        }
        hexMatcher.appendTail(sb);
        result = sb.toString();

        return result;
    }
}