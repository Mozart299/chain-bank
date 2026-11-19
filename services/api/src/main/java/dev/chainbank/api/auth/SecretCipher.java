package dev.chainbank.api.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.chainbank.api.platform.ChainbankConfig;

/**
 * AES-256-GCM encryption for secrets stored in the database (TOTP seeds). The owning user id is
 * bound as associated data, so a ciphertext copied onto another user's row will not decrypt.
 * Format: 12-byte IV || ciphertext+tag.
 */
@ApplicationScoped
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;

    @Inject
    public SecretCipher(ChainbankConfig config) {
        byte[] raw = Base64.getDecoder().decode(config.secrets().totpKey());
        if (raw.length != 32) {
            throw new IllegalStateException("chainbank.secrets.totp-key must be 32 bytes (base64)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(byte[] plaintext, UUID owner) {
        try {
            byte[] iv = Crypto.randomBytes(IV_BYTES);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(owner.toString().getBytes(StandardCharsets.US_ASCII));
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Throws if the ciphertext was tampered with or belongs to a different owner. */
    public byte[] decrypt(byte[] blob, UUID owner) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            c.updateAAD(owner.toString().getBytes(StandardCharsets.US_ASCII));
            return c.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("secret decryption failed", e);
        }
    }
}
