package com.homecare.identity.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
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
import org.springframework.web.client.RestTemplate;

import com.homecare.identity.dto.AuthDto.ForgotPasswordRequest;
import com.homecare.identity.dto.AuthDto.LoginRequest;
import com.homecare.identity.dto.AuthDto.LogoutRequest;
import com.homecare.identity.dto.AuthDto.RefreshTokenRequest;
import com.homecare.identity.dto.AuthDto.RegisterRequest;
import com.homecare.identity.dto.AuthDto.TokenResponse;
import com.homecare.identity.dto.LoginResponse;
import com.homecare.identity.entity.UserProfile;
import com.homecare.identity.repository.UserRepository;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final Keycloak keycloakAdminClient;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${keycloak.server-url}")
    private String serverUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    /**
     * US-HU-001: Đăng ký tài khoản
     */
    @Transactional
    public UserProfile register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Tên đăng nhập đã tồn tại");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email đã được sử dụng");
        }

        // Tạo User trên Keycloak
        UserRepresentation keycloakUser = new UserRepresentation();
        keycloakUser.setUsername(request.getUsername());
        keycloakUser.setEmail(request.getEmail());
        keycloakUser.setFirstName(request.getFirstName());
        keycloakUser.setLastName(request.getLastName());
        keycloakUser.setEnabled(true);
        keycloakUser.setEmailVerified(false); // Chưa verify
        keycloakUser.setRequiredActions(List.of("VERIFY_EMAIL"));

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(request.getPassword());
        credential.setTemporary(false);
        keycloakUser.setCredentials(Collections.singletonList(credential));

        UsersResource usersResource = keycloakAdminClient.realm(realm).users();
        Response response = usersResource.create(keycloakUser);

        if (response.getStatus() != 201) {
            throw new RuntimeException("Lỗi tạo tài khoản trên Keycloak");
        }

        String path = response.getLocation().getPath();
        String keycloakUserId = path.substring(path.lastIndexOf('/') + 1);

        // Gán role mặc định ROLE_CUSTOMER
        try {
            var roleRep = keycloakAdminClient.realm(realm).roles().get("CUSTOMER").toRepresentation();
            usersResource.get(keycloakUserId).roles().realmLevel().add(Collections.singletonList(roleRep));
        } catch (Exception e) {
            log.warn("Không thể gán role ROLE_CUSTOMER: {}", e.getMessage());
        }

        // Lưu Local DB
        UserProfile profile = UserProfile.builder()
                .keycloakUserId(keycloakUserId)
                .username(request.getUsername())
                .email(request.getEmail())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .address(request.getAddress())
                .build();

        return userRepository.save(profile);
    }

    /**
     * US-HU-001: Đăng nhập & Lấy Access Token từ Keycloak
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public LoginResponse login(LoginRequest request) {
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", serverUrl, realm);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("username", request.getEmail());
        body.add("password", request.getPassword());
        body.add("scope", "openid profile email roles");

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();

            TokenResponse tokenResponse = new TokenResponse();
            tokenResponse.setAccessToken((String) responseBody.get("access_token"));
            tokenResponse.setRefreshToken((String) responseBody.get("refresh_token"));
            tokenResponse.setExpiresIn(((Number) responseBody.get("expires_in")).longValue());
            tokenResponse.setTokenType((String) responseBody.get("token_type"));
            return LoginResponse.builder()
                    .requiresEmailVerification(false)
                    .email(request.getEmail())
                    .tokens(tokenResponse)
                    .message("Đăng nhập thành công")
                    .build();

        } catch (HttpClientErrorException e) {
            String responseString = e.getResponseBodyAsString();
            log.warn("Login Keycloak Error: {}", responseString);

            if (responseString.contains("Account is not fully set up") || responseString.contains("invalid_grant")) {
                UsersResource usersResource = keycloakAdminClient.realm(realm).users();
                List<UserRepresentation> users = usersResource.searchByEmail(request.getEmail(), true);

                if (!users.isEmpty() && Boolean.FALSE.equals(users.getFirst().isEmailVerified())) {
                    String targetEmail = users.getFirst().getEmail();

                    return LoginResponse.builder()
                            .requiresEmailVerification(true)
                            .email(targetEmail)
                            .message("Tài khoản chưa được xác thực email. Vui lòng kiểm tra hộp thư.")
                            .build();
                }
            }

            throw new RuntimeException("Tên đăng nhập hoặc mật khẩu không chính xác");
        } catch (Exception e) {
            log.error("Đăng nhập thất bại: {}", e.getMessage());
            throw new RuntimeException("Tài khoản hoặc mật khẩu không chính xác");
        }
    }

    /**
     * Đổi Access Token mới bằng Refresh Token
     */
    public TokenResponse refreshToken(RefreshTokenRequest request) {
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", serverUrl, realm);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", request.getRefreshToken());

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();

            TokenResponse tokenResponse = new TokenResponse();
            tokenResponse.setAccessToken((String) responseBody.get("access_token"));
            tokenResponse.setRefreshToken((String) responseBody.get("refresh_token"));
            tokenResponse.setExpiresIn(((Number) responseBody.get("expires_in")).longValue());
            tokenResponse.setTokenType((String) responseBody.get("token_type"));
            return tokenResponse;
        } catch (Exception e) {
            log.error("Refresh token thất bại hoặc token đã hết hạn: {}", e.getMessage());
            throw new RuntimeException("Refresh token không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại.");
        }
    }

    /**
     * 2. Endpoint riêng: Gửi (hoặc gửi lại) Email xác minh
     */
    public void sendVerificationEmail(String email) {
        UsersResource usersResource = keycloakAdminClient.realm(realm).users();
        List<UserRepresentation> users = usersResource.searchByEmail(email, true);

        if (users.isEmpty()) {
            throw new RuntimeException("Không tìm thấy người dùng với email: " + email);
        }

        UserRepresentation user = users.getFirst();
        if (Boolean.TRUE.equals(user.isEmailVerified())) {
            throw new RuntimeException("Email này đã được xác thực trước đó");
        }

        // Gọi Keycloak kích hoạt gửi mail xác nhận
        usersResource.get(user.getId()).sendVerifyEmail();
    }

    /**
     * Quên mật khẩu: Gửi email reset password từ Keycloak
     */
    public void forgotPassword(ForgotPasswordRequest request) {
        UsersResource usersResource = keycloakAdminClient.realm(realm).users();
        List<UserRepresentation> users = usersResource.searchByEmail(request.getEmail(), true);

        if (users.isEmpty()) {
            // Không tiết lộ email có tồn tại hay không vì lý do bảo mật
            log.info("Không tìm thấy user với email: {}", request.getEmail());
            return;
        }

        UserRepresentation user = users.getFirst();
        // Gọi Keycloak kích hoạt email reset password
        usersResource.get(user.getId()).executeActionsEmail(List.of("UPDATE_PASSWORD"));
    }

    /**
     * Đăng xuất: Vô hiệu hóa Refresh Token & Revoke User Session trên Keycloak
     */
    public void logout(LogoutRequest request) {
        String logoutUrl = String.format("%s/realms/%s/protocol/openid-connect/logout", serverUrl, realm);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", request.getRefreshToken());

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(logoutUrl, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Keycloak logout response status: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Lỗi khi revoke refresh token trên Keycloak: {}", e.getMessage());
            throw new RuntimeException("Đăng xuất thất bại hoặc Refresh Token không hợp lệ");
        }
    }
}