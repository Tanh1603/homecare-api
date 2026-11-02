package com.homecare.identity.profile;

import com.homecare.identity.security.VerifiedSubject;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public class ProfileController {
    private final ProfileService profiles;
    public ProfileController(ProfileService profiles) { this.profiles = profiles; }
    @GetMapping("/api/v1/users/me")
    ResponseEntity<Profile> me(@AuthenticationPrincipal VerifiedSubject subject, HttpServletRequest request) throws java.io.IOException {
        noQuery(request);
        if (request.getInputStream().read() != -1) throw new ProfileFailure(400, "REQUEST_VALIDATION_FAILED");
        return response(profiles.me(subject));
    }
    @PatchMapping(value = "/api/v1/users/me", consumes = "application/json")
    ResponseEntity<Profile> patch(@AuthenticationPrincipal VerifiedSubject subject, @RequestBody ProfilePatch body, HttpServletRequest request) {
        noQuery(request); return response(profiles.patch(subject, singleHeader(request, "If-Match"), body));
    }
    @PostMapping(value = "/api/v1/users/me/bootstrap", consumes = "application/json")
    ResponseEntity<Profile> bootstrap(@AuthenticationPrincipal VerifiedSubject subject, @RequestBody EmptyRequest body, HttpServletRequest request) {
        noQuery(request);
        var result = profiles.bootstrap(subject, singleHeader(request, "Idempotency-Key"), body);
        return ResponseEntity.status(result.status()).location(URI.create("/api/v1/users/me"))
                .eTag("v" + result.profile().version()).body(result.profile());
    }
    @PostMapping(value = "/internal/v1/principals/resolve", consumes = "application/json")
    Map<String, Object> resolve(@AuthenticationPrincipal VerifiedSubject subject, @RequestBody EmptyRequest body, HttpServletRequest request) {
        noQuery(request);
        var profile = profiles.resolve(subject);
        return Map.of("profileId", profile.id(), "role", profile.role(), "status", profile.status(), "version", profile.version());
    }
    private ResponseEntity<Profile> response(Profile profile) { return ResponseEntity.ok().eTag("v" + profile.version()).body(profile); }
    private void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new ProfileFailure(400, "REQUEST_VALIDATION_FAILED");
    }
    private String singleHeader(HttpServletRequest request, String header) {
        var values = Collections.list(request.getHeaders(header));
        if (values.size() > 1) throw new ProfileFailure(400, "REQUEST_VALIDATION_FAILED");
        return values.isEmpty() ? null : values.getFirst();
    }
}
