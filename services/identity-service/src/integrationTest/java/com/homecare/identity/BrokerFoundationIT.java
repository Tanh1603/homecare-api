package com.homecare.identity;

import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BrokerFoundationIT {
    @Test void brokerConnectionAndTransientRoundTrip() throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost(DatabaseFoundationIT.env("RABBITMQ_HOST"));
        factory.setPort(Integer.parseInt(DatabaseFoundationIT.env("RABBITMQ_PORT")));
        factory.setUsername(DatabaseFoundationIT.env("RABBITMQ_USERNAME"));
        factory.setPassword(DatabaseFoundationIT.env("RABBITMQ_PASSWORD"));
        factory.setVirtualHost(DatabaseFoundationIT.env("RABBITMQ_VHOST"));
        factory.setConnectionTimeout(3000);
        try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
            // Broker-generated, exclusive auto-delete fixture queue. No business topology/payload.
            String queue = channel.queueDeclare().getQueue();
            channel.confirmSelect();
            channel.basicPublish("", queue, null, "synthetic-probe".getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(3000);
            var response = channel.basicGet(queue, false);
            assertThat(response).isNotNull();
            assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("synthetic-probe");
            channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
        }
    }
}
