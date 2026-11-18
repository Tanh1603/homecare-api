package com.homecare.provider.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.auth")
public class ProviderAuthProperties {
    private Duration userBlacklistTtl = Duration.ofHours(24);
}
