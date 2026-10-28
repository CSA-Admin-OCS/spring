package com.open.spring.mvc.gist;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GistTokenCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public GistTokenCipher(@Value("${gist.encryption-key:}") String encodedKey) {
        SecretKeySpec parsed = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey);
            if (bytes.length == 32) parsed = new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException ignored) {
            // Invalid configuration disables this feature, not the application.
        }
        key = parsed;
    }
    public boolean isAvailable() { return key != null; }
    public void requireAvailable() { if (!isAvailable()) throw unavailable(); }

    public String encrypt(long personId, String token) {
        requireAvailable();
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, personId, nonce)
                .doFinal(token.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException e) { throw unavailable(); }
    }
    public String decrypt(long personId, String envelope) {
        requireAvailable();
        try {
            if (envelope == null || !envelope.startsWith("v1:")) throw new IllegalArgumentException();
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(envelope.substring(3)));
            if (buffer.remaining() < 28) throw new IllegalArgumentException();
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, personId, nonce).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw unavailable(); }
    }
    private Cipher cipher(int mode, long personId, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("ocs-gist:" + personId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    private GistException unavailable() {
        return new GistException(503, "GIST_STORAGE_UNAVAILABLE", "GitHub connection storage is unavailable. Contact support.");
    }
}
