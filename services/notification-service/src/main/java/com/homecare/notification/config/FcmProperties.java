package com.homecare.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "homecare.fcm")
public class FcmProperties {
    private String credentialsJson = "";
    private String credentialsPath = "";
}
