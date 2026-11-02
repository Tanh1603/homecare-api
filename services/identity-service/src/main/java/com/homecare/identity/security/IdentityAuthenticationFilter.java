package com.homecare.identity.security;

import com.homecare.identity.infrastructure.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import javax.naming.ldap.LdapName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class IdentityAuthenticationFilter extends OncePerRequestFilter {
    private final FirebaseVerifier verifier;
    private final ObjectMapper json;
    private final Set<String> callers;
    public IdentityAuthenticationFilter(FirebaseVerifier verifier, ObjectMapper json, Set<String> callers) {
        this.verifier = verifier; this.json = json; this.callers = Set.copyOf(callers);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            var certificates = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
            if (!request.isSecure() || certificates == null || certificates.length == 0)
                throw new SecurityFailure(403, "WORKLOAD_FORBIDDEN");
            String workload = workload(certificates[0]);
            if (!callers.contains(workload)) throw new SecurityFailure(403, "WORKLOAD_FORBIDDEN");
            // Only Gateway may call public self-profile operations; internal resolve has its own workload allowlist.
            if (!request.getRequestURI().equals("/internal/v1/principals/resolve") && !workload.equals("gateway"))
                throw new SecurityFailure(403, "WORKLOAD_FORBIDDEN");
            var subject = verifier.verify(request);
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(subject, null, List.of()));
            chain.doFilter(request, response);
        } catch (SecurityFailure e) {
            response.setStatus(e.status); response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), ApiErrors.body(e.code, "Yeu cau bi tu choi.", List.of(), request));
        } finally { SecurityContextHolder.clearContext(); }
    }
    private String workload(X509Certificate cert) {
        try {
            var names = new LdapName(cert.getSubjectX500Principal().getName()).getRdns().stream()
                    .filter(r -> r.getType().equalsIgnoreCase("CN")).map(r -> r.getValue().toString()).toList();
            if (names.size() != 1) throw new SecurityFailure(403, "WORKLOAD_FORBIDDEN");
            return names.getFirst();
        } catch (javax.naming.InvalidNameException e) { throw new SecurityFailure(403, "WORKLOAD_FORBIDDEN"); }
    }
}
