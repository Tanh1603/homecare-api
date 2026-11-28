package com.homecare.provider.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.homecare.common.event.DomainEventContracts;
import com.homecare.provider.service.ProviderReviewRatingConsumer;

@Configuration
public class ProviderRabbitConfig {

    @Bean
    public Queue providerReviewRatingQueue() {
        return QueueBuilder.durable(ProviderReviewRatingConsumer.QUEUE)
                .withArgument("x-dead-letter-exchange", DomainEventContracts.DLX)
                .withArgument("x-dead-letter-routing-key", DomainEventContracts.dlqName(ProviderReviewRatingConsumer.QUEUE))
                .build();
    }

    @Bean
    public Queue providerReviewRatingDlq() {
        return QueueBuilder.durable(DomainEventContracts.dlqName(ProviderReviewRatingConsumer.QUEUE)).build();
    }

    @Bean
    public Binding bindProviderReviewRatingDlq(Queue providerReviewRatingDlq, DirectExchange providerEventsDlx) {
        return BindingBuilder.bind(providerReviewRatingDlq)
                .to(providerEventsDlx)
                .with(DomainEventContracts.dlqName(ProviderReviewRatingConsumer.QUEUE));
    }

    @Bean
    public Binding bindProviderReviewRatingEvents(Queue providerReviewRatingQueue, TopicExchange organizationDomainExchange) {
        return BindingBuilder.bind(providerReviewRatingQueue)
                .to(organizationDomainExchange)
                .with(DomainEventContracts.REVIEW_RATING_UPDATED);
    }
}
