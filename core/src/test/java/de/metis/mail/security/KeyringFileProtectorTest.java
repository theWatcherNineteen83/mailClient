package de.metis.mail.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tests fuer den Keyring-basierten Passwortschutz der Ablage (M4-003). */
class KeyringFileProtectorTest {

    @TempDir
    Path dir;

    @Test
    void createsKeyFileAndRoundTrips() throws Exception {
        Path key = dir.resolve("keyring.key");
        KeyringFileProtector p = new KeyringFileProtector(key);

        assertTrue(Files.exists(key), "Keyring-Datei wird beim ersten Start erzeugt");
        assertFalse(Files.readString(key, StandardCharsets.UTF_8).isBlank());

        String stored = p.protect("geheim123");
        assertTrue(p.isProtected(stored));
        assertFalse(stored.contains("geheim123"), "Klartext darf nicht in der Ablage stehen");
        assertEquals("geheim123", p.reveal(stored));

        // derselbe Schluessel entschluesselt auch aus einer neuen Instanz
        KeyringFileProtector again = new KeyringFileProtector(key);
        assertEquals("geheim123", again.reveal(stored));
    }

    @Test
    void secondEncryptUsesFreshSalt() throws Exception {
        KeyringFileProtector p = new KeyringFileProtector(dir.resolve("keyring.key"));
        assertNotEquals(p.protect("gleich"), p.protect("gleich"),
                "jede Verschluesselung braucht ein eigenes Salt/IV");
    }

    @Test
    void legacyPlaintextPassesThrough() throws Exception {
        KeyringFileProtector p = new KeyringFileProtector(dir.resolve("keyring.key"));
        assertFalse(p.isProtected("altesKlartextPasswort"));
        assertEquals("altesKlartextPasswort", p.reveal("altesKlartextPasswort"));
        assertNull(p.protect(null));
        assertEquals("", p.protect(""));
    }

    @Test
    void protectIsIdempotent() throws Exception {
        KeyringFileProtector p = new KeyringFileProtector(dir.resolve("keyring.key"));
        String once = p.protect("geheim");
        assertEquals(once, p.protect(once), "bereits verschluesselter Wert bleibt unveraendert");
    }

    @Test
    void keyFileIsOwnerOnlyWherePosixIsSupported() throws Exception {
        Path key = dir.resolve("keyring.key");
        new KeyringFileProtector(key);
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(key);
            assertFalse(perms.contains(PosixFilePermission.GROUP_READ), "Gruppe darf nicht lesen: " + perms);
            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ), "Andere duerfen nicht lesen: " + perms);
        } catch (UnsupportedOperationException e) {
            // Windows o. ae.
        }
    }
}
