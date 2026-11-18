package com.homecare.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@EnableConfigurationProperties(HomecareSecurityProperties.class)
public class HomecareCommonPropertiesAutoConfiguration {
}
