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
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/v1/admin/users", exchange -> {
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            var client = new SupabaseRoleClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-secret", "role");
            UUID user = UUID.randomUUID();
            client.updateUserStatus(user, "SUSPENDED");
            assertThat(request.get()).isEqualTo("PUT /auth/v1/admin/users/" + user
                    + " {\"ban_duration\":\"876000h\",\"app_metadata\":{\"user_status\":\"SUSPENDED\"}}");
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
        listServer.start();
        try {
            var client = new SupabaseRoleClient("http://127.0.0.1:" + listServer.getAddress().getPort(), "test-secret", "role");
            var users = client.listUsers();
            assertThat(users).extracting("id", "status")
                    .containsExactly(tuple("u1", "ACTIVE"), tuple("u2", "SUSPENDED"), tuple("u3", "SUSPENDED"));
        } finally { listServer.stop(0); }
        var unconfigured = new SupabaseRoleClient("https://placeholder.supabase.co", "placeholder-secret-key", "role");
        assertThatThrownBy(() -> unconfigured.updateUserStatus(UUID.randomUUID(), "SUSPENDED")).isInstanceOf(DomainException.class);
    }
}
