package com.homecare.identity;

import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** Focused public contract parity checks on actual HTTP representations. */
final class IdentityOpenApi {
    @SuppressWarnings("unchecked")
    static void profile(JsonNode response) throws Exception {
        Map<String,Object> document;
        try (var input = Files.newInputStream(Path.of("../../contracts/openapi/identity-service.yaml"))) {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
        assertThat(document.get("openapi")).isEqualTo("3.1.0");
        var paths = (Map<String,Object>)document.get("paths");
        assertThat(paths.keySet()).containsExactlyInAnyOrder("/api/v1/users/me", "/api/v1/users/me/bootstrap");
        assertThat(((Map<?,?>)paths.get("/api/v1/users/me")).keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder("get","patch");
        assertThat(((Map<?,?>)paths.get("/api/v1/users/me/bootstrap")).keySet().stream().map(Object::toString).toList()).containsExactly("post");
        var components = (Map<String,Object>)document.get("components");
        var schemas = (Map<String,Object>)components.get("schemas");
        var profile = (Map<String,Object>)schemas.get("Profile");
        var required = (List<String>)profile.get("required");
        assertThat(response.propertyNames()).containsExactlyInAnyOrderElementsOf(required);
        for (String field : List.of("id","role","status","createdAt","updatedAt")) assertThat(response.path(field).isTextual()).isTrue();
        assertThat(response.path("version").isIntegralNumber()).isTrue();
        assertThat(response.path("version").asLong()).isNotNegative();
        var role = (Map<String,Object>)((Map<String,Object>)profile.get("properties")).get("role");
        assertThat((List<String>)role.get("enum")).contains(response.path("role").asText());
    }
}
