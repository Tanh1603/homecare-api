package com.homecare.household.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "homecare.locations")
public class LocationProperties {

    private boolean autoSeedIfEmpty = true;
}
