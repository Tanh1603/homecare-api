package com.homecare.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

public class IdempotencyService {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
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
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));

        String fingerprint = sha256Hex(payloadJson == null ? "" : payloadJson);
        String compositeKey = (actorScope == null ? "anon" : actorScope)
                + "|" + operationScope
                + "|" + idempotencyKey;

        StoredOperation<T> candidate = new StoredOperation<>(
                fingerprint, new CompletableFuture<>(), now.plus(DEFAULT_TTL));
        StoredOperation<?> existing = store.putIfAbsent(compositeKey, candidate);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new IdempotencyException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request payload.");
            }
            try {
                return (T) existing.future().join();
            } catch (CompletionException ex) {
                if (ex.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw ex;
            }
        }

        try {
            T result = action.get();
            candidate.future().complete(result);
            return result;
        } catch (Throwable t) {
            store.remove(compositeKey, candidate);
            candidate.future().completeExceptionally(t);
            throw t;
        }
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

    private record StoredOperation<T>(String fingerprint, CompletableFuture<T> future, Instant expiresAt) {
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
