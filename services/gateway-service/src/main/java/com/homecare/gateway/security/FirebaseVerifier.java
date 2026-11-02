package com.homecare.gateway.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import java.util.Collections;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FirebaseVerifier {
    private final FirebaseAuth auth;
    private final String project;
    public FirebaseVerifier(FirebaseAuth auth, @Value("${homecare.firebase.project-id}") String project) {
        this.auth = auth; this.project = project;
        if (System.getenv("FIREBASE_AUTH_EMULATOR_HOST") != null)
            throw new IllegalStateException("Unsigned Firebase emulator tokens are not supported by this trust boundary");
    }
    public VerifiedSubject verify(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Authorization"));
        if (values.isEmpty()) throw new SecurityFailure(401, "AUTHENTICATION_REQUIRED");
        if (values.size() != 1 || !values.getFirst().matches("Bearer [A-Za-z0-9_.-]+"))
            throw new SecurityFailure(401, "AUTHENTICATION_INVALID");
        return verify(values.getFirst().substring(7));
    }
    public VerifiedSubject verify(String token) {
        try {
            var verified = auth.verifyIdToken(token, true);
            return new VerifiedSubject(project, verified.getUid());
        } catch (FirebaseAuthException e) {
            String code = String.valueOf(e.getAuthErrorCode());
            if (java.util.Set.of("INVALID_ID_TOKEN", "EXPIRED_ID_TOKEN", "REVOKED_ID_TOKEN", "USER_DISABLED",
                    "USER_NOT_FOUND", "TENANT_ID_MISMATCH").contains(code))
                throw new SecurityFailure(401, "AUTHENTICATION_INVALID");
            throw new SecurityFailure(503, "AUTHENTICATION_UNAVAILABLE");
        } catch (IllegalArgumentException e) {
            throw new SecurityFailure(401, "AUTHENTICATION_INVALID");
        }
    }
}
