package com.homecare.identity.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.homecare.identity.config.WebhookProperties;
import com.homecare.identity.service.UserService; // chỉnh lại theo package của bạn

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper; // Jackson 3

@Slf4j
@RestController
@RequestMapping("/internal/webhooks")
@RequiredArgsConstructor
public class KeycloakWebhookController {

    private final UserService users;
    private final WebhookProperties props; // chỉnh import theo package của bạn
    private final JsonMapper mapper; // thay cho ObjectMapper

    @PostMapping("/keycloak")
    @SuppressWarnings("unchecked")
    public void handle(@RequestBody String rawBody,
            @RequestHeader(value = "X-Keycloak-Signature", required = false) String signature)
            throws Exception {
        verify(rawBody, signature);

        Map<String, Object> e = mapper.readValue(rawBody, Map.class);
        String type = str(e.get("type"));
        if (type == null)
            return;
        type = type.substring(type.lastIndexOf('.') + 1); // "access.REGISTER" -> "REGISTER"

        String userId = str(e.get("userId"));
        if (userId == null)
            return;
        UUID id = UUID.fromString(userId);

        Map<String, Object> d = e.get("details") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();

        switch (type) {
            case "REGISTER" -> users.upsert(id, str(d.get("username")), str(d.get("email")),
                    str(d.get("first_name")), str(d.get("last_name")));
            case "LOGIN" -> {
                users.upsert(id, str(d.get("username")), str(d.get("email")), null, null);
                users.touchLogin(id);
            }
            case "DELETE_ACCOUNT" -> users.markDeleted(id);
            default -> log.debug("Bỏ qua event {}", type);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private void verify(String body, String signature) throws Exception {
        if (signature == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing signature");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        String given = signature.replaceFirst("(?i)^sha256=", "");
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid signature");
        }
    }
}