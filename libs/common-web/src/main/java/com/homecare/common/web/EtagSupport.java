package com.homecare.common.web;

import org.springframework.http.HttpStatus;

public final class EtagSupport {

    private EtagSupport() {
    }

    public static String format(Long version) {
        long safeVersion = version == null ? 0L : version;
        return "\"v" + safeVersion + "\"";
    }

    public static void verifyIfMatch(String ifMatchHeader, Long currentVersion) {
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            throw new PreconditionException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "PRECONDITION_REQUIRED",
                    "The If-Match header is required for this update.");
        }
        String expected = format(currentVersion);
        if (!expected.equals(ifMatchHeader.trim())) {
            throw new PreconditionException(
                    HttpStatus.PRECONDITION_FAILED,
                    "RESOURCE_VERSION_MISMATCH",
                    "The resource version does not match the provided If-Match header.");
        }
    }

    public static class PreconditionException extends RuntimeException {
        private final HttpStatus status;
        private final String errorCode;

        public PreconditionException(HttpStatus status, String errorCode, String message) {
            super(message);
            this.status = status;
            this.errorCode = errorCode;
        }

        public HttpStatus status() {
            return status;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
