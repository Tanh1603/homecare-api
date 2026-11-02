package com.homecare.gateway;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** Outbound transport only; route ownership and authentication stay separate. */
@Configuration(proxyBeanMethods = false)
public class GatewayHttpClientConfiguration {
    @Bean
    ClientHttpRequestFactory gatewayRequestFactory(SslBundles bundles,
            @Value("${homecare.gateway.connect-timeout}") Duration connect,
            @Value("${homecare.gateway.response-timeout}") Duration response) {
        var client = HttpClient.newBuilder().sslContext(bundles.getBundle("workload").createSslContext())
                .connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(response);
        return factory;
    }
}
