package de.metis.mail.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class to sanitize HTML content for safe inclusion in emails.
 * Removes script tags, event handlers, and unsafe URL protocols while preserving a
 * whitelist of allowed formatting tags.
 *
 * Strategy: tag-by-tag rewriting. Disallowed tags are stripped (content kept),
 * allowed tags are kept but their attributes are filtered: only safe attributes
 * (href, src, alt, title, width, height) survive, and only with safe URL schemes.
 */
public class HtmlSanitizer {

    private static final Logger LOG = Logger.getLogger(HtmlSanitizer.class.getName());

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "div", "span", "a", "img", "table", "tr", "td", "th",
            "ul", "ol", "li", "b", "i", "u", "em", "strong", "br", "hr"
    );

    private static final Set<String> ALLOWED_ATTRS = Set.of(
            "href", "src", "alt", "title", "width", "height"
    );

    /** Matches an HTML tag (opening, closing, self-closing). */
    private static final Pattern TAG_PATTERN =
            Pattern.compile("<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");

    /** Matches one attribute: name plus optional value (quoted or bare). */
    private static final Pattern ATTR_PATTERN =
            Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*(?:=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?");

    /** Matches script/style blocks including content. */
    private static final Pattern BLOCK_PATTERN =
            Pattern.compile("<\\s*(script|style)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches HTML comments. */
    private static final Pattern COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Set<String> SAFE_URL_SCHEMES = Set.of("http", "https", "mailto", "cid", "ftp");

    /** Erlaubt nur eingebettete Rasterbilder (kein SVG, kein HTML) als data:-URI. */
    private static final Pattern DATA_IMAGE =
            Pattern.compile("^data:image/(png|jpe?g|gif|webp);base64,[a-z0-9+/]+=*$");

    /**
     * Sanitizes the provided HTML content. Never throws on malformed input.
     *
     * @param html The raw HTML string to sanitize.
     * @return The sanitized HTML string.
     */
    public String sanitize(String html) {
        return sanitize(html, false);
    }

    /**
     * M4-010: Vorschau-Modus. Mit {@code stripRemoteImages} verlieren
     * {@code <img src="http(s)|ftp:...">} ihr src-Attribut, damit die
     * JEditorPane-Ansicht (ohne CSP) keine Tracking-Pixel nachlaedt.
     * Alt-Text bleibt erhalten. data:/cid:/file: und relative Pfade bleiben unberuehrt.
     */
    public String sanitize(String html, boolean stripRemoteImages) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        List<String> removedItems = new ArrayList<>();
        remoteStripped = 0;
        String work = html;

        // 0. Strip comments (can hide conditional script tricks)
        work = COMMENT_PATTERN.matcher(work).replaceAll("");

        // 1. Remove script/style blocks including their content
        Matcher block = BLOCK_PATTERN.matcher(work);
        if (block.find()) {
            removedItems.add("script/style blocks");
            work = block.reset().replaceAll("");
        }

        // 2. Rewrite remaining tags: drop disallowed tags, filter attributes
        String result = rewrite(work, removedItems, stripRemoteImages);

        LOG.info("Sanitization complete. Removed: "
                + removedItems.stream().distinct().collect(Collectors.joining(", ")));
        return result;
    }

    /** Single-pass tag rewriting with proper tail handling. */
    private String rewrite(String work, List<String> removedItems, boolean stripRemoteImages) {
        Matcher m = TAG_PATTERN.matcher(work);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(work, last, m.start());
            last = m.end();

            boolean closing = "/".equals(m.group(1));
            String tagName = m.group(2).toLowerCase(Locale.ROOT);
            String rawAttrs = m.group(3) == null ? "" : m.group(3);
            boolean selfClosing = m.group().endsWith("/>");

            if (!ALLOWED_TAGS.contains(tagName)) {
                removedItems.add("tag:" + tagName);
                continue; // drop tag, keep surrounding text
            }
            if (closing) {
                sb.append("</").append(tagName).append('>');
            } else {
                sb.append('<').append(tagName)
                        .append(filterAttributes(rawAttrs, removedItems,
                                stripRemoteImages && "img".equals(tagName)));
                sb.append(selfClosing ? "/>" : ">");
            }
        }
        sb.append(work.substring(last));
        return sb.toString();
    }

    /** Keeps only whitelisted attributes with safe values. */
    private String filterAttributes(String rawAttrs, List<String> removedItems,
                                   boolean stripRemoteSrc) {
        if (rawAttrs == null || rawAttrs.isBlank()) {
            return "";
        }
        Matcher a = ATTR_PATTERN.matcher(rawAttrs);
        StringBuilder out = new StringBuilder();
        while (a.find()) {
            String name = a.group(1).toLowerCase(Locale.ROOT);
            String value = a.group(2) != null ? a.group(2)
                    : (a.group(3) != null ? a.group(3) : (a.group(4) != null ? a.group(4) : ""));

            if (!ALLOWED_ATTRS.contains(name)) {
                removedItems.add("attr:" + name);
                continue; // drops all on* handlers, style, etc.
            }
            if ("href".equals(name) && !isSafeUrl(value)) {
                removedItems.add("unsafe-url:" + name);
                continue;
            }
            if ("src".equals(name) && stripRemoteSrc && isRemoteUrl(value)) {
                remoteStripped++;
                removedItems.add("remote-img");
                continue; // M4-010: Tracking-Pixel gar nicht erst laden
            }
            if ("src".equals(name) && !isSafeUrl(value) && !isSafeDataImage(value)) {
                removedItems.add("unsafe-url:" + name);
                continue;
            }
            out.append(' ').append(name).append("=\"").append(escape(value)).append('"');
        }
        return out.toString();
    }

    /** http/https/ftp = entfernte Ressource (potenzielles Tracking). */
    private boolean isRemoteUrl(String url) {
        if (url == null) return false;
        String u = url.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[\u0000-\u0020]", "");
        return u.startsWith("http:") || u.startsWith("https:") || u.startsWith("ftp:");
    }

    /** Zaehler der im Vorschau-Modus unterdrueckten Remote-Bilder (fuer die UI). */
    private int remoteStripped;

    public int lastRemoteImagesStripped() {
        return remoteStripped;
    }

    private boolean isSafeUrl(String url) {
        if (url == null) return false;
        String u = url.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[\\u0000-\\u0020]", ""); // strip control chars/whitespace tricks
        if (u.startsWith("/") || u.startsWith("#") || u.startsWith("?")) {
            return true; // relative
        }
        int colon = u.indexOf(':');
        if (colon < 0) {
            return true; // no scheme => relative
        }
        String scheme = u.substring(0, colon);
        return SAFE_URL_SCHEMES.contains(scheme);
    }

    /** Erlaubt nur eingebettete Rasterbilder (kein SVG, kein HTML). */
    private boolean isSafeDataImage(String url) {
        if (url == null) return false;
        String u = url.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[\u0000-\u0020]", "");
        return u.length() <= 8_000_000 && DATA_IMAGE.matcher(u).matches();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}