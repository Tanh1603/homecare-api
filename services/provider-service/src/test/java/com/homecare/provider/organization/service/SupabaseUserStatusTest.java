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
            assertThat(request.get()).isEqualTo("PUT /auth/v1/admin/users/" + user + " {\"ban_duration\":\"876000h\"}");
            client.updateUserStatus(user, "ACTIVE");
            assertThat(request.get()).endsWith("{\"ban_duration\":\"none\"}");
            assertThatThrownBy(() -> client.updateUserStatus(user, "INVALID")).isInstanceOf(DomainException.class);
        } finally { server.stop(0); }
        var unconfigured = new SupabaseRoleClient("https://placeholder.supabase.co", "placeholder-secret-key", "role");
        assertThatThrownBy(() -> unconfigured.updateUserStatus(UUID.randomUUID(), "SUSPENDED")).isInstanceOf(DomainException.class);
    }
}
