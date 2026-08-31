package com.open.spring.mvc.person.Email;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.cdimascio.dotenv.Dotenv;

public class ResetCode {
    private static final Logger logger = LoggerFactory.getLogger(ResetCode.class);

    private static final long TOKEN_TTL_SECONDS = 5 * 60;
    private static final long RATE_WINDOW_SECONDS = 15 * 60;
    private static final int MAX_REQUESTS_PER_WINDOW = 3;

    private static final SecureRandom random = new SecureRandom();
    private static final Map<String, ResetTokenRecord> activeTokensByUid = new ConcurrentHashMap<>();
    private static final Map<String, Deque<Long>> resetRequestTimesByUid = new ConcurrentHashMap<>();
    private static final Map<String, String> lastIssueReasonByUid = new ConcurrentHashMap<>();
    // Bumped by an admin from the reset-ticket queue when a rate-limited user needs more
    // attempts; each grant adds one batch on top of MAX_REQUESTS_PER_WINDOW.
    private static final Map<String, Integer> bonusAttemptsByUid = new ConcurrentHashMap<>();

    // Ticket creation is unauthenticated, so this exists to stop one uid from being spammed
    // with repeat requests -- it is NOT the security boundary against ticket spam in general.
    // That boundary is the admin: a ticket does nothing on its own, it only grants bonus
    // attempts once a human clicks "Grant" on it, so a burst of tickets for many different
    // uids is queue noise for the admin to dismiss, not an actual bypass of anything. Keyed
    // by uid (not caller IP) so testing/admin tooling running from one machine against many
    // different uids in a short window doesn't trip this at all.
    private static final long TICKET_RATE_WINDOW_SECONDS = 15 * 60;
    private static final int MAX_TICKET_REQUESTS_PER_WINDOW = 5;
    private static final Map<String, Deque<Long>> ticketRequestTimesByUid = new ConcurrentHashMap<>();

    public static synchronized boolean canRequestTicket(String uid) {
        long now = Instant.now().getEpochSecond();
        Deque<Long> requestTimes = ticketRequestTimesByUid.computeIfAbsent(uid, key -> new ArrayDeque<>());
        while (!requestTimes.isEmpty() && requestTimes.peekFirst() <= now - TICKET_RATE_WINDOW_SECONDS) {
            requestTimes.removeFirst();
        }
        if (requestTimes.size() >= MAX_TICKET_REQUESTS_PER_WINDOW) {
            return false;
        }
        requestTimes.addLast(now);
        return true;
    }

    private static volatile byte[] cachedSecret;

    private static class ResetTokenRecord {
        private final String token;
        private final long expiresAtEpoch;

        private ResetTokenRecord(String token, long expiresAtEpoch) {
            this.token = token;
            this.expiresAtEpoch = expiresAtEpoch;
        }
    }

    // Same env-then-.env resolution order as FlaskPasswordSync/GoogleIdTokenVerifier: plain
    // System.getenv() only sees real OS environment variables, not Spring's own
    // spring.config.import=.env mechanism, so a Dotenv fallback is required for local dev
    // where the secret only lives in .env.
    private static String resolveConfiguredSecret() {
        String value = System.getenv("RESET_TOKEN_SECRET");
        if (value != null && !value.isBlank()) {
            return value;
        }
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            value = dotenv.get("RESET_TOKEN_SECRET");
            if (value != null && !value.isBlank()) {
                return value;
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    // Deliberately fails closed instead of falling back to an ephemeral per-restart secret:
    // a randomly generated fallback would silently invalidate every outstanding reset token
    // (and undermine the HMAC's whole purpose) on every deploy, without anyone noticing.
    private static byte[] getSecret() {
        byte[] local = cachedSecret;
        if (local != null) {
            return local;
        }
        String configured = resolveConfiguredSecret();
        if (configured == null) {
            throw new IllegalStateException(
                "RESET_TOKEN_SECRET is not set. Password reset cannot issue or validate tokens " +
                "without it -- set RESET_TOKEN_SECRET in the environment or .env file.");
        }
        local = configured.getBytes(StandardCharsets.UTF_8);
        cachedSecret = local;
        return local;
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hmacSha256(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(getSecret(), "HmacSHA256"));
            return base64Url(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign reset token", e);
        }
    }

    private static void cleanupUidState(String uid) {
        long now = Instant.now().getEpochSecond();

        ResetTokenRecord tokenRecord = activeTokensByUid.get(uid);
        if (tokenRecord != null && tokenRecord.expiresAtEpoch <= now) {
            activeTokensByUid.remove(uid);
            logger.info("AUDIT reset_token_expired uid={}", uid);
        }

        Deque<Long> requestTimes = resetRequestTimesByUid.computeIfAbsent(uid, key -> new ArrayDeque<>());
        while (!requestTimes.isEmpty() && requestTimes.peekFirst() <= now - RATE_WINDOW_SECONDS) {
            requestTimes.removeFirst();
        }
    }

    public static synchronized boolean canIssueResetCode(String uid) {
        cleanupUidState(uid);

        if (activeTokensByUid.containsKey(uid)) {
            lastIssueReasonByUid.put(uid, "active-token");
            return false;
        }

        Deque<Long> requestTimes = resetRequestTimesByUid.computeIfAbsent(uid, key -> new ArrayDeque<>());
        int bonus = bonusAttemptsByUid.getOrDefault(uid, 0);
        int allowedRequests = MAX_REQUESTS_PER_WINDOW + bonus;
        if (requestTimes.size() >= allowedRequests) {
            lastIssueReasonByUid.put(uid, "rate-limit");
            return false;
        }

        // This request only succeeds because of a bonus grant if the base window is already
        // exhausted -- consume one bonus attempt in that case, so a grant is a one-time batch
        // that runs out, not a permanent raise of the per-window ceiling.
        if (requestTimes.size() >= MAX_REQUESTS_PER_WINDOW && bonus > 0) {
            if (bonus <= 1) {
                bonusAttemptsByUid.remove(uid);
            } else {
                bonusAttemptsByUid.put(uid, bonus - 1);
            }
        }

        lastIssueReasonByUid.remove(uid);
        return true;
    }

    public static String getLastIssueReason(String uid) {
        return lastIssueReasonByUid.get(uid);
    }

    // Called by an admin resolving a reset ticket: lifts the rate limit by one batch of
    // extraAttempts on top of the standard window. Each attempt drawn from this batch (i.e.
    // each issuance beyond MAX_REQUESTS_PER_WINDOW) is consumed one at a time in
    // canIssueResetCode, so this is a one-time allowance, not a permanent ceiling raise.
    public static synchronized void grantBonusAttempts(String uid, int extraAttempts) {
        bonusAttemptsByUid.merge(uid, extraAttempts, Integer::sum);
        logger.info("AUDIT reset_bonus_attempts_granted uid={} extraAttempts={} totalBonus={}",
                uid, extraAttempts, bonusAttemptsByUid.get(uid));
    }

    public static synchronized String GenerateResetCode(String uid){
        if (!canIssueResetCode(uid)) {
            logger.warn("AUDIT reset_token_issue_blocked uid={} reason={}", uid, getLastIssueReason(uid));
            return null;
        }

        long now = Instant.now().getEpochSecond();
        long expiresAt = now + TOKEN_TTL_SECONDS;
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String nonceBase64 = base64Url(nonce);

        String payload = uid + "." + expiresAt + "." + nonceBase64;
        String signature = hmacSha256(payload);
        String token = base64Url(uid.getBytes(StandardCharsets.UTF_8)) + "." + expiresAt + "." + nonceBase64 + "." + signature;

        activeTokensByUid.put(uid, new ResetTokenRecord(token, expiresAt));
        resetRequestTimesByUid.computeIfAbsent(uid, key -> new ArrayDeque<>()).addLast(now);
        lastIssueReasonByUid.remove(uid);

        logger.info("AUDIT reset_token_issued uid={} expiresAt={}", uid, expiresAt);
        return token;
    }

    public static synchronized String getCodeForUid(String uid){
        cleanupUidState(uid);
        ResetTokenRecord tokenRecord = activeTokensByUid.get(uid);
        return tokenRecord == null ? null : tokenRecord.token;
    }

    public static synchronized boolean validateAndConsume(String uid, String token) {
        cleanupUidState(uid);
        if (token == null || token.isBlank()) {
            logger.warn("AUDIT reset_token_validate_failed uid={} reason=missing_token", uid);
            return false;
        }

        ResetTokenRecord tokenRecord = activeTokensByUid.get(uid);
        if (tokenRecord == null) {
            logger.warn("AUDIT reset_token_validate_failed uid={} reason=no_active_token", uid);
            return false;
        }

        if (!tokenRecord.token.equals(token)) {
            logger.warn("AUDIT reset_token_validate_failed uid={} reason=token_mismatch", uid);
            return false;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 4) {
            logger.warn("AUDIT reset_token_validate_failed uid={} reason=malformed_token", uid);
            return false;
        }

        long exp;
        try {
            exp = Long.parseLong(parts[1]);
        } catch (NumberFormatException ex) {
            logger.warn("AUDIT reset_token_validate_failed uid={} reason=invalid_exp", uid);
            return false;
        }

        String payload = uid + "." + exp + "." + parts[2];
        String expectedSig = hmacSha256(payload);
        if (!expectedSig.equals(parts[3])) {
            logger.warn("AUDIT reset_token_validate_failed uid={} reason=bad_signature", uid);
            return false;
        }

        if (Instant.now().getEpochSecond() > exp) {
            activeTokensByUid.remove(uid);
            logger.warn("AUDIT reset_token_validate_failed uid={} reason=expired", uid);
            return false;
        }

        activeTokensByUid.remove(uid); // single-use enforcement
        logger.info("AUDIT reset_token_consumed uid={}", uid);
        return true;
    }

    public static synchronized void removeCodeByUid(String uid){
        activeTokensByUid.remove(uid);
        logger.info("AUDIT reset_token_removed uid={}", uid);
    }
}
