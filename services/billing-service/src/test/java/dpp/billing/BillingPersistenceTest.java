package dpp.billing;

import dpp.billing.entity.Adjustment;
import dpp.billing.entity.AdjustmentReason;
import dpp.billing.repository.AdjustmentRepository;
import dpp.billing.repository.InvoiceRepository;
import dpp.billing.service.AdjustmentService;
import dpp.billing.service.BillingService;
import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.InvoiceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-DB persistence tests (design 5.6, R33.4/R33.5). These exercise the JPA
 * insert path so the Adjustment.created_at NOT NULL bug (mock-only tests miss it)
 * is caught. Requires dpp-postgres-billing (5438) running.
 * Requirements: R33.4, R33.5.
 */
@SpringBootTest
@Transactional
class BillingPersistenceTest {

    @Autowired
    private AdjustmentService adjustmentService;

    @Autowired
    private AdjustmentRepository adjustmentRepository;

    @Autowired
    private BillingService billingService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    // OutboxPublisher is a real bean; OrderClient is autowired. We do not hit the
    // network here because these tests do not call getPolicyBilling.

    @Test
    void endorsementAdjustmentPersistsWithCreatedAt() {
        UUID policyId = UUID.randomUUID();
        adjustmentService.applyEndorsement(UUID.randomUUID().toString(), policyId, UUID.randomUUID(), 1_000_000L, 1_500_000L, 200, 365);

        List<Adjustment> saved = adjustmentRepository.findByPolicyIdOrderByCreatedAtAsc(policyId);
        assertEquals(1, saved.size(), "Adjustment must be inserted (created_at NOT NULL satisfied)");
        assertNotNull(saved.get(0).getCreatedAt(), "created_at must be set, not rely on DB default");
        assertEquals(AdjustmentReason.endorsement, saved.get(0).getReason());
    }

    @Test
    void cancellationAdjustmentPersistsWithCreatedAt() {
        UUID policyId = UUID.randomUUID();
        adjustmentService.applyCancellation(UUID.randomUUID().toString(), policyId, 2_000_000L, 100, 365);

        List<Adjustment> saved = adjustmentRepository.findByPolicyIdOrderByCreatedAtAsc(policyId);
        assertEquals(1, saved.size());
        assertNotNull(saved.get(0).getCreatedAt());
        assertEquals(AdjustmentReason.cancellation, saved.get(0).getReason());
        assertTrue(saved.get(0).getAmountVnd() >= 0);
    }

    @Test
    void duplicateEventIdDoesNotCreateDuplicateAdjustment() {
        UUID policyId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        adjustmentService.applyEndorsement(eventId, policyId, UUID.randomUUID(), 1_000_000L, 1_500_000L, 200, 365);
        // Redelivery of the same event_id is a no-op (R33.4 dedup).
        adjustmentService.applyEndorsement(eventId, policyId, UUID.randomUUID(), 1_000_000L, 1_500_000L, 200, 365);

        List<Adjustment> saved = adjustmentRepository.findByPolicyIdOrderByCreatedAtAsc(policyId);
        assertEquals(1, saved.size(), "redelivered event must not create a second adjustment");
    }

    @Test
    void invoiceCreationIsIdempotentOnOrderId() {
        UUID orderId = UUID.randomUUID();
        CreateInvoiceRequest req = new CreateInvoiceRequest();
        req.setOrderId(orderId);
        req.setAmountVnd(2_500_000L);
        InvoiceResponse first = billingService.createInvoice(req);

        // A commit-then-REST retry replays createInvoice with the same order_id.
        CreateInvoiceRequest retry = new CreateInvoiceRequest();
        retry.setOrderId(orderId);
        retry.setAmountVnd(2_500_000L);
        InvoiceResponse second = billingService.createInvoice(retry);

        assertEquals(first.getInvoiceId(), second.getInvoiceId(), "retry must return the existing invoice");
        assertTrue(invoiceRepository.findByOrderId(orderId).isPresent(), "invoice exists for the order");
    }

    @Test
    void invoiceIsListedByPolicyOrderedByCreatedAt() {
        UUID policyId = UUID.randomUUID();
        CreateInvoiceRequest req = new CreateInvoiceRequest();
        req.setOrderId(UUID.randomUUID());
        req.setPolicyId(policyId);
        req.setAmountVnd(3_300_000L);
        InvoiceResponse created = billingService.createInvoice(req);

        assertNotNull(created.getCreatedAt());
        assertEquals(1, invoiceRepository.findByPolicyIdOrderByCreatedAtAsc(policyId).size());
    }
}
