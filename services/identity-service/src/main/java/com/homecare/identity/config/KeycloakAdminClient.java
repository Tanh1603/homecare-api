package com.homecare.identity.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class KeycloakAdminClient {

    private final KeycloakProperties props;
    private final RestClient http = RestClient.create();

    public KeycloakAdminClient(KeycloakProperties props) {
        this.props = props;
    }

    private String adminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", props.adminUsername());
        form.add("password", props.adminPassword());
        JsonNode res = http.post()
                .uri(props.baseUrl() + "/realms/master/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode.class);
        return res.get("access_token").asText();
    }

    public UUID createUser(String username, String email, String first, String last, String password) {
        Map<String, Object> body = Map.of(
                "username", username,
                "email", email,
                "firstName", first == null ? "" : first,
                "lastName", last == null ? "" : last,
                "enabled", true,
                "emailVerified", true,
                "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false)));
        try {
            var res = http.post()
                    .uri(props.baseUrl() + "/admin/realms/" + props.realm() + "/users")
                    .header("Authorization", "Bearer " + adminToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
            String loc = res.getHeaders().getFirst("Location"); // .../users/{id}
            return UUID.fromString(loc.substring(loc.lastIndexOf('/') + 1));
        } catch (HttpClientErrorException.Conflict e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username hoặc email đã tồn tại");
        }
    }
}