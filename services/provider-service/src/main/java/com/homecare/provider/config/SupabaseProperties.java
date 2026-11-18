package com.homecare.provider.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "supabase")
public class SupabaseProperties {
    private String url = "https://placeholder.supabase.co";
    private String secretKey = "placeholder-secret-key";
    private String organizationRoleId = "00000000-0000-0000-0001-000000000002";
}
