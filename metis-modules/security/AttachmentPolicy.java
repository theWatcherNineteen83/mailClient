package de.metis.mail.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Sicherheitsrichtlinie zur Prüfung von E-Mail-Anhängen.
 *
 * <p>Diese Klasse evaluiert Dateinamen und -inhalte basierend auf einer Blockliste
 * gefährlicher Endungen sowie der Erkennung von Verschleierungsversuchen
 * (z. B. doppelte Endungen wie <code>malware.pdf.exe</code>).</p>
 */
public class AttachmentPolicy {

    private static final Logger LOG = Logger.getLogger(AttachmentPolicy.class.getName());

    /**
     * Ergebnis-Enumeration für die Sicherheitsprüfung.
     */
    public enum Verdict {
        ERLAUBT,
        VERDAECHTIG,
        GESPERRT
    }

    /**
     * Datenklasse für das Prüfungsresultat, inkl. Begründung.
     */
    public record Result(Verdict verdict, String reason) {

        public boolean isBlocked() {
            return verdict == Verdict.GESPERRT;
        }

        public boolean isSuspicious() {
            return verdict == Verdict.VERDAECHTIG;
        }
    }

    // Endungen, die direkt gesperrt werden
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "msi", "scr", "com", "pif", "vbs", "js", "jsm", "hta", "wsf", "lnk", "jar"
    );

    // Regulaerer Ausdruck zur Erkennung mehrfacher Punkt-Endungen im Dateinamen
    private static final Pattern DOUBLE_EXTENSION_PATTERN = Pattern.compile("\\.[A-Za-z0-9]{1,5}\\.[A-Za-z0-9]{1,5}$");

    private final MessageDigest sha256Digest;

    public AttachmentPolicy() {
        try {
            this.sha256Digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist im JDK nicht verfügbar", e);
        }
    }

    /**
     * Hauptmethode zur Prüfung eines Anhangs.
     *
     * @param fileName Name der Datei (kann null oder leer sein)
     * @param fileData Binärinhalt der Datei (kann null sein)
     * @return Das Resultat mit Urteil und Begründung
     */
    public Result checkAttachment(String fileName, byte[] fileData) {
        if (fileName == null || fileName.isBlank()) {
            LOG.log(Level.FINE, "Dateiname fehlt - Anhang wird als VERDAECHTIG eingestuft");
            return new Result(Verdict.VERDAECHTIG, "Dateiname fehlt oder ist leer");
        }

        String normalizedFileName = fileName.trim().toLowerCase(Locale.ROOT);

        // 1. Prüfung auf doppelte Endungen (z.B. evil.pdf.exe)
        if (hasDoubleExtension(normalizedFileName)) {
            LOG.log(Level.WARNING, "Doppelte Endung erkannt in: {0}", normalizedFileName);
            return new Result(Verdict.GESPERRT, "Doppelte Endung erkannt (möglicher Verschleierungsversuch)");
        }

        // 2. Extrahierung der letzten Endung
        String extension = extractExtension(normalizedFileName);

        if (extension == null || extension.isEmpty()) {
            LOG.log(Level.FINE, "Keine Endung für Datei {0} gefunden - als VERDAECHTIG markiert", normalizedFileName);
            return new Result(Verdict.VERDAECHTIG, "Keine Dateiendung vorhanden");
        }

        // 3. Blocklistenprüfung
        if (BLOCKED_EXTENSIONS.contains(extension)) {
            LOG.log(Level.WARNING, "Gesperrte Endung {0} erkannt in Datei {1}", new Object[]{extension, normalizedFileName});
            return new Result(Verdict.GESPERRT, "Dateiendung .".concat(extension) + " ist auf der Blockliste");
        }

        // 4. Optional: Hash-Prüfung oder weitere heuristische Checks könnten hier ergänzt werden
        if (fileData != null && fileData.length > 0) {
            String hash = calculateSha256Hex(fileData);
            LOG.log(Level.FINE, "SHA-256 Hash berechnet: {0}", hash);
            // Hier könnten bekannte bösartige Hashes geprüft werden
        }

        LOG.log(Level.FINE, "Anhang {0} bestanden - ERLAUBT", normalizedFileName);
        return new Result(Verdict.ERLAUBT, "Dateiendung ist zulässig und keine Sicherheitsmerkmale erkannt");
    }

    /**
     * Prüft, ob ein Dateiname eine doppelte Endung aufweist.
     *
     * @param fileName Normalisierter, klein geschriebener Dateiname
     * @return true, falls eine doppelte Endung erkannt wird
     */
    private boolean hasDoubleExtension(String fileName) {
        return DOUBLE_EXTENSION_PATTERN.matcher(fileName).matches();
    }

    /**
     * Extrahiert die letzte Dateiendung aus dem Dateinamen.
     *
     * @param fileName Normalisierter Dateiname
     * @return Die Endung ohne Punkt, oder null falls keine vorhanden
     */
    private String extractExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            return fileName.substring(lastDotIndex + 1);
        }
        return null;
    }

    /**
     * Berechnet den SHA-256 Hex-Hash der übergebenen Daten.
     *
     * @param data Die zu haschenden Bytes
     * @return Der Hex-String des SHA-256 Hashes
     */
    public String calculateSha256Hex(byte[] data) {
        if (data == null) {
            return "";
        }
        sha256Digest.reset();
        byte[] hashBytes = sha256Digest.digest(data);
        StringBuilder hexString = new StringBuilder(64);
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Stellt eine neue Instanz der Policy mit Standardkonfiguration bereit.
     *
     * @return Neue AttachmentPolicy-Instanz
     */
    public static AttachmentPolicy createDefault() {
        return new AttachmentPolicy();
    }
}