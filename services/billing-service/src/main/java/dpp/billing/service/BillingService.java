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

    @Transactional
    public InvoiceResponse createInvoice(CreateInvoiceRequest request) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(UUID.randomUUID());
        invoice.setOrderId(request.getOrderId());
        invoice.setPolicyId(request.getPolicyId());
        invoice.setAmountVnd(request.getAmountVnd());
        invoice.setStatus(InvoiceStatus.unpaid);
        invoice.setCreatedAt(OffsetDateTime.now());
        invoice = invoiceRepository.save(invoice);
        return toResponse(invoice);
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
