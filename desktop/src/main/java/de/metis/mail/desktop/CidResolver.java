package de.metis.mail.desktop;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ersetzt cid:-Verweise im BEREITS SANITISIERTEN HTML durch data:-URIs.
 * Nur Rasterbilder werden eingebettet; alles andere verliert das src-Attribut.
 */
public final class CidResolver {

    /** Ein Inline-Bild aus der Mail (contentId ohne spitze Klammern). */
    public record InlineImage(String contentId, String mimeType, byte[] data) { }

    private static final Pattern CID_SRC = Pattern.compile("(?i)\\bsrc=\"cid:([^\"]*)\"");
    private static final Set<String> ALLOWED =
            Set.of("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp");
    private static final int MAX_IMAGE_BYTES = 5_000_000;

    private CidResolver() { }

    public static String inline(String sanitizedHtml, List<InlineImage> images) {
        if (sanitizedHtml == null || !sanitizedHtml.toLowerCase(Locale.ROOT).contains("cid:")) {
            return sanitizedHtml;
        }
        Map<String, InlineImage> byCid = new HashMap<>();
        if (images != null) {
            for (InlineImage img : images) {
                if (img != null && img.contentId() != null && img.data() != null) {
                    byCid.putIfAbsent(img.contentId().toLowerCase(Locale.ROOT), img);
                }
            }
        }
        Map<String, String> encoded = new HashMap<>(); // Cache: jedes Bild nur einmal kodieren
        Matcher m = CID_SRC.matcher(sanitizedHtml);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String raw = m.group(1).replace("&amp;", "&");
            InlineImage img = byCid.get(raw.toLowerCase(Locale.ROOT));
            if (img == null) {
                img = byCid.get(decode(raw).toLowerCase(Locale.ROOT));
            }
            String uri = null;
            if (img != null) {
                final InlineImage found = img;
                uri = encoded.computeIfAbsent(
                        found.contentId().toLowerCase(Locale.ROOT), k -> toDataUri(found));
            }
            String replacement = uri == null ? "" : "src=\"" + uri + "\"";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String toDataUri(InlineImage img) {
        String mime = img.mimeType() == null ? ""
                : img.mimeType().split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(mime) || img.data().length > MAX_IMAGE_BYTES) {
            return null;
        }
        if (mime.equals("image/jpg")) {
            mime = "image/jpeg";
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(img.data());
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
