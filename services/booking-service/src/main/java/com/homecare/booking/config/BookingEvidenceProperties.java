package com.homecare.booking.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "homecare.bookings")
public class BookingEvidenceProperties {
    private String evidenceBucket = "booking-work-evidence";
}
