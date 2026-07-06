package dpp.billing.service;

import dpp.billing.dto.AdjustmentResponse;
import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.CreditResponse;
import dpp.billing.dto.InvoiceResponse;
import dpp.billing.dto.PageResponse;
import dpp.billing.dto.PolicyBillingResponse;
import dpp.billing.dto.RefundResponse;
import dpp.billing.entity.*;
import dpp.billing.repository.*;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingService {

    private final InvoiceRepository invoiceRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;
    private final CreditService creditService;
    private final RefundService refundService;

    public BillingService(InvoiceRepository invoiceRepository, AdjustmentRepository adjustmentRepository,
                          OutboxPublisher outboxPublisher, CreditService creditService,
                          RefundService refundService) {
        this.invoiceRepository = invoiceRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.outboxPublisher = outboxPublisher;
        this.creditService = creditService;
        this.refundService = refundService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Create an invoice for an order, idempotent on order_id (task 20.11, R33.4).
     *
     * <p>Order_Service issues this call after committing the order (commit-then-REST),
     * so a transient failure or retry can replay it. Returning the existing invoice
     * instead of inserting a new one keeps approve() retries safe and prevents
     * duplicate invoices for the same order. A UNIQUE constraint on invoice.order_id
     * (Flyway V4) is the backstop if two replays race past this check.</p>
     */
    @Transactional
    public InvoiceResponse createInvoice(CreateInvoiceRequest request) {
        // Endorsement adjustment invoices - idempotent on endorsement_request_id.
        // Multiple events may be delivered (reconciliation re-enqueue, redelivery);
        // dedup prevents duplicate invoices for the same endorsement.
        if (request.getEndorsementRequestId() != null) {
            List<Invoice> existing = invoiceRepository
                    .findByEndorsementRequestIdOrderByCreatedAtDesc(request.getEndorsementRequestId());
            for (Invoice inv : existing) {
                if (inv.getStatus() != InvoiceStatus.voided) {
                    inv = attachCustomerIdIfMissing(inv, request.getCustomerId());
                    enqueueInvoiceCreated(inv);
                    return toResponse(inv);
                }
            }
            Invoice invoice = new Invoice();
            invoice.setInvoiceId(UUID.randomUUID());
            invoice.setOrderId(request.getOrderId());
            invoice.setPolicyId(request.getPolicyId());
            invoice.setCustomerId(request.getCustomerId());
            invoice.setAmountVnd(request.getAmountVnd());
            invoice.setStatus(InvoiceStatus.unpaid);
            invoice.setEndorsementRequestId(request.getEndorsementRequestId());
            invoice.setDueDate(request.getDueDate());
            invoice.setCreatedAt(OffsetDateTime.now());
            invoice = invoiceRepository.save(invoice);
            // Net-off: apply available customer-scoped credits FIFO against this invoice.
            UUID customerIdForCredit = invoice.getCustomerId();
            if (customerIdForCredit != null) {
                long netDue = creditService.applyCreditsToInvoice(invoice.getInvoiceId(),
                        customerIdForCredit, invoice.getAmountVnd());
                if (netDue <= 0) {
                    // Credit covers full amount - mark paid immediately.
                    invoice.setStatus(InvoiceStatus.paid);
                    invoice.setPaidAt(OffsetDateTime.now());
                    invoice = invoiceRepository.save(invoice);
                    enqueueInvoicePaid(invoice);
                }
            }
            enqueueInvoiceCreated(invoice);
            return toResponse(invoice);
        }
        // Renewal invoices carry a policyId - idempotent on policyId so each renewal
        // term gets its own invoice. Initial order invoices have null policyId -
        // idempotent on orderId (existing behavior, backed by UNIQUE constraint V4).
        if (request.getPolicyId() != null) {
            return invoiceRepository.findByPolicyId(request.getPolicyId())
                    .map(invoice -> toResponse(attachCustomerIdIfMissing(invoice, request.getCustomerId())))
                    .orElseGet(() -> {
                        Invoice invoice = new Invoice();
                        invoice.setInvoiceId(UUID.randomUUID());
                        invoice.setOrderId(request.getOrderId());
                        invoice.setPolicyId(request.getPolicyId());
                        invoice.setCustomerId(request.getCustomerId());
                        invoice.setAmountVnd(request.getAmountVnd());
                        invoice.setStatus(InvoiceStatus.unpaid);
                        invoice.setCreatedAt(OffsetDateTime.now());
                        invoice = invoiceRepository.save(invoice);
                        // Net-off: apply available customer-scoped credits for renewal invoices.
                        UUID customerIdForCredit = invoice.getCustomerId();
                        if (customerIdForCredit != null) {
                            long netDue = creditService.applyCreditsToInvoice(invoice.getInvoiceId(),
                                    customerIdForCredit, invoice.getAmountVnd());
                            if (netDue <= 0) {
                                invoice.setStatus(InvoiceStatus.paid);
                                invoice.setPaidAt(OffsetDateTime.now());
                                invoice = invoiceRepository.save(invoice);
                                enqueueInvoicePaid(invoice);
                            }
                        }
                        return toResponse(invoice);
                    });
        }
        return invoiceRepository.findByOrderId(request.getOrderId())
                .map(inv -> {
                    inv = attachCustomerIdIfMissing(inv, request.getCustomerId());
                    enqueueInvoiceCreated(inv);
                    return toResponse(inv);
                })
                .orElseGet(() -> {
                    Invoice invoice = new Invoice();
                    invoice.setInvoiceId(UUID.randomUUID());
                    invoice.setOrderId(request.getOrderId());
                    invoice.setPolicyId(request.getPolicyId());
                    invoice.setCustomerId(request.getCustomerId());
                    invoice.setAmountVnd(request.getAmountVnd());
                    invoice.setStatus(InvoiceStatus.unpaid);
                    invoice.setCreatedAt(OffsetDateTime.now());
                    invoice = invoiceRepository.save(invoice);
                    enqueueInvoiceCreated(invoice);
                    return toResponse(invoice);
                });
    }

    private Invoice attachCustomerIdIfMissing(Invoice invoice, UUID customerId) {
        if (invoice.getCustomerId() == null && customerId != null) {
            invoice.setCustomerId(customerId);
            return invoiceRepository.save(invoice);
        }
        return invoice;
    }

    @Transactional
    public InvoiceResponse payInvoice(UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Invoice not found", null));
        if (invoice.getStatus() == InvoiceStatus.paid) {
            return toResponse(invoice);
        }
        if (invoice.getStatus() == InvoiceStatus.voided) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Cannot pay a voided invoice", null);
        }
        invoice.setStatus(InvoiceStatus.paid);
        invoice.setPaidAt(OffsetDateTime.now());
        invoice = invoiceRepository.save(invoice);

        enqueueInvoicePaid(invoice);
        return toResponse(invoice);
    }

    /**
     * Customer-initiated payment of an invoice (task 20.13, R33.5, BR-10).
     *
     * <p>Enforces data-ownership isolation using the customer_id persisted on the
     * invoice at creation time. The server-to-server VNPAY IPN path uses
     * {@link #payInvoice(UUID)} directly and is not subject to this check.</p>
     */
    @Transactional
    public InvoiceResponse payInvoiceAsCustomer(UUID invoiceId, UUID callerCustomerId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Invoice not found", null));
        UUID owner = resolveInvoiceOwner(invoice);
        if (owner == null || !owner.equals(callerCustomerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return payInvoice(invoiceId);
    }

    private UUID resolveInvoiceOwner(Invoice invoice) {
        return invoice.getCustomerId();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoiceByOrder(UUID orderId, UUID callerCustomerId) {
        Invoice invoice = invoiceRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Invoice not found for order", null));
        UUID owner = resolveInvoiceOwner(invoice);
        if (owner == null || !owner.equals(callerCustomerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return toResponse(invoice);
    }

    @Transactional(readOnly = true)
    public PolicyBillingResponse getPolicyBilling(UUID policyId, UUID callerCustomerId) {
        List<Invoice> invoiceRows = invoiceRepository.findByPolicyIdOrderByCreatedAtAsc(policyId);
        boolean hasOwnerInvoice = invoiceRows.stream()
                .anyMatch(invoice -> callerCustomerId.equals(invoice.getCustomerId()));
        if (!hasOwnerInvoice) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        PolicyBillingResponse resp = new PolicyBillingResponse();
        List<InvoiceResponse> invoices = invoiceRows.stream().map(this::toResponse).toList();
        List<AdjustmentResponse> adjustments = adjustmentRepository.findByPolicyIdOrderByCreatedAtAsc(policyId)
                .stream().map(this::toAdjustmentResponse).toList();
        List<PremiumCredit> credits = creditService.getCreditsByPolicy(policyId);
        long unpaidNet = invoices.stream()
                .filter(i -> i.getStatus() == InvoiceStatus.unpaid)
                .mapToLong(InvoiceResponse::getNetAmountVnd)
                .sum();
        long creditRemaining = credits.stream()
                .filter(c -> c.getStatus() == CreditStatus.open || c.getStatus() == CreditStatus.partially_applied)
                .mapToLong(PremiumCredit::getRemainingAmountVnd)
                .sum();
        List<RefundResponse> refunds = refundService.listByPolicy(policyId);
        resp.setInvoices(invoices);
        resp.setAdjustments(adjustments);
        resp.setCredits(credits.stream().map(this::toCreditResponse).toList());
        resp.setRefunds(refunds);
        resp.setBalanceVnd(unpaidNet - creditRemaining);
        return resp;
    }

    private AdjustmentResponse toAdjustmentResponse(Adjustment adj) {
        AdjustmentResponse r = new AdjustmentResponse();
        r.setAdjustmentId(adj.getAdjustmentId());
        r.setPolicyId(adj.getPolicyId());
        r.setType(adj.getType());
        r.setAmountVnd(adj.getAmountVnd());
        r.setReason(adj.getReason());
        r.setCreatedAt(adj.getCreatedAt());
        return r;
    }


    @Transactional(readOnly = true)
    public PageResponse<AdjustmentResponse> adminListAdjustmentsPaged(AdjustmentType type, AdjustmentReason reason,
                                                                      UUID policyId,
                                                                      org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<Adjustment> page = adjustmentRepository.findFiltered(type, reason, policyId, pageable);
        return PageResponse.from(page.map(this::toAdjustmentResponse));
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        InvoiceResponse resp = new InvoiceResponse();
        resp.setInvoiceId(invoice.getInvoiceId());
        resp.setOrderId(invoice.getOrderId());
        resp.setPolicyId(invoice.getPolicyId());
        resp.setCustomerId(invoice.getCustomerId());
        resp.setAmountVnd(invoice.getAmountVnd());
        resp.setStatus(invoice.getStatus());
        resp.setPaidAt(invoice.getPaidAt());
        resp.setCreatedAt(invoice.getCreatedAt());
        resp.setEndorsementRequestId(invoice.getEndorsementRequestId());
        resp.setDueDate(invoice.getDueDate());
        resp.setCreditAppliedVnd(invoice.getCreditAppliedVnd());
        resp.setNetAmountVnd(invoice.getNetAmountVnd());
        return resp;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> adminListAllInvoices() {
        return invoiceRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toResponse).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> adminListInvoicesPaged(InvoiceStatus status, org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<Invoice> page = invoiceRepository.findFiltered(status, pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> adminListInvoicesByStatus(InvoiceStatus status) {
        return invoiceRepository.findByStatusOrderByCreatedAtDesc(status)
                .stream().map(this::toResponse).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public InvoiceResponse adminGetInvoice(UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Invoice not found", null));
        return toResponse(invoice);
    }

    @Transactional
    public InvoiceResponse voidInvoice(UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Invoice not found", null));
        if (invoice.getStatus() == InvoiceStatus.voided) {
            return toResponse(invoice);
        }
        if (invoice.getStatus() == InvoiceStatus.paid) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Cannot void a paid invoice", null);
        }
        if (invoice.getCreditAppliedVnd() > 0) {
            creditService.reverseCreditsForInvoice(invoice.getInvoiceId());
        }
        invoice.setStatus(InvoiceStatus.voided);
        invoice = invoiceRepository.save(invoice);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoice_id", invoice.getInvoiceId());
        payload.put("order_id", invoice.getOrderId());
        payload.put("policy_id", invoice.getPolicyId());
        if (invoice.getEndorsementRequestId() != null) {
            payload.put("endorsement_request_id", invoice.getEndorsementRequestId());
        }
        if (invoice.getCustomerId() != null) {
            payload.put("customer_id", invoice.getCustomerId());
        }
        payload.put("amount_vnd", invoice.getAmountVnd());
        try {
            outboxPublisher.enqueue("InvoiceVoided", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue InvoiceVoided event", e);
        }
        return toResponse(invoice);
    }

    @Transactional
    public void voidInvoiceByEndorsementRequestId(UUID endorsementRequestId) {
        List<Invoice> invoices = invoiceRepository.findByEndorsementRequestIdOrderByCreatedAtDesc(endorsementRequestId);
        for (Invoice invoice : invoices) {
            if (invoice.getStatus() == InvoiceStatus.unpaid) {
                if (invoice.getCreditAppliedVnd() > 0) {
                    creditService.reverseCreditsForInvoice(invoice.getInvoiceId());
                }
                invoice.setStatus(InvoiceStatus.voided);
                invoice = invoiceRepository.save(invoice);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("invoice_id", invoice.getInvoiceId());
                payload.put("order_id", invoice.getOrderId());
                payload.put("policy_id", invoice.getPolicyId());
                payload.put("endorsement_request_id", endorsementRequestId);
                if (invoice.getCustomerId() != null) {
                    payload.put("customer_id", invoice.getCustomerId());
                }
                payload.put("amount_vnd", invoice.getAmountVnd());
                try {
                    outboxPublisher.enqueue("InvoiceVoided", objectMapper.writeValueAsString(payload));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to enqueue InvoiceVoided event", e);
                }
            }
        }
    }

    private void enqueueInvoiceCreated(Invoice invoice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoice_id", invoice.getInvoiceId());
        payload.put("order_id", invoice.getOrderId());
        if (invoice.getPolicyId() != null) {
            payload.put("policy_id", invoice.getPolicyId());
        }
        if (invoice.getEndorsementRequestId() != null) {
            payload.put("endorsement_request_id", invoice.getEndorsementRequestId());
        }
        if (invoice.getCustomerId() != null) {
            payload.put("customer_id", invoice.getCustomerId());
        }
        payload.put("amount_vnd", invoice.getAmountVnd());
        try {
            outboxPublisher.enqueue("InvoiceCreated", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue InvoiceCreated event", e);
        }
    }

    private void enqueueInvoicePaid(Invoice invoice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", invoice.getOrderId());
        payload.put("policy_id", invoice.getPolicyId());
        payload.put("invoice_id", invoice.getInvoiceId());
        if (invoice.getEndorsementRequestId() != null) {
            payload.put("endorsement_request_id", invoice.getEndorsementRequestId());
        }
        if (invoice.getCustomerId() != null) {
            payload.put("customer_id", invoice.getCustomerId());
        }
        try {
            outboxPublisher.enqueue("InvoicePaid", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue InvoicePaid event", e);
        }
    }

    private CreditResponse toCreditResponse(PremiumCredit credit) {
        CreditResponse resp = new CreditResponse();
        resp.setCreditId(credit.getCreditId());
        resp.setPolicyId(credit.getPolicyId());
        resp.setSourceEndorsementId(credit.getSourceEndorsementId());
        resp.setOriginalAmountVnd(credit.getOriginalAmountVnd());
        resp.setRemainingAmountVnd(credit.getRemainingAmountVnd());
        resp.setStatus(credit.getStatus());
        resp.setCreatedAt(credit.getCreatedAt());
        return resp;
    }
}


