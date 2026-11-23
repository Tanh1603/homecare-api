package com.homecare.provider.organization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.common.error.DomainException;
import com.homecare.provider.organization.dto.OrganizationDtos.PresignedEvidenceUploadResponse;
import org.junit.jupiter.api.Test;

class OrganizationEvidenceStorageServiceTest {

    @Test
    void createPresignedUploadReturnsLocalFallbackWhenSupabaseNotConfigured() {
        OrganizationEvidenceStorageService service = new OrganizationEvidenceStorageService(
                "https://placeholder.supabase.co",
                "placeholder-secret-key",
                "organization-evidence");

        PresignedEvidenceUploadResponse res = service.createPresignedUpload(
                "user-123", "business-license.pdf", "application/pdf");

        assertThat(res.localFallback()).isTrue();
        assertThat(res.method()).isEqualTo("PUT");
        assertThat(res.bucket()).isEqualTo("organization-evidence");
        assertThat(res.objectKey()).startsWith("organizations/user-123/").endsWith(".pdf");
        assertThat(res.uploadUrl()).isEqualTo("/api/gateway/organizations/evidence/local/" + res.objectKey());
    }

    @Test
    void createPresignedUploadRejectsUnsupportedContentType() {
        OrganizationEvidenceStorageService service = new OrganizationEvidenceStorageService(
                "https://placeholder.supabase.co",
                "placeholder-secret-key",
                "organization-evidence");

        assertThatThrownBy(() -> service.createPresignedUpload(
                "user-123", "script.exe", "application/x-msdownload"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Unsupported file type");
    }
}
