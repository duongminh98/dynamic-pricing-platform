package dpp.billing;

import dpp.billing.client.OrderClient;
import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.InvoiceResponse;
import dpp.billing.entity.Invoice;
import dpp.billing.entity.InvoiceStatus;
import dpp.billing.repository.AdjustmentRepository;
import dpp.billing.repository.InvoiceRepository;
import dpp.billing.repository.ProcessedEventRepository;
import dpp.billing.service.AdjustmentService;
import dpp.billing.service.BillingService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Mock-based unit tests (no DB) for task 20.11 (invoice idempotency) and 20.13
 * (consumer dedup + pay-ownership authz). Verifies the billing-side logic that
 * keeps retries/redeliveries safe and blocks cross-customer payment.
 *
 * Requirements: R33.4, R33.5.
 */
class BillingDedupAndAuthzTest {

    // --- Task 20.13: consumer dedup (no duplicate adjustment on redelivery) ---

    @Test
    void redeliveredEndorsementEventIsNoOp() {
        AdjustmentRepository adjRepo = mock(AdjustmentRepository.class);
        ProcessedEventRepository peRepo = mock(ProcessedEventRepository.class);
        String eventId = UUID.randomUUID().toString();
        when(peRepo.existsById(eventId)).thenReturn(true); // already processed

        AdjustmentService svc = new AdjustmentService(adjRepo, mock(InvoiceRepository.class), peRepo);
        svc.applyEndorsement(eventId, UUID.randomUUID(), UUID.randomUUID(), 1_000_000L, 1_500_000L, 200, 365);

        verify(adjRepo, never()).save(any());
        verify(peRepo, never()).save(any());
    }

    @Test
    void firstEndorsementEventInsertsAdjustmentAndLedger() {
        AdjustmentRepository adjRepo = mock(AdjustmentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        ProcessedEventRepository peRepo = mock(ProcessedEventRepository.class);
        String eventId = UUID.randomUUID().toString();
        when(peRepo.existsById(eventId)).thenReturn(false);

        AdjustmentService svc = new AdjustmentService(adjRepo, invRepo, peRepo);
        svc.applyEndorsement(eventId, UUID.randomUUID(), UUID.randomUUID(), 1_000_000L, 1_500_000L, 200, 365);

        verify(adjRepo, times(1)).save(any());
        verify(peRepo, times(1)).save(any());
    }

    @Test
    void redeliveredCancellationEventIsNoOp() {
        AdjustmentRepository adjRepo = mock(AdjustmentRepository.class);
        ProcessedEventRepository peRepo = mock(ProcessedEventRepository.class);
        String eventId = UUID.randomUUID().toString();
        when(peRepo.existsById(eventId)).thenReturn(true);

        AdjustmentService svc = new AdjustmentService(adjRepo, mock(InvoiceRepository.class), peRepo);
        svc.applyCancellation(eventId, UUID.randomUUID(), 2_000_000L, 100, 365);

        verify(adjRepo, never()).save(any());
        verify(peRepo, never()).save(any());
    }

    @Test
    void nullEventIdStillInsertsAdjustmentButNoLedger() {
        AdjustmentRepository adjRepo = mock(AdjustmentRepository.class);
        ProcessedEventRepository peRepo = mock(ProcessedEventRepository.class);

        AdjustmentService svc = new AdjustmentService(adjRepo, mock(InvoiceRepository.class), peRepo);
        svc.applyCancellation(null, UUID.randomUUID(), 2_000_000L, 100, 365);

        verify(adjRepo, times(1)).save(any());
        verify(peRepo, never()).existsById(any());
        verify(peRepo, never()).save(any());
    }

    // --- Task 20.11: createInvoice idempotency on order_id ---

    @Test
    void createInvoiceReturnsExistingWhenOrderAlreadyInvoiced() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID orderId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        Invoice existing = new Invoice();
        existing.setInvoiceId(existingId);
        existing.setOrderId(orderId);
        existing.setAmountVnd(2_500_000L);
        existing.setStatus(InvoiceStatus.unpaid);
        when(invRepo.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        BillingService svc = new BillingService(invRepo, mock(AdjustmentRepository.class),
                mock(OrderClient.class), mock(OutboxPublisher.class));
        CreateInvoiceRequest req = new CreateInvoiceRequest();
        req.setOrderId(orderId);
        req.setAmountVnd(2_500_000L);

        InvoiceResponse resp = svc.createInvoice(req);

        assertEquals(existingId, resp.getInvoiceId(), "must return the pre-existing invoice");
        verify(invRepo, never()).save(any()); // no duplicate insert
    }

    @Test
    void createInvoiceInsertsWhenOrderNotYetInvoiced() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID orderId = UUID.randomUUID();
        when(invRepo.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(invRepo.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        BillingService svc = new BillingService(invRepo, mock(AdjustmentRepository.class),
                mock(OrderClient.class), mock(OutboxPublisher.class));
        CreateInvoiceRequest req = new CreateInvoiceRequest();
        req.setOrderId(orderId);
        req.setAmountVnd(2_500_000L);

        InvoiceResponse resp = svc.createInvoice(req);

        assertEquals(2_500_000L, resp.getAmountVnd());
        assertEquals(InvoiceStatus.unpaid, resp.getStatus());
        verify(invRepo, times(1)).save(any());
    }

    // --- Task 20.13: pay-ownership authz ---

    @Test
    void payInvoiceAsCustomerRejectsNonOwner() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        OrderClient orderClient = mock(OrderClient.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();

        Invoice invoice = new Invoice();
        invoice.setInvoiceId(invoiceId);
        invoice.setOrderId(orderId);
        invoice.setStatus(InvoiceStatus.unpaid);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(orderClient.getOrderOwner(orderId)).thenReturn(owner);

        BillingService svc = new BillingService(invRepo, mock(AdjustmentRepository.class), orderClient, outbox);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.payInvoiceAsCustomer(invoiceId, attacker));
        assertEquals(ErrorCode.FORBIDDEN_RESOURCE, ex.getErrorCode());
        verify(outbox, never()).enqueue(anyString(), anyString()); // not paid
    }

    @Test
    void payInvoiceAsCustomerAllowsOwner() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        OrderClient orderClient = mock(OrderClient.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(outbox.enqueue(anyString(), anyString())).thenReturn(null);

        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        Invoice invoice = new Invoice();
        invoice.setInvoiceId(invoiceId);
        invoice.setOrderId(orderId);
        invoice.setStatus(InvoiceStatus.unpaid);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invRepo.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderClient.getOrderOwner(orderId)).thenReturn(owner);

        BillingService svc = new BillingService(invRepo, mock(AdjustmentRepository.class), orderClient, outbox);

        InvoiceResponse resp = svc.payInvoiceAsCustomer(invoiceId, owner);

        assertEquals(InvoiceStatus.paid, resp.getStatus());
        verify(outbox, times(1)).enqueue(eq("InvoicePaid"), anyString());
    }

    @Test
    void payInvoiceAsCustomerResolvesOwnerViaPolicyWhenPresent() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        OrderClient orderClient = mock(OrderClient.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID invoiceId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();

        Invoice invoice = new Invoice();
        invoice.setInvoiceId(invoiceId);
        invoice.setOrderId(UUID.randomUUID());
        invoice.setPolicyId(policyId);
        invoice.setStatus(InvoiceStatus.unpaid);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(orderClient.getPolicyOwner(policyId)).thenReturn(owner);

        BillingService svc = new BillingService(invRepo, mock(AdjustmentRepository.class), orderClient, outbox);

        assertThrows(ServiceException.class, () -> svc.payInvoiceAsCustomer(invoiceId, attacker));
        verify(orderClient, times(1)).getPolicyOwner(policyId);
        verify(orderClient, never()).getOrderOwner(any());
    }
}
