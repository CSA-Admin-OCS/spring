package com.open.spring.mvc.S3uploads;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.github.cdimascio.dotenv.Dotenv;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// TODO: Change the BUCKET NAME from pages-assignments to something else

@Service
@Slf4j
// @ConditionalOnProperty(name = "file.storage-type", havingValue = "prod")
public class S3FileHandler implements FileHandler {

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.access-key-id}")
    private String accessKey;

    @Value("${aws.s3.secret-access-key}")
    private String secretKey;

    @Value("${aws.s3.region}")
    private String region;

    private S3Client s3Client;

    @PostConstruct
    public void init() {
        if (isBlank(accessKey) || isBlank(secretKey) || isBlank(region) || isBlank(bucketName)) {
            log.warn("S3 is disabled: missing AWS credentials/region/bucket. Upload API will return errors until configured.");
            return;
        }

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey.trim(), secretKey.trim());

        var builder = S3Client.builder()
                .region(Region.of(region.trim()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials));

        String endpoint = Dotenv.configure().ignoreIfMissing().load().get("AWS_S3_ENDPOINT");

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            builder.serviceConfiguration(
                    software.amazon.awssdk.services.s3.S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build()
            );
        }

        this.s3Client = builder.build();
    }

    @Override
    public String uploadFile(String base64Data, String filename, String uid) {
        if (s3Client == null) {
            log.warn("S3 upload attempted but S3 client is not configured.");
            return null;
        }
        String key = generateKey(uid, filename);
        System.out.println("S3 Upload: " + key);

        try {
            byte[] fileData = Base64.getDecoder().decode(base64Data);

            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.putObject(putOb, RequestBody.fromBytes(fileData));

            return filename;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String decodeFile(String uid, String filename) {
        if (s3Client == null) {
            log.warn("S3 download attempted but S3 client is not configured.");
            return null;
        }
        String key = generateKey(uid, filename);
        System.out.println("S3 Download: " + key);

        try {
            GetObjectRequest getOb = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getOb);
            byte[] data = objectBytes.asByteArray();

            return Base64.getEncoder().encodeToString(data);
        } catch (Exception e) {
            System.err.println("S3 Download Error for key: " + key);
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean deleteFiles(String uid) {
        if (s3Client == null) {
            log.warn("S3 delete attempted but S3 client is not configured.");
            return false;
        }
        String prefix = uid + "/";
        System.out.println("S3 Delete Prefix: " + prefix);

        try {
            // 1. List objects to delete
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listRes = s3Client.listObjectsV2(listReq);

            if (listRes.contents().isEmpty()) {
                return true;
            }

            // 2. Prepare batch delete
            List<ObjectIdentifier> objectsToDelete = listRes.contents().stream()
                    .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build())
                    .collect(Collectors.toList());

            DeleteObjectsRequest deleteReq = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(objectsToDelete).build())
                    .build();

            s3Client.deleteObjects(deleteReq);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public List<String> listFiles(String prefix) {
        if (s3Client == null) {
            log.warn("S3 list attempted but S3 client is not configured.");
            return new ArrayList<>();
        }

        try {
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listRes = s3Client.listObjectsV2(listReq);

            return listRes.contents().stream()
                    .map(s3Object -> s3Object.key())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    @Override
    public boolean fileExists(String uid, String filename) {
        if (s3Client == null) {
            log.warn("S3 fileExists check attempted but S3 client is not configured.");
            return false;
        }
        String key = generateKey(uid, filename);
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String generateKey(String uid, String filename) {
        return uid + "/" + filename;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
