package de.metis.mail.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentPolicyTest {

    private final AttachmentPolicy policy = AttachmentPolicy.createDefault();

    @Test
    void allowsSafeExtensions() {
        AttachmentPolicy.Result r = policy.checkAttachment("report.pdf", new byte[]{1, 2});
        assertEquals(AttachmentPolicy.Verdict.ERLAUBT, r.verdict());
        assertFalse(r.isBlocked());
        assertFalse(r.isSuspicious());
    }

    @Test
    void blocksDangerousExtensions() {
        for (String name : new String[]{"x.exe", "x.msi", "x.scr", "x.vbs", "x.js", "x.jar", "x.lnk"}) {
            assertTrue(policy.checkAttachment(name, null).isBlocked(), name + " should be blocked");
        }
    }

    @Test
    void blocksDoubleExtension() {
        AttachmentPolicy.Result r = policy.checkAttachment("invoice.pdf.exe", null);
        assertTrue(r.isBlocked());
        // Double extension with safe last ext is also suspicious-by-pattern => blocked
        assertTrue(policy.checkAttachment("malware.pdf.exe", null).isBlocked());
    }

    @Test
    void suspiciousWhenNoNameOrNoExtension() {
        assertTrue(policy.checkAttachment(null, null).isSuspicious());
        assertTrue(policy.checkAttachment("   ", null).isSuspicious());
        assertTrue(policy.checkAttachment("README", null).isSuspicious());
    }

    @Test
    void caseInsensitive() {
        assertTrue(policy.checkAttachment("X.EXE", null).isBlocked());
        assertEquals(AttachmentPolicy.Verdict.ERLAUBT, policy.checkAttachment("Foto.JPG", null).verdict());
    }

    @Test
    void sha256HexIsCorrect() {
        // SHA-256("abc") well-known digest
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                policy.calculateSha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", policy.calculateSha256Hex(null));
    }
}
