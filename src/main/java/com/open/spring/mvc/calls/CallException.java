package com.open.spring.mvc.calls;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/** A refused calls request, answered by CallExceptionHandler with this status and {"reason": reason}. */
@Getter
public class CallException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;

    public CallException(HttpStatus status, String reason) {
        super(reason);
        this.status = status;
        this.reason = reason;
    }
}
