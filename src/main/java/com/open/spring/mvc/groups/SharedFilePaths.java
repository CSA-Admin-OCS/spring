package com.open.spring.mvc.groups;

/**
 * The paths a group's shared file may be stored under. A file shared from a folder keeps its
 * place in that folder ("project/src/Main.java"), so a path may have folders, but it always
 * stays inside the group's shared-files folder: no "..", no "." and no empty folder names,
 * nothing absolute, no backslashes and no control characters. A path is refused rather than
 * rewritten, so what a member sees in the chat is exactly what was stored.
 */
final class SharedFilePaths {
    static final int MAX_PATH_LENGTH = 512;
    static final int MAX_NAME_LENGTH = 255;

    private SharedFilePaths() {
    }

    /** The path as given when it is a safe place to store a file, otherwise null. */
    static String clean(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH) {
            return null;
        }
        if (path.startsWith("/") || path.indexOf('\\') >= 0) {
            return null;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return null;
            }
        }
        for (String name : path.split("/", -1)) {
            if (name.isBlank() || name.equals(".") || name.equals("..") || name.length() > MAX_NAME_LENGTH) {
                return null;
            }
        }
        return path;
    }

    /** The file's own name, without its folders: what a download is called. */
    static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
