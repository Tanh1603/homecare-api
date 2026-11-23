package com.homecare.provider.organization.service;

import com.homecare.common.storage.SupabaseStorageClient;
import com.homecare.common.storage.SupabaseStorageClient.PresignedUploadResult;
import com.homecare.provider.config.SupabaseProperties;
import com.homecare.provider.organization.dto.OrganizationDtos.PresignedEvidenceUploadResponse;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OrganizationEvidenceStorageService {

    private static final int PRESIGN_EXPIRES_IN_SECONDS = 900;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
    );

    private final SupabaseStorageClient storageClient;
    private final String bucket;

    @Autowired
    public OrganizationEvidenceStorageService(
            SupabaseProperties supabaseProperties,
            @Value("${homecare.organization.evidence-bucket:organization-evidence}") String bucket) {
        this(supabaseProperties.getUrl(), supabaseProperties.getSecretKey(), bucket);
    }

    public OrganizationEvidenceStorageService(String supabaseUrl, String secretKey, String bucket) {
        this.storageClient = new SupabaseStorageClient(supabaseUrl, secretKey);
        this.bucket = bucket == null || bucket.isBlank() ? "organization-evidence" : bucket.trim();
    }

    public PresignedEvidenceUploadResponse createPresignedUpload(
            String userId,
            String fileName,
            String contentType) {
        String normalizedContentType = SupabaseStorageClient.normalizeAndValidateContentType(
                contentType, ALLOWED_CONTENT_TYPES);
        String safeUserId = (userId == null || userId.isBlank())
                ? "anonymous"
                : userId.replaceAll("[^a-zA-Z0-9_-]", "_");
        String ext = SupabaseStorageClient.resolveExtension(normalizedContentType, fileName);
        String objectKey = "organizations/" + safeUserId + "/" + UUID.randomUUID() + ext;

        PresignedUploadResult result = storageClient.createPresignedUpload(
                bucket,
                objectKey,
                "/api/gateway/organizations/evidence/local/" + objectKey,
                PRESIGN_EXPIRES_IN_SECONDS);

        return new PresignedEvidenceUploadResponse(
                result.uploadUrl(),
                result.bucket(),
                result.objectKey(),
                result.token(),
                result.expiresInSeconds(),
                result.method(),
                result.localFallback());
    }

    public String createSignedReadUrl(String objectKey) {
        return storageClient.createSignedReadUrl(
                bucket,
                objectKey,
                "/api/gateway/organizations/evidence/local/" + objectKey,
                PRESIGN_EXPIRES_IN_SECONDS);
    }

    public boolean isConfigured() {
        return storageClient.isConfigured();
    }
}
