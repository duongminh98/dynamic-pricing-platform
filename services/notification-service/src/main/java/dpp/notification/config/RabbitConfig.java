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

    @Bean public Queue orderApprovedQueue() { return quorumQueue("order.approved.queue","OrderApproved"); }
    @Bean public Binding orderApprovedBinding() { return BindingBuilder.bind(orderApprovedQueue()).to(eventsExchange()).with("OrderApproved"); }

    @Bean public Queue orderSubmittedQueue() { return quorumQueue("order.submitted.queue","OrderSubmitted"); }
    @Bean public Binding orderSubmittedBinding() { return BindingBuilder.bind(orderSubmittedQueue()).to(eventsExchange()).with("OrderSubmitted"); }

    @Bean public Queue orderRejectedQueue() { return quorumQueue("order.rejected.queue","OrderRejected"); }
    @Bean public Binding orderRejectedBinding() { return BindingBuilder.bind(orderRejectedQueue()).to(eventsExchange()).with("OrderRejected"); }

    @Bean public Queue endorsementRejectedQueue() { return quorumQueue("endorsement.rejected.queue","EndorsementRejected"); }
    @Bean public Binding endorsementRejectedBinding() { return BindingBuilder.bind(endorsementRejectedQueue()).to(eventsExchange()).with("EndorsementRejected"); }

    @Bean public Queue endorsementPendingPaymentQueue() { return quorumQueue("endorsement.pending.payment.queue","EndorsementPendingPayment"); }
    @Bean public Binding endorsementPendingPaymentBinding() { return BindingBuilder.bind(endorsementPendingPaymentQueue()).to(eventsExchange()).with("EndorsementPendingPayment"); }

    @Bean public Queue endorsementOverdueQueue() { return quorumQueue("endorsement.overdue.queue","EndorsementOverdue"); }
    @Bean public Binding endorsementOverdueBinding() { return BindingBuilder.bind(endorsementOverdueQueue()).to(eventsExchange()).with("EndorsementOverdue"); }

    @Bean public Queue creditIssuedQueue() { return quorumQueue("endorsement.credit.issued.queue","EndorsementCreditIssued"); }
    @Bean public Binding creditIssuedBinding() { return BindingBuilder.bind(creditIssuedQueue()).to(eventsExchange()).with("EndorsementCreditIssued"); }

    @Bean public Queue refundRequestedQueue() { return quorumQueue("refund.requested.queue","RefundRequested"); }
    @Bean public Binding refundRequestedBinding() { return BindingBuilder.bind(refundRequestedQueue()).to(eventsExchange()).with("RefundRequested"); }

    @Bean public Queue refundCompletedQueue() { return quorumQueue("refund.completed.queue","RefundCompleted"); }
    @Bean public Binding refundCompletedBinding() { return BindingBuilder.bind(refundCompletedQueue()).to(eventsExchange()).with("RefundCompleted"); }
}
