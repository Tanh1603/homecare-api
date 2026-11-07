package com.homecare.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.homecare.identity.entity.UserProfile;

class InternalAndAdminIdentityTest {

    @Test
    void keycloakRealmJsonSeedsAllFiveRolesAndDemoUsers() throws Exception {
        String realmJson = Files.readString(Path.of("../../keycloak/import/homecare-realm.json"));
        assertThat(realmJson).contains("\"CUSTOMER\"", "\"TECHNICIAN\"", "\"PROVIDER\"", "\"MERCHANT\"", "\"ADMIN\"");
        assertThat(realmJson).contains("demo-customer", "demo-technician", "demo-provider", "demo-merchant", "demo-admin");
    }

    @Test
    void userProfileEntityDoesNotDuplicateKeycloakRoleOrVersion() {
        Set<String> fieldNames = Arrays.stream(UserProfile.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());
        assertThat(fieldNames)
                .contains("id", "keycloakUserId", "username", "email", "firstName", "lastName", "phone", "address", "status")
                .doesNotContain("role", "version");
    }
}
