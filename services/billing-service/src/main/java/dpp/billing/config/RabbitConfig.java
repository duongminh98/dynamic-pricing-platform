package dpp.billing.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean public TopicExchange eventsExchange() { return new TopicExchange("platform.events", true, false); }
    @Bean public Queue endorsementQueue() {
        return QueueBuilder.durable("endorsement.applied.queue").withArgument("x-queue-type","quorum").withArgument("x-dead-letter-exchange","platform.events.dlx").withArgument("x-delivery-limit",3).build(); }
    @Bean public Binding endorsementBinding() { return BindingBuilder.bind(endorsementQueue()).to(eventsExchange()).with("EndorsementApplied"); }
    @Bean public Queue cancellationQueue() {
        return QueueBuilder.durable("policy.cancelled.queue").withArgument("x-queue-type","quorum").withArgument("x-dead-letter-exchange","platform.events.dlx").withArgument("x-delivery-limit",3).build(); }
    @Bean public Binding cancellationBinding() { return BindingBuilder.bind(cancellationQueue()).to(eventsExchange()).with("PolicyCancelled"); }
}
