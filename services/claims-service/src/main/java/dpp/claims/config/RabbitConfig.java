package dpp.claims.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange("platform.events", true, false);
    }

    private Queue quorumQueue(String name, String routingKey) {
        return QueueBuilder.durable(name)
                .withArgument("x-queue-type", "quorum")
                .withArgument("x-dead-letter-exchange", "platform.events.dlx")
                .withArgument("x-dead-letter-routing-key", routingKey)
                .withArgument("x-delivery-limit", 3)
                .build();
    }

    @Bean public Queue claimsPolicyIssuedQueue() { return quorumQueue("claims.policy.issued.queue", "claims.policy.issued.dlq"); }
    @Bean public Binding claimsPolicyIssuedBinding() { return BindingBuilder.bind(claimsPolicyIssuedQueue()).to(eventsExchange()).with("PolicyIssued"); }

    @Bean public Queue claimsPolicyRenewedQueue() { return quorumQueue("claims.policy.renewed.queue", "claims.policy.renewed.dlq"); }
    @Bean public Binding claimsPolicyRenewedBinding() { return BindingBuilder.bind(claimsPolicyRenewedQueue()).to(eventsExchange()).with("PolicyRenewed"); }

    @Bean public Queue claimsPolicyCancelledQueue() { return quorumQueue("claims.policy.cancelled.queue", "claims.policy.cancelled.dlq"); }
    @Bean public Binding claimsPolicyCancelledBinding() { return BindingBuilder.bind(claimsPolicyCancelledQueue()).to(eventsExchange()).with("PolicyCancelled"); }

    @Bean public Queue claimsEndorsementAppliedQueue() { return quorumQueue("claims.endorsement.applied.queue", "claims.endorsement.applied.dlq"); }
    @Bean public Binding claimsEndorsementAppliedBinding() { return BindingBuilder.bind(claimsEndorsementAppliedQueue()).to(eventsExchange()).with("EndorsementApplied"); }
}
