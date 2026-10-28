package com.open.spring.mvc.aiconnections;

/** Contains only application-owned messages, never a provider body, URL, or key. */
public class AiProviderFailure extends RuntimeException {
    private final boolean operational;
    private final int httpStatus;
    public AiProviderFailure(boolean operational, String message) {
        this(operational, message, 0);
    }
    public AiProviderFailure(boolean operational, String message, int httpStatus) {
        super(message); this.operational = operational; this.httpStatus = httpStatus;
    }
    public boolean isOperational() { return operational; }
    public int getHttpStatus() { return httpStatus; }
}
