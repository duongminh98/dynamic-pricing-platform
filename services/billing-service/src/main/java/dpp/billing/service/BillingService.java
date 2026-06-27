package dpp.billing.service;

import dpp.billing.client.OrderClient;
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
    private final OrderClient orderClient;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;
    private final CreditService creditService;
    private final RefundService refundService;

    public BillingService(InvoiceRepository invoiceRepository, AdjustmentRepository adjustmentRepository,
                          OrderClient orderClient, OutboxPublisher outboxPublisher, CreditService creditService,
                          RefundService refundService) {
        this.invoiceRepository = invoiceRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.orderClient = orderClient;
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
        // Endorsement adjustment invoices are not idempotent on order_id — multiple
        // endorsements can create multiple invoices for the same order/policy.
        if (request.getEndorsementRequestId() != null) {
            Invoice invoice = new Invoice();
            invoice.setInvoiceId(UUID.randomUUID());
            invoice.setOrderId(request.getOrderId());
            invoice.setPolicyId(request.getPolicyId());
            invoice.setAmountVnd(request.getAmountVnd());
            invoice.setStatus(InvoiceStatus.unpaid);
            invoice.setEndorsementRequestId(request.getEndorsementRequestId());
            invoice.setDueDate(request.getDueDate());
            invoice.setCreatedAt(OffsetDateTime.now());
            invoice = invoiceRepository.save(invoice);
            // Net-off: apply available customer-scoped credits FIFO against this invoice.
            UUID customerIdForCredit = request.getCustomerId();
            if (customerIdForCredit == null) {
                try {
                    customerIdForCredit = resolveInvoiceOwner(invoice);
                } catch (Exception ignored) {
                }
            }
            if (customerIdForCredit != null) {
                long netDue = creditService.applyCreditsToInvoice(invoice.getInvoiceId(),
                        customerIdForCredit, invoice.getAmountVnd());
                if (netDue <= 0) {
                    // Credit covers full amount — mark paid immediately.
                    invoice.setStatus(InvoiceStatus.paid);
                    invoice.setPaidAt(OffsetDateTime.now());
                    invoice = invoiceRepository.save(invoice);
                    enqueueInvoicePaid(invoice);
                }
            }
            return toResponse(invoice);
        }
        // Renewal invoices carry a policyId — idempotent on policyId so each renewal
        // term gets its own invoice. Initial order invoices have null policyId —
        // idempotent on orderId (existing behavior, backed by UNIQUE constraint V4).
        if (request.getPolicyId() != null) {
            return invoiceRepository.findByPolicyId(request.getPolicyId())
                    .map(this::toResponse)
                    .orElseGet(() -> {
                        Invoice invoice = new Invoice();
                        invoice.setInvoiceId(UUID.randomUUID());
                        invoice.setOrderId(request.getOrderId());
                        invoice.setPolicyId(request.getPolicyId());
                        invoice.setAmountVnd(request.getAmountVnd());
                        invoice.setStatus(InvoiceStatus.unpaid);
                        invoice.setCreatedAt(OffsetDateTime.now());
                        invoice = invoiceRepository.save(invoice);
                        // Net-off: apply available customer-scoped credits for renewal invoices.
                        UUID customerIdForCredit = request.getCustomerId();
                        if (customerIdForCredit == null) {
                            try {
                                customerIdForCredit = resolveInvoiceOwner(invoice);
                            } catch (Exception ignored) {
                            }
                        }
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
                .map(this::toResponse)
                .orElseGet(() -> {
                    Invoice invoice = new Invoice();
                    invoice.setInvoiceId(UUID.randomUUID());
                    invoice.setOrderId(request.getOrderId());
                    invoice.setPolicyId(request.getPolicyId());
                    invoice.setAmountVnd(request.getAmountVnd());
                    invoice.setStatus(InvoiceStatus.unpaid);
                    invoice.setCreatedAt(OffsetDateTime.now());
                    return toResponse(invoiceRepository.save(invoice));
                });
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
     * <p>Enforces data-ownership isolation: the JWT subject must own the order
     * (or policy) the invoice belongs to. Ownership is resolved through
     * Order_Service via {@link OrderClient}; a mismatch maps to FORBIDDEN_RESOURCE
     * so a user cannot pay another customer's invoice. The server-to-server VNPAY
     * IPN path uses {@link #payInvoice(UUID)} directly and is not subject to this
     * check (the gateway/IPN signature is its trust boundary).</p>
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

    /**
     * Resolve the owning customer of an invoice. Invoices created at order
     * approval carry order_id with a null policy_id (the policy is issued only
     * after payment), so the order is the primary resolution path; the policy
     * path covers invoices that already reference an issued policy.
     */
    private UUID resolveInvoiceOwner(Invoice invoice) {
        if (invoice.getPolicyId() != null) {
            return orderClient.getPolicyOwner(invoice.getPolicyId());
        }
        return orderClient.getOrderOwner(invoice.getOrderId());
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
        UUID owner = orderClient.getPolicyOwner(policyId);
        if (owner == null || !owner.equals(callerCustomerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        PolicyBillingResponse resp = new PolicyBillingResponse();
        List<InvoiceResponse> invoices = invoiceRepository.findByPolicyIdOrderByCreatedAtAsc(policyId)
                .stream().map(this::toResponse).toList();
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

    private InvoiceResponse toResponse(Invoice invoice) {
        InvoiceResponse resp = new InvoiceResponse();
        resp.setInvoiceId(invoice.getInvoiceId());
        resp.setOrderId(invoice.getOrderId());
        resp.setPolicyId(invoice.getPolicyId());
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
        if (invoice.getStatus() == InvoiceStatus.paid) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Cannot void a paid invoice", null);
        }
        invoice.setStatus(InvoiceStatus.voided);
        invoice = invoiceRepository.save(invoice);

        UUID customerId = resolveInvoiceOwner(invoice);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoice_id", invoice.getInvoiceId());
        payload.put("order_id", invoice.getOrderId());
        payload.put("policy_id", invoice.getPolicyId());
        payload.put("customer_id", customerId);
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
                // Reverse credit applications before voiding.
                if (invoice.getCreditAppliedVnd() > 0) {
                    creditService.reverseCreditsForInvoice(invoice.getInvoiceId());
                }
                invoice.setStatus(InvoiceStatus.voided);
                invoiceRepository.save(invoice);
            }
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
