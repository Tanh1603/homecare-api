package com.homecare.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "webhook")
public record WebhookProperties(String secret) {
}
