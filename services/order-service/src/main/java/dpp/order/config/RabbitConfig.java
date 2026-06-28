package dpp.order.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange("platform.events", true, false);
    }

    @Bean
    public Queue invoicePaidQueue() {
        return QueueBuilder.durable("invoice.paid.queue")
                .withArgument("x-queue-type", "quorum")
                .withArgument("x-dead-letter-exchange", "platform.events.dlx")
                .withArgument("x-dead-letter-routing-key", "InvoicePaid")
                .withArgument("x-delivery-limit", 3)
                .build();
    }

    @Bean
    public Binding invoicePaidBinding() {
        return BindingBuilder.bind(invoicePaidQueue()).to(eventsExchange()).with("InvoicePaid");
    }

    @Bean
    public Queue invoiceCreatedQueue() {
        return QueueBuilder.durable("invoice.created.order.queue")
                .withArgument("x-queue-type", "quorum")
                .withArgument("x-dead-letter-exchange", "platform.events.dlx")
                .withArgument("x-dead-letter-routing-key", "InvoiceCreated")
                .withArgument("x-delivery-limit", 3)
                .build();
    }

    @Bean
    public Binding invoiceCreatedBinding() {
        return BindingBuilder.bind(invoiceCreatedQueue()).to(eventsExchange()).with("InvoiceCreated");
    }
}
