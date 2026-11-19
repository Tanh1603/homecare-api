package com.homecare.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

public class IdempotencyService {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final int DEFAULT_MAX_ENTRIES = 10_000;
    private final Map<String, StoredOperation<?>> store = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;

    public IdempotencyService() {
        this(DEFAULT_TTL, DEFAULT_MAX_ENTRIES);
    }

    public IdempotencyService(Duration ttl, int maxEntries) {
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        this.maxEntries = Math.max(1, maxEntries);
    }

    public int size() {
        return store.size();
    }

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

        String fingerprint = sha256Hex(payloadJson == null ? "" : payloadJson);
        String compositeKey = (actorScope == null ? "anon" : actorScope)
                + "|" + operationScope
                + "|" + idempotencyKey;

        StoredOperation<T> candidate = new StoredOperation<>(
                fingerprint, new CompletableFuture<>(), now.plus(ttl));
        StoredOperation<?> existing = store.putIfAbsent(compositeKey, candidate);
        while (existing != null) {
            if (!existing.expiresAt().isAfter(now)) {
                if (store.replace(compositeKey, existing, candidate)) {
                    existing = null;
                    break;
                }
                existing = store.putIfAbsent(compositeKey, candidate);
                continue;
            }
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

        if (store.size() > maxEntries) {
            evictIfOverCapacity(now, compositeKey);
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

    private void evictIfOverCapacity(Instant now, String protectedKey) {
        store.entrySet().removeIf(e -> !e.getKey().equals(protectedKey) && !e.getValue().expiresAt().isAfter(now));
        int excess = store.size() - maxEntries;
        if (excess <= 0) {
            return;
        }
        store.entrySet().stream()
                .filter(e -> !e.getKey().equals(protectedKey))
                .sorted(Comparator.comparing(e -> e.getValue().expiresAt()))
                .limit(excess)
                .toList()
                .forEach(e -> store.remove(e.getKey(), e.getValue()));
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
