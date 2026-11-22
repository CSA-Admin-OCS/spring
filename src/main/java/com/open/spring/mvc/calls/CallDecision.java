package com.open.spring.mvc.calls;

/**
 * What the safety rules say about someone asking to join: ALLOW with the grants their token gets,
 * or WAIT / DENY with the reason the page shows. listed marks a call of one adult and one minor.
 */
public record CallDecision(Outcome outcome, String reason, CallGrants grants, boolean listed) {

    public enum Outcome { ALLOW, WAIT, DENY }

    static CallDecision refused(Outcome outcome, String reason) {
        return new CallDecision(outcome, reason, CallGrants.PAUSED, false);
    }
}
