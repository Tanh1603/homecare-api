package com.homecare.identity.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.homecare.identity.dto.AuthDto.ForgotPasswordRequest;
import com.homecare.identity.dto.AuthDto.LoginRequest;
import com.homecare.identity.dto.AuthDto.LogoutRequest;
import com.homecare.identity.dto.AuthDto.RefreshTokenRequest;
import com.homecare.identity.dto.AuthDto.RegisterRequest;
import com.homecare.identity.dto.AuthDto.TokenResponse;
import com.homecare.identity.dto.LoginResponse;
import com.homecare.identity.entity.UserProfile;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;
import com.homecare.identity.repository.UserRepository;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String TOKEN_ENDPOINT = "/realms/%s/protocol/openid-connect/token";
    private static final String LOGOUT_ENDPOINT = "/realms/%s/protocol/openid-connect/logout";
    private static final String PASSWORD_GRANT = "password";
    private static final String REFRESH_GRANT = "refresh_token";
    private static final String VERIFY_EMAIL_ACTION = "VERIFY_EMAIL";
    private static final String UPDATE_PASSWORD_ACTION = "UPDATE_PASSWORD";

    private final Keycloak keycloakAdminClient;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    @Value("${keycloak.server-url}")
    private String serverUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${keycloak.token-scope:openid profile email roles}")
    private String tokenScope;

    @Value("${keycloak.default-role:CUSTOMER}")
    private String defaultRole;

    @Transactional
    public UserProfile register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IdentityException(ApiErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IdentityException(ApiErrorCode.EMAIL_ALREADY_EXISTS);
        }

        UserRepresentation keycloakUser = new UserRepresentation();
        keycloakUser.setUsername(request.getUsername());
        keycloakUser.setEmail(request.getEmail());
        keycloakUser.setFirstName(request.getFirstName());
        keycloakUser.setLastName(request.getLastName());
        keycloakUser.setEnabled(true);
        keycloakUser.setEmailVerified(false);
        keycloakUser.setRequiredActions(List.of(VERIFY_EMAIL_ACTION));

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(request.getPassword());
        credential.setTemporary(false);
        keycloakUser.setCredentials(Collections.singletonList(credential));

        UsersResource usersResource = keycloakAdminClient.realm(realm).users();
        String keycloakUserId = createKeycloakUser(usersResource, keycloakUser);

        UserProfile profile = UserProfile.builder()
                .keycloakUserId(keycloakUserId)
                .username(request.getUsername())
                .email(request.getEmail())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .address(request.getAddress())
                .build();

        try {
            assignDefaultRole(usersResource, keycloakUserId);
            return userRepository.save(profile);
        } catch (RuntimeException exception) {
            removeKeycloakUser(usersResource, keycloakUserId);
            throw exception;
        }
    }

    public LoginResponse login(LoginRequest request) {
        try {
            Map<?, ?> tokenBody = requestToken(
                    TOKEN_ENDPOINT,
                    passwordGrantBody(request.getEmail(), request.getPassword()));
            return LoginResponse.builder()
                    .requiresEmailVerification(false)
                    .email(request.getEmail())
                    .tokens(toTokenResponse(tokenBody))
                    .build();
        } catch (HttpClientErrorException exception) {
            if (isInvalidGrant(exception) && requiresEmailVerification(request.getEmail())) {
                throw new IdentityException(ApiErrorCode.EMAIL_VERIFICATION_REQUIRED);
            }
            log.warn("Keycloak rejected login status={}", exception.getStatusCode().value());
            throw new IdentityException(ApiErrorCode.INVALID_CREDENTIALS);
        } catch (RestClientException exception) {
            log.error("Keycloak login endpoint failed exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new IdentityException(ApiErrorCode.AUTH_PROVIDER_UNAVAILABLE, exception);
        }
    }

    public void verifyCurrentPassword(String username, String currentPassword) {
        try {
            requestToken(TOKEN_ENDPOINT, passwordGrantBody(username, currentPassword));
        } catch (HttpClientErrorException exception) {
            log.warn("Keycloak rejected current password status={}", exception.getStatusCode().value());
            throw new IdentityException(ApiErrorCode.INVALID_CREDENTIALS);
        } catch (RestClientException exception) {
            log.error("Keycloak current-password verification failed exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new IdentityException(ApiErrorCode.AUTH_PROVIDER_UNAVAILABLE, exception);
        }
    }

    public TokenResponse refreshToken(RefreshTokenRequest request) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", REFRESH_GRANT);
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", request.getRefreshToken());

        try {
            return toTokenResponse(requestToken(TOKEN_ENDPOINT, body));
        } catch (HttpClientErrorException exception) {
            log.warn("Keycloak rejected refresh token status={}", exception.getStatusCode().value());
            throw new IdentityException(ApiErrorCode.INVALID_REFRESH_TOKEN);
        } catch (RestClientException exception) {
            log.error("Keycloak refresh endpoint failed exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new IdentityException(ApiErrorCode.AUTH_PROVIDER_UNAVAILABLE, exception);
        }
    }

    public void sendVerificationEmail(String email) {
        UsersResource usersResource = usersResource();
        List<UserRepresentation> users = searchUsersByEmail(usersResource, email);

        if (users.isEmpty()) {
            throw new IdentityException(ApiErrorCode.USER_NOT_FOUND);
        }

        UserRepresentation user = users.getFirst();
        if (Boolean.TRUE.equals(user.isEmailVerified())) {
            throw new IdentityException(ApiErrorCode.EMAIL_ALREADY_VERIFIED);
        }

        callKeycloak(() -> {
            usersResource.get(user.getId()).sendVerifyEmail();
            return null;
        });
    }

    public void forgotPassword(ForgotPasswordRequest request) {
        UsersResource usersResource = usersResource();
        List<UserRepresentation> users = searchUsersByEmail(usersResource, request.getEmail());

        if (users.isEmpty()) {
            return;
        }

        UserRepresentation user = users.getFirst();
        callKeycloak(() -> {
            usersResource.get(user.getId()).executeActionsEmail(List.of(UPDATE_PASSWORD_ACTION));
            return null;
        });
    }

    public void logout(LogoutRequest request) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", request.getRefreshToken());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    serverUrl + String.format(LOGOUT_ENDPOINT, realm),
                    new HttpEntity<>(body, headers),
                    String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Keycloak logout returned non-success status={}", response.getStatusCode().value());
                throw new IdentityException(ApiErrorCode.OPERATION_FAILED);
            }
        } catch (HttpClientErrorException exception) {
            log.warn("Keycloak rejected logout status={}", exception.getStatusCode().value());
            throw new IdentityException(ApiErrorCode.INVALID_REFRESH_TOKEN);
        } catch (RestClientException exception) {
            log.error("Keycloak logout endpoint failed exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new IdentityException(ApiErrorCode.AUTH_PROVIDER_UNAVAILABLE, exception);
        }
    }

    private String createKeycloakUser(UsersResource usersResource, UserRepresentation user) {
        try (Response response = usersResource.create(user)) {
            if (response.getStatus() != 201 || response.getLocation() == null) {
                log.warn("Keycloak user creation returned status={}", response.getStatus());
                ApiErrorCode errorCode = response.getStatus() == 409
                        ? ApiErrorCode.CONFLICT
                        : ApiErrorCode.OPERATION_FAILED;
                throw new IdentityException(errorCode);
            }
            String path = response.getLocation().getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (IdentityException exception) {
            throw exception;
        } catch (WebApplicationException exception) {
            log.warn("Keycloak rejected user creation status={}",
                    exception.getResponse().getStatus());
            throw new IdentityException(ApiErrorCode.CONFLICT, exception);
        } catch (RuntimeException exception) {
            log.error("Keycloak user creation failed exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new IdentityException(ApiErrorCode.AUTH_PROVIDER_UNAVAILABLE, exception);
        }
    }

    private void assignDefaultRole(UsersResource usersResource, String userId) {
        callKeycloak(() -> {
            var role = keycloakAdminClient.realm(realm).roles().get(defaultRole).toRepresentation();
            usersResource.get(userId).roles().realmLevel().add(Collections.singletonList(role));
            return null;
        });
    }

    private void removeKeycloakUser(UsersResource usersResource, String userId) {
        try {
            usersResource.get(userId).remove();
        } catch (RuntimeException cleanupException) {
            log.error("Keycloak user cleanup after failed registration failed exceptionType={}",
                    cleanupException.getClass().getSimpleName());
        }
    }

    private MultiValueMap<String, String> passwordGrantBody(String username, String password) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", PASSWORD_GRANT);
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("username", username);
        body.add("password", password);
        body.add("scope", tokenScope);
        return body;
    }

    private Map<?, ?> requestToken(String endpointTemplate, MultiValueMap<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                serverUrl + String.format(endpointTemplate, realm),
                HttpMethod.POST,
                entity,
                Map.class);
        Map<?, ?> tokenBody = response.getBody();
        if (tokenBody == null) {
            throw new IdentityException(ApiErrorCode.OPERATION_FAILED);
        }
        return tokenBody;
    }

    private TokenResponse toTokenResponse(Map<?, ?> tokenBody) {
        Object accessToken = tokenBody.get("access_token");
        Object expiresIn = tokenBody.get("expires_in");
        Object tokenType = tokenBody.get("token_type");
        if (!(accessToken instanceof String) || !(expiresIn instanceof Number) || !(tokenType instanceof String)) {
            throw new IdentityException(ApiErrorCode.OPERATION_FAILED);
        }

        TokenResponse token = new TokenResponse();
        token.setAccessToken((String) accessToken);
        token.setRefreshToken(tokenBody.get("refresh_token") instanceof String refreshToken ? refreshToken : null);
        token.setExpiresIn(((Number) expiresIn).longValue());
        token.setTokenType((String) tokenType);
        return token;
    }

    private boolean isInvalidGrant(HttpClientErrorException exception) {
        String responseBody = exception.getResponseBodyAsString();
        return responseBody.contains("invalid_grant")
                || responseBody.contains("Account is not fully set up");
    }

    private boolean requiresEmailVerification(String email) {
        List<UserRepresentation> users = searchUsersByEmail(usersResource(), email);
        return !users.isEmpty() && Boolean.FALSE.equals(users.getFirst().isEmailVerified());
    }

    private UsersResource usersResource() {
        return callKeycloak(() -> keycloakAdminClient.realm(realm).users());
    }

    private List<UserRepresentation> searchUsersByEmail(UsersResource usersResource, String email) {
        return callKeycloak(() -> usersResource.searchByEmail(email, true));
    }

    private <T> T callKeycloak(KeycloakCall<T> call) {
        try {
            return call.execute();
        } catch (IdentityException exception) {
            throw exception;
        } catch (WebApplicationException exception) {
            log.warn("Keycloak operation rejected status={}", exception.getResponse().getStatus());
            throw new IdentityException(ApiErrorCode.OPERATION_FAILED, exception);
        } catch (RuntimeException exception) {
            log.error("Keycloak operation failed exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new IdentityException(ApiErrorCode.AUTH_PROVIDER_UNAVAILABLE, exception);
        }
    }

    @FunctionalInterface
    private interface KeycloakCall<T> {
        T execute();
    }
}
