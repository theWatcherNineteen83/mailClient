package de.metis.mail.desktop;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * Wie {@link #inline(String, List)}, schreibt aber jedes eingebettete Rasterbild
     * als echte Datei in {@code dir} und ersetzt den cid:-Verweis durch eine
     * file://-URI. Das ist der Pfad fuer die integrierte JEditorPane-Ansicht, die
     * data:-URIs nicht aufloest, aber file:// lokal laden kann.
     *
     * <p>Sicherheit: Nur {@link #ALLOWED}-Rasterbilder unter {@link #MAX_IMAGE_BYTES}
     * werden geschrieben; der Dateiname wird strikt auf [A-Za-z0-9._-] begrenzt, damit
     * keine Pfad- oder Traversal-Spielerei moeglich ist.
     */
    public static String solveToFiles(String sanitizedHtml, List<InlineImage> images, Path dir) {
        return solveToFiles(sanitizedHtml, images, dir, "msg");
    }

    public static String solveToFiles(String sanitizedHtml, List<InlineImage> images, Path dir, String msgKey) {
        if (sanitizedHtml == null || !sanitizedHtml.toLowerCase(Locale.ROOT).contains("cid:")) {
            return sanitizedHtml;
        }
        Path msgDir = dir.resolve(sanitizeSegment(msgKey));
        Map<String, InlineImage> byCid = new HashMap<>();
        if (images != null) {
            for (InlineImage img : images) {
                if (img != null && img.contentId() != null && img.data() != null) {
                    byCid.putIfAbsent(img.contentId().toLowerCase(Locale.ROOT), img);
                }
            }
        }
        Map<String, String> fileUri = new HashMap<>();
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
                uri = fileUri.computeIfAbsent(
                        found.contentId().toLowerCase(Locale.ROOT), k -> writeImageFile(found, msgDir));
            }
            String replacement = uri == null ? "" : "src=\"" + uri + "\"";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String sanitizeSegment(String s) {
        if (s == null || s.isBlank()) return "msg";
        String safe = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "msg" : safe;
    }

    private static String writeImageFile(InlineImage img, Path dir) {
        String mime = img.mimeType() == null ? ""
                : img.mimeType().split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(mime) || img.data().length > MAX_IMAGE_BYTES) {
            return null;
        }
        String ext = switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "bin";
        };
        String safe = img.contentId().replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) safe = "img";
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(safe + "." + ext);
            Files.write(file, img.data());
            return file.toUri().toString();
        } catch (IOException e) {
            return null;
        }
    }
}
