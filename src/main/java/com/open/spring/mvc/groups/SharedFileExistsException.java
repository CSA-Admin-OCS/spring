package com.open.spring.mvc.groups;

/**
 * A shared file is already stored at this path. Storing another there would replace it, so
 * the upload is refused: the API answers 409 and the page shares it under the next free name.
 */
public class SharedFileExistsException extends RuntimeException {

    private final String path;

    public SharedFileExistsException(String path) {
        super("A file named " + path + " is already shared in this group.");
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
