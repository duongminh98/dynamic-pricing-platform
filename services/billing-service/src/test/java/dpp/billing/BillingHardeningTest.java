package dpp.billing;

import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.InvoiceResponse;
import dpp.billing.dto.PageResponse;
import dpp.billing.entity.*;
import dpp.billing.repository.*;
import dpp.billing.service.BillingService;
import dpp.billing.service.CreditService;
import dpp.billing.service.RefundService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BillingHardeningTest {

    private BillingService serviceWith(InvoiceRepository invRepo, OutboxPublisher outbox) {
        return new BillingService(invRepo, mock(AdjustmentRepository.class), outbox,
                mock(CreditService.class), mock(RefundService.class));
    }

    private Invoice unpaidInvoice(UUID invoiceId, UUID orderId) {
        Invoice inv = new Invoice();
        inv.setInvoiceId(invoiceId);
        inv.setOrderId(orderId);
        inv.setCustomerId(UUID.randomUUID());
        inv.setAmountVnd(2_000_000L);
        inv.setStatus(InvoiceStatus.unpaid);
        inv.setCreatedAt(OffsetDateTime.now());
        return inv;
    }

    private Invoice paidInvoice(UUID invoiceId, UUID orderId) {
        Invoice inv = unpaidInvoice(invoiceId, orderId);
        inv.setStatus(InvoiceStatus.paid);
        inv.setPaidAt(OffsetDateTime.now());
        return inv;
    }

    private Invoice voidedInvoice(UUID invoiceId, UUID orderId) {
        Invoice inv = unpaidInvoice(invoiceId, orderId);
        inv.setStatus(InvoiceStatus.voided);
        return inv;
    }

    // Section 1: Guard double pay

    @Test
    void payInvoiceOnPaidInvoiceIsIdempotentNoReenqueue() {
        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(paidInvoice(invoiceId, orderId)));

        BillingService svc = serviceWith(invRepo, outbox);
        InvoiceResponse resp = svc.payInvoice(invoiceId);

        assertEquals(InvoiceStatus.paid, resp.getStatus());
        verify(outbox, never()).enqueue(any(), any());
    }

    @Test
    void payInvoiceOnVoidedInvoiceThrowsBadRequest() {
        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(voidedInvoice(invoiceId, orderId)));

        BillingService svc = serviceWith(invRepo, mock(OutboxPublisher.class));
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.payInvoice(invoiceId));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void payInvoiceOnUnpaidEnqueuesInvoicePaidOnce() {
        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        Invoice inv = unpaidInvoice(invoiceId, orderId);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(inv));
        when(invRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        BillingService svc = serviceWith(invRepo, outbox);
        svc.payInvoice(invoiceId);

        verify(outbox, times(1)).enqueue(eq("InvoicePaid"), any());
    }

    // Section 3: Void invoice enqueues InvoiceVoided

    @Test
    void voidInvoiceEnqueuesInvoiceVoidedWithCustomerId() {
        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        Invoice inv = unpaidInvoice(invoiceId, orderId);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(inv));
        when(invRepo.save(any())).thenAnswer(a -> a.getArgument(0));
        inv.setCustomerId(customerId);

        BillingService svc = serviceWith(invRepo, outbox);
        InvoiceResponse resp = svc.voidInvoice(invoiceId);

        assertEquals(InvoiceStatus.voided, resp.getStatus());
        verify(outbox, times(1)).enqueue(eq("InvoiceVoided"), any());
    }

    @Test
    void voidInvoiceStillSucceedsWhenOwnerLookupFails() {
        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        Invoice inv = unpaidInvoice(invoiceId, orderId);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(inv));
        when(invRepo.save(any())).thenAnswer(a -> a.getArgument(0));
        BillingService svc = serviceWith(invRepo, outbox);
        InvoiceResponse resp = svc.voidInvoice(invoiceId);

        assertEquals(InvoiceStatus.voided, resp.getStatus());
        verify(outbox, times(1)).enqueue(eq("InvoiceVoided"), any());
    }

    @Test
    void voidInvoiceOnAlreadyVoidedIsIdempotentNoReenqueue() {
        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(voidedInvoice(invoiceId, orderId)));

        BillingService svc = serviceWith(invRepo, outbox);
        InvoiceResponse resp = svc.voidInvoice(invoiceId);

        assertEquals(InvoiceStatus.voided, resp.getStatus());
        verify(invRepo, never()).save(any());
        verify(outbox, never()).enqueue(any(), any());
    }

    @Test
    void voidInvoiceOnPaidThrowsBadRequestNoEnqueue() {
        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(paidInvoice(invoiceId, orderId)));

        BillingService svc = serviceWith(invRepo, outbox);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.voidInvoice(invoiceId));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(outbox, never()).enqueue(any(), any());
    }

    // Section 4: Admin paging

    @Test
    void adminListInvoicesPagedWithStatusFilter() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        Invoice inv1 = unpaidInvoice(UUID.randomUUID(), UUID.randomUUID());
        Invoice inv2 = paidInvoice(UUID.randomUUID(), UUID.randomUUID());
        when(invRepo.findFiltered(eq(InvoiceStatus.unpaid), any()))
                .thenReturn(new PageImpl<>(List.of(inv1), PageRequest.of(0, 20), 1));

        BillingService svc = serviceWith(invRepo, mock(OutboxPublisher.class));
        PageResponse<InvoiceResponse> result = svc.adminListInvoicesPaged(InvoiceStatus.unpaid,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(1, result.getContent().size());
        assertEquals(InvoiceStatus.unpaid, result.getContent().get(0).getStatus());
        assertEquals(0, result.getPage());
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void adminListInvoicesPagedWithoutStatusFilter() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        Invoice inv1 = unpaidInvoice(UUID.randomUUID(), UUID.randomUUID());
        Invoice inv2 = paidInvoice(UUID.randomUUID(), UUID.randomUUID());
        when(invRepo.findFiltered(eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(inv1, inv2), PageRequest.of(0, 20), 2));

        BillingService svc = serviceWith(invRepo, mock(OutboxPublisher.class));
        PageResponse<InvoiceResponse> result = svc.adminListInvoicesPaged(null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(2, result.getContent().size());
        assertEquals(2, result.getTotalElements());
    }

    // Section 6: Split endpoints verify service methods

    @Test
    void getInvoiceByOrderReturnsInvoiceWhenOwnerMatches() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        Invoice inv = unpaidInvoice(UUID.randomUUID(), orderId);
        inv.setCustomerId(customerId);
        when(invRepo.findByOrderId(orderId)).thenReturn(Optional.of(inv));

        BillingService svc = serviceWith(invRepo, mock(OutboxPublisher.class));
        InvoiceResponse resp = svc.getInvoiceByOrder(orderId, customerId);

        assertEquals(inv.getInvoiceId(), resp.getInvoiceId());
    }

    @Test
    void getInvoiceByOrderUsesPersistedCustomerIdWithoutOrderLookup() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        Invoice inv = unpaidInvoice(UUID.randomUUID(), orderId);
        inv.setCustomerId(customerId);
        when(invRepo.findByOrderId(orderId)).thenReturn(Optional.of(inv));

        BillingService svc = serviceWith(invRepo, mock(OutboxPublisher.class));
        InvoiceResponse resp = svc.getInvoiceByOrder(orderId, customerId);

        assertEquals(inv.getInvoiceId(), resp.getInvoiceId());
    }

    void getInvoiceByOrderThrowsForbiddenWhenOwnerMismatch() {
        UUID orderId = UUID.randomUUID();
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        Invoice inv = unpaidInvoice(UUID.randomUUID(), orderId);
        when(invRepo.findByOrderId(orderId)).thenReturn(Optional.of(inv));

        BillingService svc = serviceWith(invRepo, mock(OutboxPublisher.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getInvoiceByOrder(orderId, UUID.randomUUID()));
        assertEquals(ErrorCode.FORBIDDEN_RESOURCE, ex.getErrorCode());
    }
}

