package com.homecare.identity.security;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirebaseConfigurationTest {
    @Test void missingProjectFailsBeforeAdcLookup() {
        assertThatThrownBy(() -> new FirebaseConfiguration().firebaseAuth(" ",2000,5000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("project required");
    }
    @Test void unreadableAdcDoesNotExposeCredentialPathOrCause() throws Exception {
        try (var credentials = mockStatic(GoogleCredentials.class)) {
            credentials.when(GoogleCredentials::getApplicationDefault).thenThrow(new IOException("synthetic-sensitive-path"));
            assertThatThrownBy(() -> new FirebaseConfiguration().firebaseAuth("test-project",2000,5000))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("synthetic-sensitive-path").hasNoCause();
        }
    }
    @Test void serviceAccountFromAnotherProjectFailsClosed() throws Exception {
        var account=mock(ServiceAccountCredentials.class);
        when(account.getProjectId()).thenReturn("other-project");
        try (var credentials=mockStatic(GoogleCredentials.class)) {
            credentials.when(GoogleCredentials::getApplicationDefault).thenReturn(account);
            assertThatThrownBy(() -> new FirebaseConfiguration().firebaseAuth("test-project",2000,5000))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("ADC configuration invalid").hasNoCause();
        }
    }
}
