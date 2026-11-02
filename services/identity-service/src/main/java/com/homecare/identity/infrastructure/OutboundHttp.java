package com.homecare.identity.infrastructure;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class OutboundHttp {
    @Bean
    RestClient.Builder correlatedRestClientBuilder() {
        return RestClient.builder().requestInterceptor((request, body, execution) -> {
            String id = MDC.get("correlationId");
            request.getHeaders().remove(CorrelationFilter.HEADER);
            if (id != null && id.matches("[A-Za-z0-9._-]{1,64}")) request.getHeaders().set(CorrelationFilter.HEADER, id);
            return execution.execute(request, body);
        });
    }
}
