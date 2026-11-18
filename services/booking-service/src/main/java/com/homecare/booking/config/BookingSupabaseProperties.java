package com.homecare.booking.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "homecare.supabase")
public class BookingSupabaseProperties {
    private String url = "";
    private String serviceRoleKey = "";
}
