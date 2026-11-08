package com.homecare.notification.config;

import java.time.Clock;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homecare.common.security.KeycloakJwtConverterSupport;
import com.homecare.common.web.servlet.BaseApiExceptionHandler;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.common.web.servlet.ServletCorrelationIdFilter;
import com.homecare.notification.service.DomainEventConsumer;

@Configuration
@EnableMethodSecurity
public class NotificationSecurityAndRabbitConfig {

    @Bean
    public TopicExchange domainEventsExchange() {
        return new TopicExchange(DomainEventConsumer.EXCHANGE, true, false);
    }

    @Bean
    public Queue notificationInboxQueue() {
        return new Queue(DomainEventConsumer.QUEUE, true);
    }

    @Bean
    public Binding bookingEventsBinding(Queue notificationInboxQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(notificationInboxQueue).to(domainEventsExchange).with("booking.#");
    }

    @Bean
    public Binding orderEventsBinding(Queue notificationInboxQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(notificationInboxQueue).to(domainEventsExchange).with("order.#");
    }

    @Bean
    public Binding maintenanceEventsBinding(Queue notificationInboxQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(notificationInboxQueue).to(domainEventsExchange).with("maintenance.#");
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
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ServletCorrelationIdFilter correlationIdFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                KeycloakJwtConverterSupport.createJwtAuthenticationConverter())))
                .addFilterBefore(correlationIdFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    @RestControllerAdvice
    public static class NotificationExceptionHandler extends BaseApiExceptionHandler {
        public NotificationExceptionHandler(ServletApiResponseFactory responseFactory) {
            super(responseFactory);
        }
    }
}
