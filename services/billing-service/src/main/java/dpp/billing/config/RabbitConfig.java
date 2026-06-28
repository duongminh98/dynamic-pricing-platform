package dpp.billing.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean public TopicExchange eventsExchange() { return new TopicExchange("platform.events", true, false); }

    private Queue billingQueue(String name, String rk) {
        return QueueBuilder.durable(name)
                .withArgument("x-queue-type", "quorum")
                .withArgument("x-dead-letter-exchange", "platform.events.dlx")
                .withArgument("x-dead-letter-routing-key", rk)
                .withArgument("x-delivery-limit", 3)
                .build();
    }

    @Bean public Queue endorsementQueue() { return billingQueue("endorsement.applied.billing.queue", "EndorsementApplied"); }
    @Bean public Binding endorsementBinding() { return BindingBuilder.bind(endorsementQueue()).to(eventsExchange()).with("EndorsementApplied"); }

    @Bean public Queue cancellationQueue() { return billingQueue("policy.cancelled.billing.queue", "PolicyCancelled"); }
    @Bean public Binding cancellationBinding() { return BindingBuilder.bind(cancellationQueue()).to(eventsExchange()).with("PolicyCancelled"); }

    @Bean public Queue creditIssuedQueue() { return billingQueue("endorsement.credit.issued.billing.queue", "EndorsementCreditIssued"); }
    @Bean public Binding creditIssuedBinding() { return BindingBuilder.bind(creditIssuedQueue()).to(eventsExchange()).with("EndorsementCreditIssued"); }

    @Bean public Queue renewalQueue() { return billingQueue("policy.renewed.billing.queue", "PolicyRenewed"); }
    @Bean public Binding renewalBinding() { return BindingBuilder.bind(renewalQueue()).to(eventsExchange()).with("PolicyRenewed"); }

    @Bean public Queue orderApprovedQueue() { return billingQueue("order.approved.billing.queue", "OrderApproved"); }
    @Bean public Binding orderApprovedBinding() { return BindingBuilder.bind(orderApprovedQueue()).to(eventsExchange()).with("OrderApproved"); }

    @Bean public Queue endorsementPendingPaymentQueue() { return billingQueue("endorsement.pending.payment.billing.queue", "EndorsementPendingPayment"); }
    @Bean public Binding endorsementPendingPaymentBinding() { return BindingBuilder.bind(endorsementPendingPaymentQueue()).to(eventsExchange()).with("EndorsementPendingPayment"); }
}
