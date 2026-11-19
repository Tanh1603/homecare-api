package com.homecare.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "homecare")
public class HomecareSecurityProperties {

    private Internal internal = new Internal();
    private Security security = new Security();

    public Internal getInternal() {
        return internal;
    }

    public void setInternal(Internal internal) {
        this.internal = internal;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public static class Internal {
        private String workloadToken = "change_me_internal_workload_token";

        public String getWorkloadToken() {
            return workloadToken;
        }

        public void setWorkloadToken(String workloadToken) {
            this.workloadToken = workloadToken;
        }
    }

    public static class Security {
        private boolean enforceGateway = false;
        private String internalGatewaySecret = "homecare-internal-secret-2026";

        public boolean isEnforceGateway() {
            return enforceGateway;
        }

        public void setEnforceGateway(boolean enforceGateway) {
            this.enforceGateway = enforceGateway;
        }

        public String getInternalGatewaySecret() {
            return internalGatewaySecret;
        }

        public void setInternalGatewaySecret(String internalGatewaySecret) {
            this.internalGatewaySecret = internalGatewaySecret;
        }
    }
}
