package com.homecare.booking.service;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SupabaseWorkEvidenceStorageAdapter implements WorkEvidenceStoragePort {

    private static final Logger log = LoggerFactory.getLogger(SupabaseWorkEvidenceStorageAdapter.class);

    private final RestClient restClient;
    private final String supabaseUrl;
    private final String serviceRoleKey;
    private final String bucket;

    public SupabaseWorkEvidenceStorageAdapter(
            @Value("${homecare.supabase.url:}") String supabaseUrl,
            @Value("${homecare.supabase.service-role-key:}") String serviceRoleKey,
            @Value("${homecare.bookings.evidence-bucket:booking-work-evidence}") String bucket) {
        this.restClient = RestClient.create();
        this.supabaseUrl = supabaseUrl == null ? "" : supabaseUrl.replaceAll("/+$", "");
        this.serviceRoleKey = serviceRoleKey == null ? "" : serviceRoleKey.trim();
        this.bucket = bucket == null || bucket.isBlank() ? "booking-work-evidence" : bucket.trim();
    }

    @Override
    public StoredObject upload(UUID bookingId, String originalFilename, String contentType, byte[] bytes) {
        String ext = extensionFor(contentType, originalFilename);
        String objectKey = "bookings/" + bookingId + "/" + UUID.randomUUID() + ext;
        if (!isConfigured()) {
            return new StoredObject(bucket, objectKey);
        }
        try {
            restClient.post()
                    .uri(URI.create(supabaseUrl + "/storage/v1/object/" + bucket + "/" + objectKey))
                    .header("apikey", serviceRoleKey)
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(bytes)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Supabase storage upload failed for {}: {}", objectKey, ex.getMessage());
            throw new IllegalStateException("Failed to upload work evidence to storage", ex);
        }
        return new StoredObject(bucket, objectKey);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String createSignedUrl(String targetBucket, String objectKey, int expiresInSeconds) {
        if (!isConfigured()) {
            return "/api/v1/bookings/work-evidence/local/" + objectKey;
        }
        try {
            Map<String, Object> body = restClient.post()
                    .uri(URI.create(supabaseUrl + "/storage/v1/object/sign/" + targetBucket + "/" + objectKey))
                    .header("apikey", serviceRoleKey)
                    .header("Authorization", "Bearer " + serviceRoleKey)
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
            log.warn("Failed to sign work evidence URL for {}: {}", objectKey, ex.getMessage());
        }
        return null;
    }

    private boolean isConfigured() {
        return !supabaseUrl.isBlank() && !serviceRoleKey.isBlank();
    }

    private String extensionFor(String contentType, String originalFilename) {
        if ("image/png".equalsIgnoreCase(contentType)) return ".png";
        if ("image/webp".equalsIgnoreCase(contentType)) return ".webp";
        if ("image/jpeg".equalsIgnoreCase(contentType) || "image/jpg".equalsIgnoreCase(contentType)) return ".jpg";
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase();
        }
        return ".bin";
    }
}
