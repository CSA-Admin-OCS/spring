package com.open.spring.mvc.S3uploads;

public interface FileHandler {
    /**
     * Uploads a file (base64 encoded) to the storage system.
     * 
     * @param base64Data      Base64 encoded file content
     * @param filename        User provided filename
     * @param uid             User ID
     * @return The saved filename or null if failed
     */
    String uploadFile(String base64Data, String filename, String uid);

    /**
     * Retrieves a file and encodes it to base64.
     * 
     * @param uid             User ID
     * @param filename        Filename to retrieve
     * @return Base64 encoded string or null if failed
     */
    String decodeFile(String uid, String filename);

    /**
     * Deletes all files associated with a user.
     *
     * @param uid             User ID
     * @return true if successful
     */
    boolean deleteFiles(String uid);

    /**
     * Checks if a file exists in the storage system.
     *
     * @param uid             User/group ID
     * @param filename        Filename to check
     * @return true if the file exists
     */
    boolean fileExists(String uid, String filename);

    /**
     * Lists all file keys under a given prefix.
     *
     * @param prefix          The S3 key prefix to list
     * @return List of S3 object keys matching the prefix
     */
    java.util.List<String> listFiles(String prefix);

    /**
     * Every object key under a prefix with its size in bytes, without reading any bytes:
     * what a list of shared files shows. Follows the listing past S3's 1000 keys per page.
     *
     * @param prefix          The S3 key prefix to list
     * @return Map of S3 object key to size in bytes, empty when nothing matches or on error
     */
    java.util.Map<String, Long> listFileSizes(String prefix);
}
