package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Expected identities were worked out separately with Python's hmac module. */
class CallIdentityTest {

    private static final String SECRET = "test-livekit-secret-0123456789abcdef";

    @Test
    void identityIsTheHmacOfTheUid() {
        assertEquals("YcP_m_Mq4hQs7ZwUlJgNpI", CallIdentity.of("ada", SECRET));
        assertEquals("m-R6MldfagWEzEPOTp9Eex", CallIdentity.of("niko", SECRET));
    }

    @Test
    void sameUidSameIdentity() {
        assertEquals(CallIdentity.of("ada", SECRET), CallIdentity.of("ada", SECRET));
    }

    @Test
    void differentUidsDiffer() {
        assertNotEquals(CallIdentity.of("ada", SECRET), CallIdentity.of("niko", SECRET));
    }

    @Test
    void identityHidesTheUid() {
        String identity = CallIdentity.of("ada", SECRET);

        assertFalse(identity.contains("ada"));
        assertTrue(identity.matches("[A-Za-z0-9_-]{22}"));
    }

    @Test
    void anotherSecretGivesAnotherIdentity() {
        assertEquals("NlCZ6slbA9JEDjt6VbRYHk", CallIdentity.of("ada", "another-livekit-secret-0123456789ab"));
    }
}
