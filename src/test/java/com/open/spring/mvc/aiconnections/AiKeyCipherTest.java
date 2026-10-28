package com.open.spring.mvc.aiconnections;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiKeyCipherTest {
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    @Test void roundTripUsesUniqueNoncesAndNoPlaintext() {
        var cipher = new AiKeyCipher(key);
        String first = cipher.encrypt(1, AiProvider.gemini, "test-key");
        String second = cipher.encrypt(1, AiProvider.gemini, "test-key");
        assertNotEquals(first, second);
        assertFalse(first.contains("test-key"));
        assertEquals("test-key", cipher.decrypt(1, AiProvider.gemini, first));
    }
    @Test void ciphertextCannotBeCopiedBetweenPeopleOrProviders() {
        var cipher = new AiKeyCipher(key);
        String encrypted = cipher.encrypt(1, AiProvider.gemini, "test-key");
        assertThrows(AiConnectionException.class, () -> cipher.decrypt(2, AiProvider.gemini, encrypted));
        assertThrows(AiConnectionException.class, () -> cipher.decrypt(1, AiProvider.groq, encrypted));
    }
    @Test void tamperingAndIncorrectKeysFailWithoutRevealingSecrets() {
        var cipher = new AiKeyCipher(key);
        String encrypted = cipher.encrypt(1, AiProvider.groq, "test-key");
        byte[] bytes = Base64.getDecoder().decode(encrypted.substring(3)); bytes[15] ^= 1;
        assertThrows(AiConnectionException.class, () -> cipher.decrypt(1, AiProvider.groq,
            "v1:" + Base64.getEncoder().encodeToString(bytes)));
        byte[] wrong = new byte[32]; wrong[0] = 1;
        var exception = assertThrows(AiConnectionException.class,
            () -> new AiKeyCipher(Base64.getEncoder().encodeToString(wrong)).decrypt(1, AiProvider.groq, encrypted));
        assertFalse(exception.toString().contains(encrypted));
        assertFalse(exception.toString().contains("test-key"));
    }
    @Test void missingOrInvalidConfigDisablesOnlyPersonalStorage() {
        for (String config : new String[] {"", "bad", Base64.getEncoder().encodeToString(new byte[16])}) {
            var cipher = new AiKeyCipher(config);
            assertFalse(cipher.isAvailable());
            assertEquals(503, assertThrows(AiConnectionException.class,
                () -> cipher.encrypt(1, AiProvider.gemini, "test-key")).getStatus());
        }
    }
}
