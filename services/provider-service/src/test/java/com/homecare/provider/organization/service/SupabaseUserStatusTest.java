package com.homecare.provider.organization.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

import com.homecare.common.error.DomainException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupabaseUserStatusTest {
    @Test void suspendAndRestoreUseAuthAdminAndPropagateFailures() throws Exception {
        var request = new AtomicReference<String>();
        var profilePatch = new AtomicReference<String>();
        var logoutCall = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/v1/admin/users", exchange -> {
            String path = exchange.getRequestURI().toString();
            String bodyStr = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/logout")) {
                logoutCall.set(path);
            } else {
                request.set(exchange.getRequestMethod() + " " + path + " " + bodyStr);
            }
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.createContext("/rest/v1/profiles", exchange -> {
            profilePatch.set(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " prefer=" + exchange.getRequestHeaders().getFirst("Prefer")
                    + " " + new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            byte[] json = "[{\"id\":\"ok\"}]".getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();
        try {
            var client = new SupabaseRoleClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-secret", "role");
            UUID user = UUID.randomUUID();
            client.updateUserStatus(user, "SUSPENDED");
            assertThat(request.get()).isEqualTo("PUT /auth/v1/admin/users/" + user
                    + " {\"ban_duration\":\"876000h\",\"app_metadata\":{\"user_status\":\"SUSPENDED\"}}");
            assertThat(profilePatch.get()).isEqualTo("PATCH /rest/v1/profiles?id=eq." + user
                    + " prefer=return=representation {\"status\":\"SUSPENDED\"}");
            assertThat(logoutCall.get()).isEqualTo("/auth/v1/admin/users/" + user + "/logout");
            client.updateUserStatus(user, "ACTIVE");
            assertThat(request.get()).endsWith("{\"ban_duration\":\"none\",\"app_metadata\":{\"user_status\":\"ACTIVE\"}}");
            assertThatThrownBy(() -> client.updateUserStatus(user, "INVALID")).isInstanceOf(DomainException.class);
        } finally { server.stop(0); }

        var listServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        listServer.createContext("/auth/v1/admin/users", exchange -> {
            byte[] json = """
                    {"users":[
                      {"id":"u1","email":"expired@example.com","banned_until":"2020-01-01T00:00:00Z","app_metadata":{"user_status":"ACTIVE"}},
                      {"id":"u2","email":"banned@example.com","banned_until":"2099-01-01T00:00:00Z","app_metadata":{}},
                      {"id":"u3","email":"meta@example.com","banned_until":null,"app_metadata":{"user_status":"SUSPENDED"}}
                    ]}""".getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        listServer.createContext("/rest/v1/user_roles", exchange -> {
            byte[] json = """
                    [
                      {"user_id":"u1","roles":{"code":"ADMIN"}},
                      {"user_id":"u2","roles":{"code":"ORGANIZATION"}}
                    ]""".getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        listServer.start();
        try {
            var client = new SupabaseRoleClient("http://127.0.0.1:" + listServer.getAddress().getPort(), "test-secret", "role");
            var users = client.listUsers();
            assertThat(users).extracting("id", "status", "role")
                    .containsExactly(
                            tuple("u1", "ACTIVE", "ADMIN"),
                            tuple("u2", "SUSPENDED", "ORGANIZATION"),
                            tuple("u3", "SUSPENDED", "CUSTOMER"));
        } finally { listServer.stop(0); }
        var unconfigured = new SupabaseRoleClient("https://placeholder.supabase.co", "placeholder-secret-key", "role");
        assertThatThrownBy(() -> unconfigured.updateUserStatus(UUID.randomUUID(), "SUSPENDED")).isInstanceOf(DomainException.class);
    }

    @Test void adminUserControllerSyncsRedisBlacklistOnSuspendAndRestore() {
        var roleClient = org.mockito.Mockito.mock(SupabaseRoleClient.class);
        var redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        var ops = (org.springframework.data.redis.core.ValueOperations<String, String>)
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(ops);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(redis);

        var controller = new com.homecare.provider.organization.controller.AdminUserController(
                roleClient, new com.homecare.common.web.servlet.ServletApiResponseFactory(), provider);
        UUID target = UUID.randomUUID();
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("tok")
                .header("alg", "none").subject("admin-1").build();
        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute(com.homecare.common.web.CorrelationIdSupport.REQUEST_ATTRIBUTE, "test-trace-id");

        controller.updateStatus(target, new com.homecare.provider.organization.controller.AdminUserController.StatusRequest("SUSPENDED", "Policy violation"), jwt, req);
        org.mockito.Mockito.verify(ops).set("auth:blacklist:user:" + target, "SUSPENDED", java.time.Duration.ofHours(24));

        controller.updateStatus(target, new com.homecare.provider.organization.controller.AdminUserController.StatusRequest("ACTIVE"), jwt, req);
        org.mockito.Mockito.verify(redis).delete("auth:blacklist:user:" + target);
    }

    @Test
    void filterSortAndPaginateUsersFindsMatchBeyondPageOneAndExcludesSelf() {
        java.time.Instant base = java.time.Instant.parse("2026-01-01T00:00:00Z");
        java.util.List<com.homecare.provider.organization.dto.OrganizationDtos.AdminUserResponse> all = new java.util.ArrayList<>();
        all.add(new com.homecare.provider.organization.dto.OrganizationDtos.AdminUserResponse(
                "admin-self", "Quản Trị Chính", "admin@homecare.vn", "ADMIN", "ACTIVE", base.plusSeconds(100)));
        for (int i = 1; i <= 25; i++) {
            all.add(new com.homecare.provider.organization.dto.OrganizationDtos.AdminUserResponse(
                    "user-" + i,
                    i == 24 ? "Nguyễn Thanh Sơn Target" : "Người Dùng " + i,
                    i == 24 ? "son.target@homecare.vn" : "user" + i + "@homecare.vn",
                    i == 24 ? "TECHNICIAN" : "CUSTOMER",
                    i == 24 ? "SUSPENDED" : "ACTIVE",
                    base.plusSeconds(i)));
        }

        // Page 1 size 10 without filter excludes self -> 25 total users, 3 pages
        var unfiltered = SupabaseRoleClient.filterSortAndPaginateUsers(
                all, 1, 10, null, null, null, "createdAt", "desc", "admin-self");
        assertThat(unfiltered.items()).hasSize(10);
        assertThat(unfiltered.pagination().totalItems()).isEqualTo(25);
        assertThat(unfiltered.pagination().totalPages()).isEqualTo(3);
        assertThat(unfiltered.items()).noneMatch(u -> "admin-self".equals(u.id()));

        // Search + role + status finds record #24 (which would be outside page 1) on page 1
        var filtered = SupabaseRoleClient.filterSortAndPaginateUsers(
                all, 1, 10, "son.target", "TECHNICIAN", "SUSPENDED", "fullName", "asc", "admin-self");
        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.items().get(0).id()).isEqualTo("user-24");
        assertThat(filtered.pagination().totalItems()).isEqualTo(1);
        assertThat(filtered.pagination().totalPages()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void suspendUserPersistsOutboxEventWithNonNullRoutingKey() {
        var roleClient = org.mockito.Mockito.mock(SupabaseRoleClient.class);
        var redisProvider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var outboxRepo = org.mockito.Mockito.mock(com.homecare.provider.organization.repository.ProviderOutboxEventRepository.class);
        var outboxProvider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(outboxProvider.getIfAvailable()).thenReturn(outboxRepo);

        var controller = new com.homecare.provider.organization.controller.AdminUserController(
                roleClient,
                new com.homecare.common.web.servlet.ServletApiResponseFactory(),
                redisProvider,
                new com.homecare.provider.config.ProviderAuthProperties(),
                null,
                null,
                outboxProvider,
                null);
        UUID target = UUID.randomUUID();
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("tok")
                .header("alg", "none").subject("admin-1").build();
        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute(com.homecare.common.web.CorrelationIdSupport.REQUEST_ATTRIBUTE, "test-trace-id");

        var response = controller.updateStatus(
                target,
                new com.homecare.provider.organization.controller.AdminUserController.StatusRequest("SUSPENDED", "Vi phạm"),
                jwt,
                req);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().id()).isEqualTo(target);
        assertThat(response.getBody().data().status()).isEqualTo("SUSPENDED");

        var captor = org.mockito.ArgumentCaptor.forClass(com.homecare.provider.organization.entity.ProviderOutboxEventEntity.class);
        org.mockito.Mockito.verify(outboxRepo).save(captor.capture());
        assertThat(captor.getValue().getRoutingKey())
                .isEqualTo(com.homecare.common.event.DomainEventContracts.USER_SUSPENDED);
    }
}
