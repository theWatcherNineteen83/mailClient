package de.metis.mail.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Tests fuer den von Metis erzeugten MailKeyring (Metis-Ziel 13.10.2026). */
class MailKeyringTest {

    private final MailKeyring keyring = new MailKeyring();
    private final String master = "Master-Passwort-123";

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        String secret = "geheim123";
        byte[] blob = keyring.encrypt(master, secret);
        String asText = new String(blob, StandardCharsets.UTF_8);
        assertEquals(secret, keyring.decrypt(master, asText));
        assertNotEquals(secret, asText, "Klartext darf nicht im Blob stehen");
    }

    @Test
    void wrongMasterPasswordFails() throws Exception {
        String asText = new String(keyring.encrypt(master, "geheim123"), StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> keyring.decrypt("falsch", asText));
    }

    @Test
    void tamperedBlobIsRejected() throws Exception {
        byte[] raw = Base64.getDecoder().decode(
                new String(keyring.encrypt(master, "geheim123"), StandardCharsets.UTF_8));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThrows(Exception.class, () -> keyring.decrypt(master, tampered),
                "GCM muss Manipulation erkennen");
    }

    @Test
    void blankInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> keyring.encrypt("", "x"));
        assertThrows(IllegalArgumentException.class, () -> keyring.encrypt(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> keyring.encrypt(master, null));
    }

    @Test
    void isEncryptedDetectsBlobAndPlaintext() throws Exception {
        String asText = new String(keyring.encrypt(master, "geheim123"), StandardCharsets.UTF_8);
        assertTrue(keyring.isEncrypted(asText));
        assertFalse(keyring.isEncrypted("Klartext"));
        assertFalse(keyring.isEncrypted(""));
        assertFalse(keyring.isEncrypted(null));
    }

    @Test
    void wipeClearsChars() {
        char[] chars = {'a', 'b', 'c'};
        keyring.wipe(chars);
        assertArrayEquals(new char[]{0, 0, 0}, chars);
        keyring.wipe(null);
    }
}
