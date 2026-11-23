package com.homecare.common.storage;

import static com.homecare.common.error.CommonErrorCode.VALIDATION_FAILED;

import com.homecare.common.error.DomainException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Shared Supabase Storage client for presigned uploads, signed read URLs, and direct byte uploads.
 */
public class SupabaseStorageClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageClient.class);

    public record PresignedUploadResult(
            String uploadUrl,
            String bucket,
            String objectKey,
            String token,
            int expiresInSeconds,
            String method,
            boolean localFallback) {
    }

    private final RestClient restClient;
    private final String supabaseUrl;
    private final String secretKey;

    public SupabaseStorageClient(String supabaseUrl, String secretKey) {
        this.restClient = RestClient.create();
        this.supabaseUrl = supabaseUrl == null ? "" : supabaseUrl.replaceAll("/+$", "");
        this.secretKey = secretKey == null ? "" : secretKey.trim();
    }

    public boolean isConfigured() {
        return !supabaseUrl.isBlank()
                && !supabaseUrl.contains("placeholder.supabase.co")
                && !secretKey.isBlank()
                && !"placeholder-secret-key".equals(secretKey);
    }

    public static String normalizeAndValidateContentType(String contentType, Set<String> allowedTypes) {
        String normalized = contentType == null ? "" : contentType.trim().toLowerCase();
        if (!allowedTypes.contains(normalized)) {
            throw new DomainException(
                    VALIDATION_FAILED,
                    "Unsupported file type: " + (normalized.isBlank() ? "<empty>" : normalized));
        }
        return normalized;
    }

    public static String resolveExtension(String contentType, String originalFilename) {
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/jpeg", "image/jpg" -> ".jpg";
            default -> {
                if (originalFilename != null && originalFilename.contains(".")) {
                    yield originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase();
                }
                yield ".bin";
            }
        };
    }

    @SuppressWarnings("unchecked")
    public PresignedUploadResult createPresignedUpload(
            String bucket,
            String objectKey,
            String localFallbackUrl,
            int expiresInSeconds) {
        if (!isConfigured()) {
            return new PresignedUploadResult(
                    localFallbackUrl,
                    bucket,
                    objectKey,
                    "local-dev-token",
                    expiresInSeconds,
                    "PUT",
                    true);
        }
        try {
            Map<String, Object> response = restClient.post()
                    .uri(URI.create(supabaseUrl + "/storage/v1/object/upload/sign/" + bucket + "/" + objectKey))
                    .header("apikey", secretKey)
                    .header("Authorization", "Bearer " + secretKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("upsert", false))
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.get("url") instanceof String signedPath) {
                String uploadUrl = signedPath.startsWith("http")
                        ? signedPath
                        : supabaseUrl + "/storage/v1" + signedPath;
                String token = response.get("token") instanceof String t ? t : null;
                return new PresignedUploadResult(
                        uploadUrl, bucket, objectKey, token, expiresInSeconds, "PUT", false);
            }
            throw new IllegalStateException("Supabase storage did not return a signed upload URL.");
        } catch (Exception ex) {
            log.warn("Failed to create presigned upload URL for {}/{}: {}", bucket, objectKey, ex.getMessage());
            throw new IllegalStateException("Failed to create presigned upload URL", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public String createSignedReadUrl(
            String bucket,
            String objectKey,
            String localFallbackUrl,
            int expiresInSeconds) {
        if (!isConfigured()) {
            return localFallbackUrl;
        }
        try {
            Map<String, Object> body = restClient.post()
                    .uri(URI.create(supabaseUrl + "/storage/v1/object/sign/" + bucket + "/" + objectKey))
                    .header("apikey", secretKey)
                    .header("Authorization", "Bearer " + secretKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("expiresIn", expiresInSeconds))
                    .retrieve()
                    .body(Map.class);
            if (body != null && body.get("signedURL") instanceof String signedPath) {
                return signedPath.startsWith("http")
                        ? signedPath
                        : supabaseUrl + "/storage/v1" + signedPath;
            }
        } catch (Exception ex) {
            log.warn("Failed to sign read URL for {}/{}: {}", bucket, objectKey, ex.getMessage());
        }
        return null;
    }
}
