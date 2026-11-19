package com.homecare.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public class GatewayAuthProperties {
    private boolean blacklistFailClosed = false;

    public boolean isBlacklistFailClosed() {
        return blacklistFailClosed;
    }

    public void setBlacklistFailClosed(boolean blacklistFailClosed) {
        this.blacklistFailClosed = blacklistFailClosed;
    }
}
