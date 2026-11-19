package com.homecare.booking.service;

import java.util.UUID;

public interface WorkEvidenceStoragePort {
    record StoredObject(String bucket, String objectKey) {}

    StoredObject upload(UUID bookingId, String originalFilename, String contentType, byte[] bytes);

    String createSignedUrl(String bucket, String objectKey, int expiresInSeconds);
}
