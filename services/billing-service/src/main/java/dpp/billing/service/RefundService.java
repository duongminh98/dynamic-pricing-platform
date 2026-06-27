package dpp.billing.service;

import dpp.billing.dto.RefundResponse;
import dpp.billing.entity.*;
import dpp.billing.repository.*;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.dto.PageResponse;
import dpp.common.outbox.OutboxPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
    public RefundResponse completeRefund(UUID refundId, String paymentReference, String completedBy, String note) {
        RefundRequest refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Refund not found", null));
        if (refund.getStatus() == RefundStatus.completed) {
            return toResponse(refund);
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
        if (note != null && !note.isBlank()) {
            refund.setNote(note);
        }
        refundRepository.save(refund);
        enqueueRefundEvent("RefundCompleted", refund);
        return toResponse(refund);
    }

    @Transactional
    public RefundResponse rejectRefund(UUID refundId, String reason) {
        RefundRequest refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Refund not found", null));
        if (refund.getStatus() != RefundStatus.pending) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Only pending refunds can be rejected", null);
        }
        if (reason == null || reason.isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Reject reason is required", null);
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
        enqueueRefundEvent("RefundRejected", refund);
        return toResponse(refund);
    }

    @Transactional(readOnly = true)
    public PageResponse<RefundResponse> listFiltered(RefundStatus status, UUID customerId, UUID policyId, Pageable pageable) {
        Page<RefundRequest> page = refundRepository.findFiltered(status, customerId, policyId, pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> listByPolicy(UUID policyId) {
        return refundRepository.findByPolicyIdOrderByRequestedAtDesc(policyId)
                .stream().map(this::toResponse).toList();
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
            if (refund.getNote() != null) {
                payload.put("note", refund.getNote());
            }
            outboxPublisher.enqueue(type, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue " + type, e);
        }
    }

    private RefundResponse toResponse(RefundRequest refund) {
        RefundResponse resp = new RefundResponse();
        resp.setRefundId(refund.getRefundId());
        resp.setPolicyId(refund.getPolicyId());
        resp.setCustomerId(refund.getCustomerId());
        resp.setCreditId(refund.getCreditId());
        resp.setAmountVnd(refund.getAmountVnd());
        resp.setStatus(refund.getStatus());
        resp.setPaymentReference(refund.getPaymentReference());
        resp.setNote(refund.getNote());
        resp.setRequestedAt(refund.getRequestedAt());
        resp.setCompletedBy(refund.getCompletedBy());
        resp.setCompletedAt(refund.getCompletedAt());
        return resp;
    }
}
