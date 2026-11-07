package com.homecare.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

public class IdempotencyService {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private final Map<String, StoredOperation<?>> store = new ConcurrentHashMap<>();

    public static void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || !KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new IdempotencyException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Idempotency-Key must match ^[A-Za-z0-9_-]{16,128}$.");
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T execute(
            String actorScope,
            String operationScope,
            String idempotencyKey,
            String payloadJson,
            Supplier<T> action) {
        validateKey(idempotencyKey);
        String fingerprint = sha256Hex(payloadJson == null ? "" : payloadJson);
        String compositeKey = (actorScope == null ? "anon" : actorScope)
                + "|" + operationScope
                + "|" + idempotencyKey;

        StoredOperation<?> existing = store.get(compositeKey);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new IdempotencyException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request payload.");
            }
            return (T) existing.response();
        }

        T result = action.get();
        StoredOperation<?> previous = store.putIfAbsent(compositeKey, new StoredOperation<>(fingerprint, result));
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) {
                throw new IdempotencyException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request payload.");
            }
            return (T) previous.response();
        }
        return result;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record StoredOperation<T>(String fingerprint, T response) {
    }

    public static class IdempotencyException extends RuntimeException {
        private final HttpStatus status;
        private final String errorCode;

        public IdempotencyException(HttpStatus status, String errorCode, String message) {
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
