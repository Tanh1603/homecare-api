package com.homecare.review.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.homecare.common.event.DomainEventContracts;

@Configuration
public class ReviewRabbitConfig {

    @Bean
    public TopicExchange domainEventsExchange() {
        return new TopicExchange(DomainEventContracts.EXCHANGE, true, false);
    }
}
