package com.homecare.household.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homecare.common.config.HomecareSecurityProperties;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.servlet.BaseApiExceptionHandler;
import com.homecare.common.web.servlet.GatewayOriginFilter;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.common.web.servlet.ServletCorrelationIdFilter;
import com.homecare.grpc.interceptor.WorkloadAuthServerInterceptor;
import com.homecare.household.service.HouseholdEventConsumer;
import com.homecare.household.service.MaintenanceReminderWorker;

import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

@Configuration
@EnableMethodSecurity
@EnableScheduling
public class HouseholdSecurityConfig {

    @Bean
    public TopicExchange domainEventsExchange() {
        return new TopicExchange(MaintenanceReminderWorker.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange householdEventsDlx() {
        return new DirectExchange(DomainEventContracts.DLX, true, false);
    }

    @Bean
    public Queue householdEventsQueue() {
        return QueueBuilder.durable(HouseholdEventConsumer.QUEUE)
                .withArgument("x-dead-letter-exchange", DomainEventContracts.DLX)
                .withArgument("x-dead-letter-routing-key", DomainEventContracts.dlqName(HouseholdEventConsumer.QUEUE))
                .build();
    }

    @Bean
    public Queue householdEventsDlq() {
        return QueueBuilder.durable(DomainEventContracts.dlqName(HouseholdEventConsumer.QUEUE)).build();
    }

    @Bean
    public Binding bindHouseholdEventsDlq(Queue householdEventsDlq, DirectExchange householdEventsDlx) {
        return BindingBuilder.bind(householdEventsDlq)
                .to(householdEventsDlx)
                .with(DomainEventContracts.dlqName(HouseholdEventConsumer.QUEUE));
    }

    @Bean
    public Binding bindOrderLineDelivered(Queue householdEventsQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(householdEventsQueue)
                .to(domainEventsExchange)
                .with(DomainEventContracts.ORDER_LINE_DELIVERED);
    }

    @Bean
    public Binding bindBookingCompleted(Queue householdEventsQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(householdEventsQueue)
                .to(domainEventsExchange)
                .with(DomainEventContracts.BOOKING_COMPLETED);
    }

    @Bean
    public Binding bindInstallationCompleted(Queue householdEventsQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(householdEventsQueue)
                .to(domainEventsExchange)
                .with(DomainEventContracts.INSTALLATION_COMPLETED);
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
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                SupabaseJwtConverterSupport.createJwtAuthenticationConverter())))
                .addFilterBefore(correlationIdFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(gatewayOriginFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    @RestControllerAdvice
    public static class HouseholdExceptionHandler extends BaseApiExceptionHandler {
        public HouseholdExceptionHandler(ServletApiResponseFactory responseFactory) {
            super(responseFactory);
        }
    }
}
