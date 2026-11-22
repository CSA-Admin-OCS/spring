package com.open.spring.mvc.calls;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The name a person has inside LiveKit: the first 22 characters of the base64url HMAC-SHA256 of
 * "calls:" + their uid, keyed with the LiveKit API secret. It is the same on every join, so
 * presence and removals follow the person, and LiveKit's logs never hold a uid, name or email.
 */
public final class CallIdentity {

    private static final int LENGTH = 22;

    private CallIdentity() {
    }

    public static String of(String uid, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(("calls:" + uid).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
