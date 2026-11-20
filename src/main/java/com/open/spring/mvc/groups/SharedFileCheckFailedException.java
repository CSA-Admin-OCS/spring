package com.open.spring.mvc.groups;

/**
 * S3 couldn't say for now whether a shared file is already stored at this path: it timed out,
 * couldn't be reached or answered with a 5xx (a throttle, an outage). The upload is refused
 * rather than risk writing over a file, and the API answers 503 with Retry-After, since S3 being
 * unavailable for a moment isn't a bug in this server.
 */
public class SharedFileCheckFailedException extends RuntimeException {

    private final String path;

    public SharedFileCheckFailedException(String path, Throwable cause) {
        super("Could not check whether " + path + " is already shared in this group. Try again in a moment.", cause);
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
