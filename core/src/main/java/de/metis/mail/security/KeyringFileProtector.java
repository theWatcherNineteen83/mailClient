package de.metis.mail.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Schuetzt Geheimnisse mit einem lokalen Keyring-Schluessel (M4-003).
 * Der Schluessel liegt in einer eigenen Datei neben der Datenbank (Rechte 0600)
 * und wird beim ersten Start zufaellig erzeugt; verschluesselte Werte tragen
 * das Praefix enc1:. Grundlage ist {@link MailKeyring} (AES-256-GCM, PBKDF2).
 */
public final class KeyringFileProtector implements SecretProtector {

    public static final String PREFIX = "enc1:";
    private static final int KEY_BYTES = 32;

    private final MailKeyring keyring = new MailKeyring();
    private final Path keyFile;
    private final String master;

    public KeyringFileProtector(Path keyFile) throws IOException {
        this.keyFile = keyFile;
        this.master = loadOrCreateKey();
    }

    @Override
    public boolean isProtected(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    @Override
    public String protect(String plain) {
        if (plain == null || plain.isEmpty() || isProtected(plain)) {
            return plain;
        }
        try {
            byte[] blob = keyring.encrypt(master, plain);
            return PREFIX + new String(blob, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Verschluesselung fehlgeschlagen", e);
        }
    }

    @Override
    public String reveal(String stored) {
        if (!isProtected(stored)) {
            return stored;
        }
        try {
            return keyring.decrypt(master, stored.substring(PREFIX.length()));
        } catch (Exception e) {
            throw new IllegalStateException("Entschluesselung fehlgeschlagen", e);
        }
    }

    /** Pfad der Schluesseldatei (Diagnose/Tests). */
    public Path keyFile() {
        return keyFile;
    }

    private String loadOrCreateKey() throws IOException {
        if (Files.exists(keyFile)) {
            String existing = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        String value = Base64.getEncoder().encodeToString(key);
        Path parent = keyFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(keyFile, value, StandardCharsets.UTF_8);
        restrictToOwner(keyFile);
        return value;
    }

    private static void restrictToOwner(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception ignored) {
            // Windows und andere Dateisysteme: dort gelten die Standard-ACLs
        }
    }
}
