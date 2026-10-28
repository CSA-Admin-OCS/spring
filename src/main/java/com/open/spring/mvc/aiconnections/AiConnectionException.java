package com.open.spring.mvc.aiconnections;

public class AiConnectionException extends RuntimeException {
    private final int status;
    private final String code;
    public AiConnectionException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int getStatus() { return status; }
    public String getCode() { return code; }
    public static AiConnectionException unavailable() {
        return new AiConnectionException(503, "AI_STORAGE_UNAVAILABLE",
            "Personal AI key storage is unavailable. System grading and submissions remain available.");
    }
}
