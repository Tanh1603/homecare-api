package com.homecare.common.web;

import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationIdSupport {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String REQUEST_ATTRIBUTE = "com.homecare.common.web.correlationId";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationIdSupport() {
    }

    public static boolean isValid(String candidate) {
        return candidate != null && VALID_ID.matcher(candidate).matches();
    }

    public static String resolveOrGenerate(String suppliedId) {
        return isValid(suppliedId) ? suppliedId : UUID.randomUUID().toString();
    }
}
