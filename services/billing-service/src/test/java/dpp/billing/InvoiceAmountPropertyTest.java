package dpp.billing;

import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.InvoiceResponse;
import dpp.billing.entity.Invoice;
import dpp.billing.entity.InvoiceStatus;
import dpp.billing.client.OrderClient;
import dpp.billing.repository.AdjustmentRepository;
import dpp.billing.repository.InvoiceRepository;
import dpp.billing.service.BillingService;
import dpp.billing.service.CreditService;
import dpp.common.outbox.OutboxPublisher;
import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 15")
class InvoiceAmountPropertyTest {

    @Property(tries = 100)
    void invoiceAmountEqualsLockedPremium(
            @ForAll @LongRange(min = 1, max = 100_000_000) long premium) {
        InvoiceRepository repo = mock(InvoiceRepository.class);
        when(repo.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        BillingService svc = new BillingService(repo, mock(AdjustmentRepository.class), mock(OrderClient.class), mock(OutboxPublisher.class), mock(CreditService.class));

        CreateInvoiceRequest req = new CreateInvoiceRequest();
        req.setOrderId(UUID.randomUUID());
        req.setAmountVnd(premium);

        InvoiceResponse resp = svc.createInvoice(req);

        assertEquals(premium, resp.getAmountVnd());
        assertEquals(InvoiceStatus.unpaid, resp.getStatus());

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(repo, times(1)).save(captor.capture());
        assertEquals(premium, captor.getValue().getAmountVnd());
    }

    @Property(tries = 100)
    void payingInvoiceEnqueuesInvoicePaidEvent(
            @ForAll @LongRange(min = 1, max = 100_000_000) long premium) {
        InvoiceRepository repo = mock(InvoiceRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(outbox.enqueue(anyString(), anyString())).thenReturn(null);
        BillingService svc = new BillingService(repo, mock(AdjustmentRepository.class), mock(OrderClient.class), outbox, mock(CreditService.class));

        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(invoiceId);
        invoice.setOrderId(UUID.randomUUID());
        invoice.setAmountVnd(premium);
        invoice.setStatus(InvoiceStatus.unpaid);
        when(repo.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(repo.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceResponse resp = svc.payInvoice(invoiceId);

        assertEquals(InvoiceStatus.paid, resp.getStatus());
        verify(outbox, times(1)).enqueue(eq("InvoicePaid"), anyString());
    }

    @Test
    void property15_sanity() {
        InvoiceRepository repo = mock(InvoiceRepository.class);
        when(repo.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        BillingService svc = new BillingService(repo, mock(AdjustmentRepository.class), mock(OrderClient.class), mock(OutboxPublisher.class), mock(CreditService.class));

        CreateInvoiceRequest req = new CreateInvoiceRequest();
        req.setOrderId(UUID.randomUUID());
        req.setAmountVnd(1_000_000L);

        InvoiceResponse resp = svc.createInvoice(req);
        assertEquals(1_000_000L, resp.getAmountVnd());
        assertEquals(InvoiceStatus.unpaid, resp.getStatus());
    }
}
