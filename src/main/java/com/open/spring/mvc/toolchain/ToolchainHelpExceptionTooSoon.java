package com.open.spring.mvc.toolchain;

/**
 * A student flagged for help again before the cooldown ended.
 * Keeps one double-click or a stuck key from flooding the teacher's list.
 */
public class ToolchainHelpExceptionTooSoon extends RuntimeException {
    private final long retryAfterSeconds;

    public ToolchainHelpExceptionTooSoon(long retryAfterSeconds) {
        super("Your teacher already has your help request. You can send another in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
