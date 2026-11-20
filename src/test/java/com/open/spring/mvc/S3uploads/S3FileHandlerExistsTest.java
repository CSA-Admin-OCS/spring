package com.open.spring.mvc.S3uploads;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * fileExistsOrThrow tells "not stored" apart from "couldn't check": shared files are never
 * written over, so a check that failed must not read as a free path.
 */
class S3FileHandlerExistsTest {

    private S3Client s3;
    private S3FileHandler handler;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        handler = new S3FileHandler();
        ReflectionTestUtils.setField(handler, "s3Client", s3);
    }

    private static S3Exception answered(int status) {
        return (S3Exception) S3Exception.builder().statusCode(status).message("HTTP " + status).build();
    }

    @Test
    void aStoredFileExists() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        assertTrue(handler.fileExistsOrThrow("CSA", "shared-files/notes.md"));
    }

    @Test
    void notFoundMeansItIsNotStored() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(answered(404));
        assertFalse(handler.fileExistsOrThrow("CSA", "shared-files/notes.md"));
    }

    @Test
    void anyOtherFailureThrowsInsteadOfReadingAsNotStored() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(answered(503));
        assertThrows(S3Exception.class, () -> handler.fileExistsOrThrow("CSA", "shared-files/notes.md"));
        // fileExists keeps answering false, as its other callers expect.
        assertFalse(handler.fileExists("CSA", "shared-files/notes.md"));
    }
}
