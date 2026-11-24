package com.homecare.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.common.error.DomainException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SupabaseStorageClientTest {

    @Test
    void returnsLocalFallbackWhenPlaceholderCredentialsAreUsed() {
        SupabaseStorageClient client = new SupabaseStorageClient(
                "https://placeholder.supabase.co",
                "placeholder-secret-key");

        assertThat(client.isConfigured()).isFalse();
        var result = client.createPresignedUpload(
                "service-images",
                "services/123/photo.webp",
                "/api/gateway/services/local/photo.webp",
                600);
        assertThat(result.localFallback()).isTrue();
        assertThat(result.uploadUrl()).isEqualTo("/api/gateway/services/local/photo.webp");
        assertThat(result.method()).isEqualTo("PUT");
    }

    @Test
    void validatesContentTypeAndResolvesExtension() {
        assertThat(SupabaseStorageClient.normalizeAndValidateContentType(
                " IMAGE/PNG ", Set.of("image/png", "image/jpeg")))
                .isEqualTo("image/png");
        assertThat(SupabaseStorageClient.resolveExtension("image/webp", "pic.bin"))
                .isEqualTo(".webp");

        assertThatThrownBy(() -> SupabaseStorageClient.normalizeAndValidateContentType(
                "application/zip", Set.of("image/png")))
                .isInstanceOf(DomainException.class);
    }
}
