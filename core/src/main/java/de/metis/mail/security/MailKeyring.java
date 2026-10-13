package de.metis.mail.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class MailKeyring {
    private static final Logger LOG = Logger.getLogger(MailKeyring.class.getName());
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 100000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    public byte[] encrypt(String masterPassword, String plainSecret) throws Exception {
        if (masterPassword == null || masterPassword.isEmpty()) {
            throw new IllegalArgumentException("Master password cannot be null or empty");
        }
        if (plainSecret == null) {
            throw new IllegalArgumentException("Plain secret cannot be null");
        }

        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);

        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);

        SecretKey aesKey = deriveKey(masterPassword, salt);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);

        byte[] ciphertext = cipher.doFinal(plainSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        byte[] combined = new byte[salt.length + iv.length + ciphertext.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(iv, 0, combined, salt.length, iv.length);
        System.arraycopy(ciphertext, 0, combined, salt.length + iv.length, ciphertext.length);

        return Base64.getEncoder().encode(combined);
    }

    public String decrypt(String masterPassword, String blob) throws Exception {
        if (masterPassword == null || masterPassword.isEmpty()) {
            throw new IllegalArgumentException("Master password cannot be null or empty");
        }
        if (blob == null || blob.isEmpty()) {
            throw new IllegalArgumentException("Blob cannot be null or empty");
        }

        byte[] combined = Base64.getDecoder().decode(blob);

        int saltLength = SALT_BYTES;
        int ivLength = IV_BYTES;

        if (combined.length < saltLength + ivLength + (TAG_BITS / 8)) {
            throw new SecurityException("Invalid encrypted blob format");
        }

        byte[] salt = new byte[saltLength];
        System.arraycopy(combined, 0, salt, 0, saltLength);

        byte[] iv = new byte[ivLength];
        System.arraycopy(combined, saltLength, iv, 0, ivLength);

        byte[] ciphertext = new byte[combined.length - saltLength - ivLength];
        System.arraycopy(combined, saltLength + ivLength, ciphertext, 0, ciphertext.length);

        SecretKey aesKey = deriveKey(masterPassword, salt);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);

        byte[] plaintextBytes = cipher.doFinal(ciphertext);

        return new String(plaintextBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean isEncrypted(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length >= (SALT_BYTES + IV_BYTES + (TAG_BITS / 8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void wipe(char[] chars) {
        if (chars != null) {
            for (int i = 0; i < chars.length; i++) {
                chars[i] = '\0';
            }
        }
    }

    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(keyBytes, "AES");
    }
}