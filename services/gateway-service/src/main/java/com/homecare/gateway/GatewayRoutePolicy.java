package com.homecare.gateway;

import java.util.Set;

/** Exact public route/method policy shared by the boundary filter and security chain. */
public final class GatewayRoutePolicy {
    private GatewayRoutePolicy() {}

    public static boolean guest(String method, String path) {
        return method.equals("GET") && (Set.of("/api/v1/services", "/api/v1/providers", "/api/v1/technicians", "/api/v1/products").contains(path)
                || path.matches("/api/v1/(providers|products)/[A-Za-z0-9_-]+"));
    }

    public static boolean self(String method, String path) {
        return (Set.of("GET", "PATCH").contains(method) && path.equals("/api/v1/users/me"))
                || (method.equals("POST") && path.equals("/api/v1/users/me/bootstrap"));
    }
}
