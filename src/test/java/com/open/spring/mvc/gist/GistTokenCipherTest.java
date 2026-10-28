package com.open.spring.mvc.gist;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GistTokenCipherTest {
    private final GistTokenCipher cipher = new GistTokenCipher(Base64.getEncoder().encodeToString(new byte[32]));
    @Test void roundTripUsesFreshNonces() {
        String first = cipher.encrypt(1, "fixture-token");
        assertEquals("fixture-token", cipher.decrypt(1, first));
        assertNotEquals(first, cipher.encrypt(1, "fixture-token"));
    }
    @Test void rejectsCopiedCiphertextTamperingAndWrongKey() {
        String encrypted = cipher.encrypt(1, "fixture-token");
        assertThrows(GistException.class, () -> cipher.decrypt(2, encrypted));
        byte[] bytes = Base64.getDecoder().decode(encrypted.substring(3));
        bytes[bytes.length - 1] ^= 1;
        assertThrows(GistException.class, () -> cipher.decrypt(1, "v1:" + Base64.getEncoder().encodeToString(bytes)));
        byte[] other = new byte[32]; other[0] = 1;
        var wrong = new GistTokenCipher(Base64.getEncoder().encodeToString(other));
        assertThrows(GistException.class, () -> wrong.decrypt(1, encrypted));
    }
    @Test void invalidConfigurationDisablesOnlyTheFeature() {
        for (String value : new String[] {"", "invalid", "YQ=="}) {
            var disabled = new GistTokenCipher(value);
            assertFalse(disabled.isAvailable());
            assertEquals(503, assertThrows(GistException.class, disabled::requireAvailable).getStatus());
        }
    }
}
