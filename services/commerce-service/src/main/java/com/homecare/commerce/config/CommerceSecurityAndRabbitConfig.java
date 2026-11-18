package com.homecare.commerce.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homecare.commerce.service.CommerceDomainService;
import com.homecare.commerce.service.CommerceOrganizationConsumer;
import com.homecare.common.config.HomecareSecurityProperties;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.servlet.BaseApiExceptionHandler;
import com.homecare.common.web.servlet.GatewayOriginFilter;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.common.web.servlet.ServletCorrelationIdFilter;
import com.homecare.grpc.interceptor.WorkloadAuthServerInterceptor;
import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

@Configuration
@EnableMethodSecurity
public class CommerceSecurityAndRabbitConfig {

    @Bean
    public TopicExchange domainEventsExchange() {
        return new TopicExchange(CommerceDomainService.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange commerceEventsDlx() {
        return new DirectExchange(DomainEventContracts.DLX, true, false);
    }

    @Bean
    public Queue commerceOrganizationQueue() {
        return QueueBuilder.durable(CommerceOrganizationConsumer.QUEUE)
                .withArgument("x-dead-letter-exchange", DomainEventContracts.DLX)
                .withArgument("x-dead-letter-routing-key", DomainEventContracts.dlqName(CommerceOrganizationConsumer.QUEUE))
                .build();
    }

    @Bean
    public Queue commerceOrganizationDlq() {
        return QueueBuilder.durable(DomainEventContracts.dlqName(CommerceOrganizationConsumer.QUEUE)).build();
    }

    @Bean
    public Binding bindCommerceOrgDlq(Queue commerceOrganizationDlq, DirectExchange commerceEventsDlx) {
        return BindingBuilder.bind(commerceOrganizationDlq)
                .to(commerceEventsDlx)
                .with(DomainEventContracts.dlqName(CommerceOrganizationConsumer.QUEUE));
    }

    @Bean
    public Binding bindCommerceOrgApproved(Queue commerceOrganizationQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(commerceOrganizationQueue)
                .to(domainEventsExchange)
                .with(DomainEventContracts.ORG_APPROVED);
    }

    @Bean
    public Binding bindCommerceOrgSuspended(Queue commerceOrganizationQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(commerceOrganizationQueue)
                .to(domainEventsExchange)
                .with(DomainEventContracts.ORG_SUSPENDED);
    }

    @Bean
    public Binding bindCommerceOrgUpdated(Queue commerceOrganizationQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(commerceOrganizationQueue)
                .to(domainEventsExchange)
                .with(DomainEventContracts.ORG_UPDATED);
    }

    @Bean
    public IdempotencyService idempotencyService() {
        return new IdempotencyService();
    }

    @Bean
    public ServletCorrelationIdFilter servletCorrelationIdFilter() {
        return new ServletCorrelationIdFilter();
    }

    @Bean
    public ServletApiResponseFactory servletApiResponseFactory() {
        return new ServletApiResponseFactory();
    }

    @Bean
    public GatewayOriginFilter gatewayOriginFilter(HomecareSecurityProperties securityProperties) {
        return new GatewayOriginFilter(
                securityProperties.getSecurity().isEnforceGateway(),
                securityProperties.getSecurity().getInternalGatewaySecret(),
                securityProperties.getInternal().getWorkloadToken());
    }

    @Bean
    @GrpcGlobalServerInterceptor
    public ServerInterceptor internalWorkloadGrpcInterceptor(HomecareSecurityProperties securityProperties) {
        return new WorkloadAuthServerInterceptor(securityProperties.getInternal().getWorkloadToken());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ServletCorrelationIdFilter correlationIdFilter,
            GatewayOriginFilter gatewayOriginFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/**",
                                "/api/v1/payments/vnpay/ipn",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                SupabaseJwtConverterSupport.createJwtAuthenticationConverter())))
                .addFilterBefore(correlationIdFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(gatewayOriginFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    @RestControllerAdvice
    public static class CommerceExceptionHandler extends BaseApiExceptionHandler {
        public CommerceExceptionHandler(ServletApiResponseFactory responseFactory) {
            super(responseFactory);
        }
    }
}
