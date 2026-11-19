package com.homecare.commerce.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "homecare.vnpay")
public class VnpayProperties {
    private String tmnCode = "HOMECARE01";
    private String hashSecret = "HOMECARE_VNPAY_SANDBOX_SECRET_KEY_2026";
    private String payUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private String returnUrl = "http://localhost:3000/orders/payment-result";
}
