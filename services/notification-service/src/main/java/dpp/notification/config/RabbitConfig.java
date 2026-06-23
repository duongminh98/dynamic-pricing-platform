package dpp.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean public TopicExchange eventsExchange() { return new TopicExchange("platform.events", true, false); }

    private Queue quorumQueue(String name, String rk) {
        return QueueBuilder.durable(name).withArgument("x-queue-type","quorum")
                .withArgument("x-dead-letter-exchange","platform.events.dlx")
                .withArgument("x-dead-letter-routing-key", rk)
                .withArgument("x-delivery-limit", 3).build();
    }

    @Bean public Queue policyIssuedQueue() { return quorumQueue("policy.issued.queue","PolicyIssued"); }
    @Bean public Binding policyIssuedBinding() { return BindingBuilder.bind(policyIssuedQueue()).to(eventsExchange()).with("PolicyIssued"); }

    @Bean public Queue claimChangedQueue() { return quorumQueue("claim.status.changed.queue","ClaimStatusChanged"); }
    @Bean public Binding claimChangedBinding() { return BindingBuilder.bind(claimChangedQueue()).to(eventsExchange()).with("ClaimStatusChanged"); }

    @Bean public Queue endorsementQueue() { return quorumQueue("endorsement.applied.queue","EndorsementApplied"); }
    @Bean public Binding endorsementBinding() { return BindingBuilder.bind(endorsementQueue()).to(eventsExchange()).with("EndorsementApplied"); }

    @Bean public Queue renewedQueue() { return quorumQueue("policy.renewed.queue","PolicyRenewed"); }
    @Bean public Binding renewedBinding() { return BindingBuilder.bind(renewedQueue()).to(eventsExchange()).with("PolicyRenewed"); }

    @Bean public Queue cancelledQueue() { return quorumQueue("policy.cancelled.queue","PolicyCancelled"); }
    @Bean public Binding cancelledBinding() { return BindingBuilder.bind(cancelledQueue()).to(eventsExchange()).with("PolicyCancelled"); }
}
