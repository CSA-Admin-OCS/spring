package com.open.spring.mvc.gist;

public class GistException extends RuntimeException {
    private final int status;
    private final String code;
    public GistException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int getStatus() { return status; }
    public String getCode() { return code; }
}
