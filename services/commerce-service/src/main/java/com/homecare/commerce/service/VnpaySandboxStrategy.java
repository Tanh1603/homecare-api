package com.homecare.commerce.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.homecare.commerce.config.VnpayProperties;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;

@Component
public class VnpaySandboxStrategy {

    private static final DateTimeFormatter VNP_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final String tmnCode;
    private final String hashSecret;
    private final String payUrl;
    private final String returnUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public VnpaySandboxStrategy(VnpayProperties properties) {
        this(properties.getTmnCode(), properties.getHashSecret(), properties.getPayUrl(), properties.getReturnUrl());
    }

    public VnpaySandboxStrategy(
            String tmnCode,
            String hashSecret,
            String payUrl,
            String returnUrl) {
        this.tmnCode = tmnCode;
        this.hashSecret = hashSecret;
        this.payUrl = payUrl;
        this.returnUrl = returnUrl;
    }

    public String getTmnCode() {
        return tmnCode;
    }

    public String buildPaymentUrl(String txnRef, long amountVnd, String orderInfo) {
        return buildPaymentUrl(txnRef, amountVnd, orderInfo, null);
    }

    public String buildPaymentUrl(String txnRef, long amountVnd, String orderInfo, String customReturnUrl) {
        String effectiveReturnUrl = (customReturnUrl != null && !customReturnUrl.isBlank())
                ? validateReturnUrl(customReturnUrl.trim())
                : returnUrl;

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_Amount", String.valueOf(amountVnd * 100L));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_OrderInfo", orderInfo);
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", effectiveReturnUrl);
        params.put("vnp_IpAddr", "127.0.0.1");
        params.put("vnp_CreateDate", now.format(VNP_TIME_FORMAT));

        String query = buildCanonicalQuery(params);
        String secureHash = hmacSha512(hashSecret, query);
        return payUrl + "?" + query + "&vnp_SecureHash=" + secureHash;
    }

    public boolean verifyIpnSignature(Map<String, String> rawParams) {
        String providedHash = rawParams.get("vnp_SecureHash");
        if (providedHash == null || providedHash.isBlank()) {
            return false;
        }
        Map<String, String> sorted = new TreeMap<>();
        rawParams.forEach((k, v) -> {
            if (k != null && k.startsWith("vnp_")
                    && !"vnp_SecureHash".equals(k)
                    && !"vnp_SecureHashType".equals(k)
                    && v != null && !v.isEmpty()) {
                sorted.put(k, v);
            }
        });
        String canonical = buildCanonicalQuery(sorted);
        String expected = hmacSha512(hashSecret, canonical);
        return java.security.MessageDigest.isEqual(
                expected.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                providedHash.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    public String signParamsForTest(Map<String, String> params) {
        Map<String, String> sorted = new TreeMap<>(params);
        sorted.remove("vnp_SecureHash");
        sorted.remove("vnp_SecureHashType");
        return hmacSha512(hashSecret, buildCanonicalQuery(sorted));
    }

    private String validateReturnUrl(String candidate) {
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException e) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Invalid returnUrl.");
        }
        String scheme = uri.getScheme();
        if ("homecare".equalsIgnoreCase(scheme)) {
            return candidate;
        }
        if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || uri.getUserInfo() != null
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Untrusted returnUrl origin.");
        }
        URI defaultUri = URI.create(returnUrl);
        String allowedHost = defaultUri.getHost();
        String host = uri.getHost();
        boolean matchesConfiguredHost = allowedHost != null && allowedHost.equalsIgnoreCase(host);
        boolean matchesLocalhost = isLocalhost(allowedHost) && isLocalhost(host);
        if (!matchesConfiguredHost && !matchesLocalhost) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Untrusted returnUrl origin.");
        }
        return candidate;
    }

    private boolean isLocalhost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }

    private String buildCanonicalQuery(Map<String, String> sortedParams) {
        return sortedParams.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
    }

    public static String hmacSha512(String key, String data) {
        try {
            Mac hmac512 = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            hmac512.init(secretKey);
            byte[] bytes = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA512", e);
        }
    }
}
