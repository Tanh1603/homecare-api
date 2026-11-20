package com.homecare.commerce.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.homecare.commerce.service.CommerceDomainService;
import com.homecare.commerce.service.InstallationCancellationConsumer;

@Configuration
public class InstallationCancellationRabbitConfig {
    @Bean
    Queue installationCancellationQueue() {
        return QueueBuilder.durable(InstallationCancellationConsumer.QUEUE).build();
    }

    @Bean
    Binding installationCancellationBinding(
            Queue installationCancellationQueue,
            TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(installationCancellationQueue)
                .to(domainEventsExchange)
                .with("INSTALLATION_CANCELLED");
    }
}
