package dpp.billing;

import dpp.billing.dto.PolicyBillingResponse;
import dpp.billing.entity.Adjustment;
import dpp.billing.entity.AdjustmentReason;
import dpp.billing.entity.AdjustmentType;
import dpp.billing.entity.Invoice;
import dpp.billing.entity.InvoiceStatus;
import dpp.billing.repository.AdjustmentRepository;
import dpp.billing.repository.InvoiceRepository;
import dpp.billing.service.BillingService;
import dpp.billing.service.CreditService;
import dpp.billing.service.RefundService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingServiceCoverageTest {

    @Test
    void getPolicyBillingReturnsDataForOwner() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        AdjustmentRepository adjRepo = mock(AdjustmentRepository.class);
        UUID policyId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        Invoice inv = new Invoice();
        inv.setInvoiceId(UUID.randomUUID());
        inv.setOrderId(UUID.randomUUID());
        inv.setPolicyId(policyId);
        inv.setCustomerId(owner);
        inv.setAmountVnd(1_000_000L);
        inv.setStatus(InvoiceStatus.paid);
        inv.setCreatedAt(OffsetDateTime.now());
        when(invRepo.findByPolicyIdOrderByCreatedAtAsc(policyId)).thenReturn(List.of(inv));

        Adjustment adj = new Adjustment();
        adj.setAdjustmentId(UUID.randomUUID());
        adj.setPolicyId(policyId);
        adj.setType(AdjustmentType.additional_charge);
        adj.setAmountVnd(200_000L);
        adj.setReason(AdjustmentReason.endorsement);
        adj.setCreatedAt(OffsetDateTime.now());
        when(adjRepo.findByPolicyIdOrderByCreatedAtAsc(policyId)).thenReturn(List.of(adj));

        BillingService svc = new BillingService(invRepo, adjRepo, mock(OutboxPublisher.class), mock(CreditService.class), mock(RefundService.class));
        PolicyBillingResponse resp = svc.getPolicyBilling(policyId, owner);

        assertEquals(1, resp.getInvoices().size());
        assertEquals(1, resp.getAdjustments().size());
        assertEquals(1_000_000L, resp.getInvoices().get(0).getAmountVnd());
    }

    @Test
    void getPolicyBillingRejectsNonOwner() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        AdjustmentRepository adjRepo = mock(AdjustmentRepository.class);
        UUID policyId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();

        BillingService svc = new BillingService(invRepo, adjRepo, mock(OutboxPublisher.class), mock(CreditService.class), mock(RefundService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getPolicyBilling(policyId, attacker));
        assertEquals(ErrorCode.FORBIDDEN_RESOURCE, ex.getErrorCode());
    }

    @Test
    void getPolicyBillingRejectsWhenOwnerIsNull() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        AdjustmentRepository adjRepo = mock(AdjustmentRepository.class);
        UUID policyId = UUID.randomUUID();

        BillingService svc = new BillingService(invRepo, adjRepo, mock(OutboxPublisher.class), mock(CreditService.class), mock(RefundService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getPolicyBilling(policyId, UUID.randomUUID()));
        assertEquals(ErrorCode.FORBIDDEN_RESOURCE, ex.getErrorCode());
    }

    @Test
    void payInvoiceRejectsUnknownInvoice() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID invoiceId = UUID.randomUUID();
        when(invRepo.findById(invoiceId)).thenReturn(Optional.empty());

        BillingService svc = new BillingService(invRepo, mock(AdjustmentRepository.class),
                mock(OutboxPublisher.class), mock(CreditService.class), mock(RefundService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.payInvoice(invoiceId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void payInvoiceAsCustomerRejectsUnknownInvoice() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID invoiceId = UUID.randomUUID();
        when(invRepo.findById(invoiceId)).thenReturn(Optional.empty());

        BillingService svc = new BillingService(invRepo, mock(AdjustmentRepository.class),
                mock(OutboxPublisher.class), mock(CreditService.class), mock(RefundService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.payInvoiceAsCustomer(invoiceId, UUID.randomUUID()));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void payInvoiceAsCustomerRejectsWhenOwnerIsNull() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Invoice inv = new Invoice();
        inv.setInvoiceId(invoiceId);
        inv.setOrderId(orderId);
        inv.setStatus(InvoiceStatus.unpaid);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(inv));

        BillingService svc = new BillingService(invRepo, mock(AdjustmentRepository.class), mock(OutboxPublisher.class), mock(CreditService.class), mock(RefundService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.payInvoiceAsCustomer(invoiceId, UUID.randomUUID()));
        assertEquals(ErrorCode.FORBIDDEN_RESOURCE, ex.getErrorCode());
    }
}

