package com.homecare.provider.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.BaseApiExceptionHandler;
import com.homecare.common.web.servlet.GatewayOriginFilter;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.common.web.servlet.ServletCorrelationIdFilter;
import com.homecare.grpc.interceptor.WorkloadAuthServerInterceptor;
import com.homecare.provider.organization.service.SupabaseRoleSyncConsumer;
import com.homecare.provider.service.ProviderBookingConsumer;
import com.homecare.provider.service.ScheduleAllocationConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

@Configuration
@EnableMethodSecurity
@EnableScheduling
public class ProviderSecurityAndGrpcConfig {

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
    public TopicExchange organizationDomainExchange() {
        return new TopicExchange(DomainEventContracts.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange providerEventsDlx() {
        return new DirectExchange(DomainEventContracts.DLX, true, false);
    }

    @Bean
    public Queue supabaseRoleSyncQueue() {
        return QueueBuilder.durable(SupabaseRoleSyncConsumer.QUEUE)
                .withArgument("x-dead-letter-exchange", DomainEventContracts.DLX)
                .withArgument("x-dead-letter-routing-key", DomainEventContracts.dlqName(SupabaseRoleSyncConsumer.QUEUE))
                .build();
    }

    @Bean
    public Queue supabaseRoleSyncDlq() {
        return QueueBuilder.durable(DomainEventContracts.dlqName(SupabaseRoleSyncConsumer.QUEUE)).build();
    }

    @Bean
    public Binding bindSupabaseRoleSyncDlq(Queue supabaseRoleSyncDlq, DirectExchange providerEventsDlx) {
        return BindingBuilder.bind(supabaseRoleSyncDlq)
                .to(providerEventsDlx)
                .with(DomainEventContracts.dlqName(SupabaseRoleSyncConsumer.QUEUE));
    }

    @Bean
    public Binding bindOrgApprovedForRoleSync(Queue supabaseRoleSyncQueue, TopicExchange organizationDomainExchange) {
        return BindingBuilder.bind(supabaseRoleSyncQueue)
                .to(organizationDomainExchange)
                .with(DomainEventContracts.ORG_APPROVED);
    }

    @Bean
    public Binding bindOrgSuspendedForRoleSync(Queue supabaseRoleSyncQueue, TopicExchange organizationDomainExchange) {
        return BindingBuilder.bind(supabaseRoleSyncQueue)
                .to(organizationDomainExchange)
                .with(DomainEventContracts.ORG_SUSPENDED);
    }

    @Bean
    public Binding bindOrgMemberChangedForRoleSync(Queue supabaseRoleSyncQueue, TopicExchange organizationDomainExchange) {
        return BindingBuilder.bind(supabaseRoleSyncQueue)
                .to(organizationDomainExchange)
                .with(DomainEventContracts.ORG_MEMBER_CHANGED);
    }

    @Bean
    public Queue providerBookingQueue() {
        return QueueBuilder.durable(ProviderBookingConsumer.QUEUE)
                .withArgument("x-dead-letter-exchange", DomainEventContracts.DLX)
                .withArgument("x-dead-letter-routing-key", DomainEventContracts.dlqName(ProviderBookingConsumer.QUEUE))
                .build();
    }

    @Bean
    public Queue providerBookingDlq() {
        return QueueBuilder.durable(DomainEventContracts.dlqName(ProviderBookingConsumer.QUEUE)).build();
    }

    @Bean
    public Binding bindProviderBookingDlq(Queue providerBookingDlq, DirectExchange providerEventsDlx) {
        return BindingBuilder.bind(providerBookingDlq)
                .to(providerEventsDlx)
                .with(DomainEventContracts.dlqName(ProviderBookingConsumer.QUEUE));
    }

    @Bean
    public Binding providerBookingCompleted() {
        return new Binding(
                ProviderBookingConsumer.QUEUE,
                Binding.DestinationType.QUEUE,
                DomainEventContracts.EXCHANGE,
                DomainEventContracts.BOOKING_COMPLETED,
                null);
    }

    @Bean
    public Binding providerBookingCancelled() {
        return new Binding(
                ProviderBookingConsumer.QUEUE,
                Binding.DestinationType.QUEUE,
                DomainEventContracts.EXCHANGE,
                DomainEventContracts.BOOKING_CANCELLED,
                null);
    }

    @Bean
    public Binding providerBookingAllocationReleaseRetry() {
        return new Binding(
                ProviderBookingConsumer.QUEUE,
                Binding.DestinationType.QUEUE,
                DomainEventContracts.EXCHANGE,
                "booking.allocation.release.retry.v1",
                null);
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
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/technicians/me",
                                "/api/v1/technicians/me/**",
                                "/api/v1/technicians/*/schedules",
                                "/api/v1/technicians/*/calendar",
                                "/api/v1/technicians/*/schedule-audits",
                                "/api/v1/technicians/*/weekly-schedules",
                                "/api/v1/technicians/*/weekly-schedules/**",
                                "/api/v1/technicians/*/schedule-exceptions",
                                "/api/v1/technicians/*/schedule-exceptions/**")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/services", "/api/v1/services/**",
                                "/api/v1/providers", "/api/v1/providers/**",
                                "/api/v1/technicians", "/api/v1/technicians/**")
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
    public static class ProviderExceptionHandler extends BaseApiExceptionHandler {
        public ProviderExceptionHandler(ServletApiResponseFactory responseFactory) {
            super(responseFactory);
        }

        @ExceptionHandler(ScheduleAllocationConflictException.class)
        public ResponseEntity<ApiResponse<Map<String, Object>>> handleScheduleConflict(
                ScheduleAllocationConflictException exception,
                HttpServletRequest request) {
            String traceId = responseFactory.error(
                    HttpStatus.CONFLICT,
                    "SCHEDULE_ALLOCATION_CONFLICT",
                    exception.getMessage(),
                    null,
                    request).traceId();
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(new ApiResponse<>(
                            false,
                            409,
                            "SCHEDULE_ALLOCATION_CONFLICT",
                            exception.getMessage(),
                            Map.of("conflicts", exception.conflicts()),
                            null,
                            Instant.now(),
                            traceId));
        }
    }
}
