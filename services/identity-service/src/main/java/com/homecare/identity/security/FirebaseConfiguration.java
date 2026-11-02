package com.homecare.identity.security;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FirebaseConfiguration {
    @Bean
    @ConditionalOnMissingBean(FirebaseAuth.class)
    FirebaseAuth firebaseAuth(@Value("${homecare.firebase.project-id}") String project,
            @Value("${homecare.firebase.connect-timeout-ms}") int connect,
            @Value("${homecare.firebase.read-timeout-ms}") int read) throws java.io.IOException {
        if (project == null || project.isBlank() || System.getenv("FIREBASE_AUTH_EMULATOR_HOST") != null)
            throw new IllegalStateException("Firebase project required; emulator mode forbidden");
        try {
            var credentials = GoogleCredentials.getApplicationDefault();
            if (credentials instanceof ServiceAccountCredentials serviceAccount
                    && !project.equals(serviceAccount.getProjectId()))
                throw new IllegalArgumentException("ADC project mismatch");
            var options = FirebaseOptions.builder().setProjectId(project)
                    .setCredentials(credentials).setConnectTimeout(connect).setReadTimeout(read).build();
            return FirebaseAuth.getInstance(FirebaseApp.initializeApp(options, "identity-" + java.util.UUID.randomUUID()));
        } catch (Exception failure) {
            // SDK credential exceptions may include local paths or credential material.
            throw new IllegalStateException("Firebase ADC configuration invalid; inspect operator configuration privately");
        }
    }
}
