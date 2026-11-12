package com.homecare.notification.config;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    public FirebaseApp firebaseApp(
            @Value("${homecare.fcm.credentials-json:}") String credentialsJson,
            @Value("${homecare.fcm.credentials-path:}") String credentialsPath) {

        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }

        try {
            GoogleCredentials credentials;
            if (StringUtils.hasText(credentialsJson)) {
                log.info("Initializing FirebaseApp using raw JSON credentials (FCM_CREDENTIALS_JSON)");
                credentials = GoogleCredentials.fromStream(
                        new ByteArrayInputStream(credentialsJson.trim().getBytes(StandardCharsets.UTF_8)));
            } else if (StringUtils.hasText(credentialsPath)) {
                log.info("Initializing FirebaseApp using credentials file from {}", credentialsPath);
                credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsPath.trim()));
            } else {
                log.warn("No FCM credentials provided (FCM_CREDENTIALS_JSON or FCM_CREDENTIALS_PATH). Real push dispatch is inactive.");
                return null;
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();

            FirebaseApp app = FirebaseApp.initializeApp(options);
            log.info("FirebaseApp initialized successfully for project: {}", app.getOptions().getProjectId());
            return app;
        } catch (IOException e) {
            log.error("Failed to initialize FirebaseApp with provided credentials: {}", e.getMessage(), e);
            return null;
        }
    }
}
