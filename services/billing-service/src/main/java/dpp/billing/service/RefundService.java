package dpp.billing.service;

import dpp.billing.entity.*;
import dpp.billing.repository.*;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Manual refund management (design §8).
 *
 * <p>RefundRequest represents a pending payout of premium credit to the customer
 * when there is nothing left to net-off (e.g. policy cancelled, no renewal).</p>
 */
@Service
public class RefundService {

    private final RefundRequestRepository refundRepository;
    private final PremiumCreditRepository creditRepository;
    private final OutboxPublisher outboxPublisher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RefundService(RefundRequestRepository refundRepository,
                         PremiumCreditRepository creditRepository,
                         OutboxPublisher outboxPublisher) {
        this.refundRepository = refundRepository;
        this.creditRepository = creditRepository;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    }

    @Transactional
    public RefundRequest createRefund(UUID policyId, UUID customerId, UUID creditId, long amountVnd, String note) {
        RefundRequest refund = new RefundRequest();
        refund.setRefundId(UUID.randomUUID());
        refund.setPolicyId(policyId);
        refund.setCustomerId(customerId);
        refund.setCreditId(creditId);
        refund.setAmountVnd(amountVnd);
        refund.setStatus(RefundStatus.pending);
        refund.setNote(note);
        refund.setRequestedAt(OffsetDateTime.now());

        if (creditId != null) {
            PremiumCredit credit = creditRepository.findById(creditId)
                    .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Credit not found", null));
            credit.setStatus(CreditStatus.refunded);
            creditRepository.save(credit);
        }

        refundRepository.save(refund);
        enqueueRefundEvent("RefundRequested", refund);
        return refund;
    }

    @Transactional
    public RefundRequest completeRefund(UUID refundId, String paymentReference, String completedBy) {
        RefundRequest refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Refund not found", null));
        if (refund.getStatus() == RefundStatus.completed) {
            return refund;
        }
        if (refund.getStatus() != RefundStatus.pending) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Only pending refunds can be completed", null);
        }
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Payment reference is required", null);
        }
        refund.setStatus(RefundStatus.completed);
        refund.setPaymentReference(paymentReference);
        refund.setCompletedBy(completedBy);
        refund.setCompletedAt(OffsetDateTime.now());
        refundRepository.save(refund);
        enqueueRefundEvent("RefundCompleted", refund);
        return refund;
    }

    @Transactional
    public RefundRequest rejectRefund(UUID refundId, String reason) {
        RefundRequest refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Refund not found", null));
        if (refund.getStatus() != RefundStatus.pending) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Only pending refunds can be rejected", null);
        }
        refund.setStatus(RefundStatus.rejected);
        refund.setNote(reason);
        refundRepository.save(refund);

        if (refund.getCreditId() != null) {
            PremiumCredit credit = creditRepository.findById(refund.getCreditId()).orElse(null);
            if (credit != null && credit.getStatus() == CreditStatus.refunded) {
                if (credit.getRemainingAmountVnd() <= 0) {
                    credit.setStatus(CreditStatus.exhausted);
                } else if (credit.getRemainingAmountVnd() < credit.getOriginalAmountVnd()) {
                    credit.setStatus(CreditStatus.partially_applied);
                } else {
                    credit.setStatus(CreditStatus.open);
                }
                creditRepository.save(credit);
            }
        }
        return refund;
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> listByStatus(RefundStatus status) {
        return refundRepository.findByStatusOrderByRequestedAtAsc(status);
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> listAll() {
        return refundRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> listByPolicy(UUID policyId) {
        return refundRepository.findByPolicyIdOrderByRequestedAtDesc(policyId);
    }

    @Transactional
    public void createRefundsForCancelledPolicy(UUID policyId, UUID customerId) {
        List<PremiumCredit> activeCredits = creditRepository
                .findByPolicyIdAndRemainingAmountVndGreaterThan(policyId, 0);
        for (PremiumCredit credit : activeCredits) {
            if (credit.getStatus() == CreditStatus.refunded) continue;
            RefundRequest refund = new RefundRequest();
            refund.setRefundId(UUID.randomUUID());
            refund.setPolicyId(policyId);
            refund.setCustomerId(customerId);
            refund.setCreditId(credit.getCreditId());
            refund.setAmountVnd(credit.getRemainingAmountVnd());
            refund.setStatus(RefundStatus.pending);
            refund.setRequestedAt(OffsetDateTime.now());
            refundRepository.save(refund);

            credit.setStatus(CreditStatus.refunded);
            creditRepository.save(credit);
            enqueueRefundEvent("RefundRequested", refund);
        }
    }

    private void enqueueRefundEvent(String type, RefundRequest refund) {
        try {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("refund_id", refund.getRefundId().toString());
            payload.put("policy_id", refund.getPolicyId().toString());
            payload.put("customer_id", refund.getCustomerId().toString());
            payload.put("amount_vnd", refund.getAmountVnd());
            if (refund.getPaymentReference() != null) {
                payload.put("payment_reference", refund.getPaymentReference());
            }
            outboxPublisher.enqueue(type, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue " + type, e);
        }
    }
}
