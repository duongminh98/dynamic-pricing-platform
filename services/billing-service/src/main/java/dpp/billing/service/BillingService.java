package dpp.billing.service;

import dpp.billing.client.OrderClient;
import dpp.billing.dto.AdjustmentResponse;
import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.InvoiceResponse;
import dpp.billing.dto.PolicyBillingResponse;
import dpp.billing.entity.Adjustment;
import dpp.billing.entity.Invoice;
import dpp.billing.entity.InvoiceStatus;
import dpp.billing.repository.AdjustmentRepository;
import dpp.billing.repository.InvoiceRepository;
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

    public BillingService(InvoiceRepository invoiceRepository, AdjustmentRepository adjustmentRepository,
                          OrderClient orderClient, OutboxPublisher outboxPublisher) {
        this.invoiceRepository = invoiceRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.orderClient = orderClient;
        this.outboxPublisher = outboxPublisher;
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
        invoice.setStatus(InvoiceStatus.paid);
        invoice.setPaidAt(OffsetDateTime.now());
        invoice = invoiceRepository.save(invoice);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", invoice.getOrderId());
        payload.put("policy_id", invoice.getPolicyId());
        payload.put("invoice_id", invoice.getInvoiceId());
        try {
            outboxPublisher.enqueue("InvoicePaid", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue InvoicePaid event", e);
        }
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
        resp.setInvoices(invoices);
        resp.setAdjustments(adjustments);
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
        return resp;
    }
}
