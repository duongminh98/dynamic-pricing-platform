package dpp.notification.consumer;

import dpp.notification.service.NotificationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class NotificationEventListeners {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public NotificationEventListeners(NotificationService notificationService) {
        this.notificationService = notificationService;
        this.objectMapper = new ObjectMapper();
    }

    @RabbitListener(queues = "policy.issued.queue")
    public void onPolicyIssued(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "PolicyIssued", this::buildPolicyIssuedMessage);
    }

    @RabbitListener(queues = "claim.status.changed.queue")
    public void onClaimChanged(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "ClaimStatusChanged", this::buildClaimChangedMessage);
    }

    @RabbitListener(queues = "endorsement.applied.queue")
    public void onEndorsement(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "EndorsementApplied", this::buildEndorsementAppliedMessage);
    }

    @RabbitListener(queues = "endorsement.rejected.queue")
    public void onEndorsementRejected(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "EndorsementRejected", this::buildEndorsementRejectedMessage);
    }

    @RabbitListener(queues = "policy.renewed.queue")
    public void onRenewed(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "PolicyRenewed", this::buildPolicyRenewedMessage);
    }

    @RabbitListener(queues = "policy.cancelled.queue")
    public void onCancelled(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "PolicyCancelled", this::buildPolicyCancelledMessage);
    }

    @RabbitListener(queues = "order.submitted.queue")
    public void onOrderSubmitted(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "OrderSubmitted", this::buildOrderSubmittedMessage);
    }

    @RabbitListener(queues = "order.approved.queue")
    public void onOrderApproved(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "OrderApproved", this::buildOrderApprovedMessage);
    }

    @RabbitListener(queues = "order.rejected.queue")
    public void onOrderRejected(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "OrderRejected", this::buildOrderRejectedMessage);
    }

    @RabbitListener(queues = "endorsement.pending.payment.queue")
    public void onEndorsementPendingPayment(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "EndorsementPendingPayment", this::buildEndorsementPendingPaymentMessage);
    }

    @RabbitListener(queues = "endorsement.overdue.queue")
    public void onEndorsementOverdue(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "EndorsementOverdue", this::buildEndorsementOverdueMessage);
    }

    @RabbitListener(queues = "endorsement.credit.issued.queue")
    public void onCreditIssued(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "EndorsementCreditIssued", this::buildCreditIssuedMessage);
    }

    @RabbitListener(queues = "refund.requested.queue")
    public void onRefundRequested(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "RefundRequested", this::buildRefundRequestedMessage);
    }

    @RabbitListener(queues = "refund.completed.queue")
    public void onRefundCompleted(@Payload String msg, @Header(name = "X-Event-Id", required = false) String eventId) {
        handle(msg, eventId, "RefundCompleted", this::buildRefundCompletedMessage);
    }

    private void handle(String msg, String eventId, String type, MessageBuilder builder) {
        try {
            JsonNode n = objectMapper.readTree(msg);
            UUID policyId = n.has("policy_id") && !n.get("policy_id").isNull() ? UUID.fromString(n.get("policy_id").asText()) : null;
            UUID customerId = n.has("customer_id") && !n.get("customer_id").isNull() ? UUID.fromString(n.get("customer_id").asText()) : null;
            String message = builder.build(n);
            notificationService.createNotification(eventId, customerId, policyId, type, message);
        } catch (Exception e) {
            throw new RuntimeException("Notification processing failed", e);
        }
    }

    private String buildPolicyIssuedMessage(JsonNode n) {
        String policyId = text(n, "policy_id");
        String productId = text(n, "product_id");
        String premium = text(n, "final_premium_vnd");
        String termDays = text(n, "term_days");
        StringBuilder sb = new StringBuilder();
        sb.append("Your insurance policy has been issued.");
        if (policyId != null) sb.append(" Policy ID: ").append(policyId).append(".");
        if (productId != null) sb.append(" Product: ").append(productId).append(".");
        if (premium != null) sb.append(" Premium: ").append(formatVnd(premium)).append(" VND.");
        if (termDays != null) sb.append(" Term: ").append(termDays).append(" days.");
        return sb.toString();
    }

    private String buildClaimChangedMessage(JsonNode n) {
        String claimId = text(n, "claim_id");
        String status = text(n, "status");
        String paidAmount = text(n, "paid_amount_vnd");
        StringBuilder sb = new StringBuilder();
        sb.append("Your claim status has changed.");
        if (claimId != null) sb.append(" Claim ID: ").append(claimId).append(".");
        if (status != null) sb.append(" New status: ").append(status).append(".");
        if (paidAmount != null) sb.append(" Paid amount: ").append(formatVnd(paidAmount)).append(" VND.");
        return sb.toString();
    }

    private String buildEndorsementAppliedMessage(JsonNode n) {
        String policyId = text(n, "policy_id");
        String premiumOld = text(n, "premium_old");
        String premiumNew = text(n, "premium_new");
        String remainingDays = text(n, "remaining_days");
        StringBuilder sb = new StringBuilder();
        sb.append("Your policy endorsement has been applied.");
        if (policyId != null) sb.append(" Policy ID: ").append(policyId).append(".");
        if (premiumOld != null && premiumNew != null) {
            sb.append(" Premium changed from ").append(formatVnd(premiumOld))
              .append(" VND to ").append(formatVnd(premiumNew)).append(" VND.");
        }
        if (remainingDays != null) sb.append(" Remaining term: ").append(remainingDays).append(" days.");
        return sb.toString();
    }

    private String buildEndorsementRejectedMessage(JsonNode n) {
        String policyId = text(n, "policy_id");
        String reason = text(n, "review_reason");
        StringBuilder sb = new StringBuilder();
        sb.append("Your endorsement request has been rejected.");
        if (policyId != null) sb.append(" Policy ID: ").append(policyId).append(".");
        if (reason != null && !reason.isEmpty()) sb.append(" Reason: ").append(reason).append(".");
        return sb.toString();
    }

    private String buildPolicyRenewedMessage(JsonNode n) {
        String policyId = text(n, "policy_id");
        String renewalNumber = text(n, "renewal_number");
        String premiumVnd = text(n, "final_premium_vnd");
        StringBuilder sb = new StringBuilder();
        sb.append("Your insurance policy has been renewed.");
        if (policyId != null) sb.append(" Policy ID: ").append(policyId).append(".");
        if (renewalNumber != null) sb.append(" Renewal #: ").append(renewalNumber).append(".");
        if (premiumVnd != null) sb.append(" Premium: ").append(formatVnd(premiumVnd)).append(" VND.");
        return sb.toString();
    }

    private String buildPolicyCancelledMessage(JsonNode n) {
        String policyId = text(n, "policy_id");
        String cancelDate = text(n, "cancel_date");
        String remainingDays = text(n, "remaining_days");
        String termDays = text(n, "term_days");
        StringBuilder sb = new StringBuilder();
        sb.append("Your insurance policy has been cancelled.");
        if (policyId != null) sb.append(" Policy ID: ").append(policyId).append(".");
        if (cancelDate != null) sb.append(" Cancel date: ").append(cancelDate).append(".");
        if (remainingDays != null && termDays != null) {
            sb.append(" Unused term: ").append(remainingDays).append("/").append(termDays).append(" days.");
        }
        return sb.toString();
    }

    private String buildOrderSubmittedMessage(JsonNode n) {
        String orderId = text(n, "order_id");
        String productId = text(n, "product_id");
        String line = text(n, "line");
        String premium = text(n, "final_premium_vnd");
        String status = text(n, "status");
        String createdAt = text(n, "created_at");
        StringBuilder sb = new StringBuilder();
        sb.append("Your order has been submitted for review.");
        if (orderId != null) sb.append(" Order ID: ").append(orderId).append(".");
        if (productId != null) sb.append(" Product: ").append(productId);
        if (line != null) sb.append(" (").append(line).append(")");
        sb.append(".");
        if (premium != null) sb.append(" Premium: ").append(formatVnd(premium)).append(" VND.");
        if (status != null) sb.append(" Status: ").append(status.toLowerCase().replace("_", " ")).append(".");
        if (createdAt != null) sb.append(" Submitted at: ").append(createdAt).append(".");
        return sb.toString();
    }

    private String buildOrderApprovedMessage(JsonNode n) {
        String orderId = text(n, "order_id");
        String productId = text(n, "product_id");
        String line = text(n, "line");
        String premium = text(n, "final_premium_vnd");
        String invoiceId = text(n, "invoice_id");
        StringBuilder sb = new StringBuilder();
        sb.append("Your order has been approved and is awaiting payment.");
        if (orderId != null) sb.append(" Order ID: ").append(orderId).append(".");
        if (invoiceId != null && !invoiceId.isEmpty()) sb.append(" Invoice ID: ").append(invoiceId).append(".");
        if (productId != null) sb.append(" Product: ").append(productId);
        if (line != null) sb.append(" (").append(line).append(")");
        sb.append(".");
        if (premium != null) sb.append(" Amount due: ").append(formatVnd(premium)).append(" VND.");
        sb.append(" Please pay via VNPAY to issue your policy.");
        return sb.toString();
    }

    private String buildOrderRejectedMessage(JsonNode n) {
        String orderId = text(n, "order_id");
        String productId = text(n, "product_id");
        String reason = text(n, "review_reason");
        StringBuilder sb = new StringBuilder();
        sb.append("Your order has been rejected.");
        if (orderId != null) sb.append(" Order ID: ").append(orderId).append(".");
        if (productId != null) sb.append(" Product: ").append(productId).append(".");
        if (reason != null && !reason.isEmpty()) sb.append(" Reason: ").append(reason).append(".");
        return sb.toString();
    }

    private String buildEndorsementPendingPaymentMessage(JsonNode n) {
        String policyId = text(n, "policy_id");
        String endorsementId = text(n, "endorsement_request_id");
        String invoiceId = text(n, "invoice_id");
        String additionalCharge = text(n, "additional_charge_vnd");
        String dueDays = text(n, "due_days");
        StringBuilder sb = new StringBuilder();
        sb.append("Your endorsement has been approved. Payment is required to take effect.");
        if (policyId != null) sb.append(" Policy ID: ").append(policyId).append(".");
        if (endorsementId != null) sb.append(" Endorsement ID: ").append(endorsementId).append(".");
        if (additionalCharge != null) sb.append(" Additional charge: ").append(formatVnd(additionalCharge)).append(" VND.");
        if (dueDays != null) sb.append(" Please pay within ").append(dueDays).append(" days.");
        if (invoiceId != null && !invoiceId.isEmpty()) {
            sb.append(" Invoice ID: ").append(invoiceId).append(".");
            sb.append(" Please log in and use the payment portal to complete your payment via VNPAY.");
        }
        return sb.toString();
    }

    private String buildEndorsementOverdueMessage(JsonNode n) {
        String policyId = text(n, "policy_id");
        String endorsementId = text(n, "endorsement_request_id");
        String dueDate = text(n, "due_date");
        StringBuilder sb = new StringBuilder();
        sb.append("Your endorsement request has expired due to non-payment.");
        if (policyId != null) sb.append(" Policy ID: ").append(policyId).append(".");
        if (endorsementId != null) sb.append(" Endorsement ID: ").append(endorsementId).append(".");
        if (dueDate != null) sb.append(" Due date was: ").append(dueDate).append(".");
        sb.append(" Your policy remains unchanged.");
        return sb.toString();
    }

    private String buildCreditIssuedMessage(JsonNode n) {
        String policyId = text(n, "policy_id");
        String endorsementId = text(n, "endorsement_request_id");
        String amount = text(n, "amount_vnd");
        StringBuilder sb = new StringBuilder();
        sb.append("A premium credit has been issued to your account from an endorsement.");
        if (policyId != null) sb.append(" Policy ID: ").append(policyId).append(".");
        if (endorsementId != null) sb.append(" Endorsement ID: ").append(endorsementId).append(".");
        if (amount != null) sb.append(" Credit amount: ").append(formatVnd(amount)).append(" VND.");
        sb.append(" This credit will be automatically applied to your future invoices.");
        return sb.toString();
    }

    private String buildRefundRequestedMessage(JsonNode n) {
        String policyId = text(n, "policy_id");
        String refundId = text(n, "refund_id");
        String amount = text(n, "amount_vnd");
        StringBuilder sb = new StringBuilder();
        sb.append("A refund request has been created for your policy.");
        if (policyId != null) sb.append(" Policy ID: ").append(policyId).append(".");
        if (refundId != null) sb.append(" Refund ID: ").append(refundId).append(".");
        if (amount != null) sb.append(" Refund amount: ").append(formatVnd(amount)).append(" VND.");
        sb.append(" Our team will process your refund shortly.");
        return sb.toString();
    }

    private String buildRefundCompletedMessage(JsonNode n) {
        String policyId = text(n, "policy_id");
        String refundId = text(n, "refund_id");
        String amount = text(n, "amount_vnd");
        String paymentRef = text(n, "payment_reference");
        StringBuilder sb = new StringBuilder();
        sb.append("Your refund has been completed.");
        if (policyId != null) sb.append(" Policy ID: ").append(policyId).append(".");
        if (refundId != null) sb.append(" Refund ID: ").append(refundId).append(".");
        if (amount != null) sb.append(" Refund amount: ").append(formatVnd(amount)).append(" VND.");
        if (paymentRef != null) sb.append(" Payment reference: ").append(paymentRef).append(".");
        return sb.toString();
    }

    private String text(JsonNode n, String field) {
        return n.has(field) && !n.get(field).isNull() ? n.get(field).asText() : null;
    }

    private String formatVnd(String value) {
        try {
            long v = Long.parseLong(value);
            return String.format("%,d", v);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    @FunctionalInterface
    private interface MessageBuilder {
        String build(JsonNode payload);
    }
}
