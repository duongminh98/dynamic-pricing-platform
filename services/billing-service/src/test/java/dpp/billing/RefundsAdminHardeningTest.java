package dpp.billing;

import dpp.billing.dto.PolicyBillingResponse;
import dpp.billing.dto.RefundResponse;
import dpp.billing.entity.*;
import dpp.billing.repository.PremiumCreditRepository;
import dpp.billing.repository.RefundRequestRepository;
import dpp.billing.service.BillingService;
import dpp.billing.service.CreditService;
import dpp.billing.service.RefundService;
import dpp.billing.client.OrderClient;
import dpp.billing.repository.AdjustmentRepository;
import dpp.billing.repository.InvoiceRepository;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.dto.PageResponse;
import dpp.common.outbox.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefundsAdminHardeningTest {

    private RefundService refundServiceWith(RefundRequestRepository refundRepo,
                                            PremiumCreditRepository creditRepo,
                                            OutboxPublisher outbox) {
        return new RefundService(refundRepo, creditRepo, outbox);
    }

    private RefundRequest pendingRefund(UUID refundId, UUID policyId, UUID customerId, UUID creditId, long amount) {
        RefundRequest r = new RefundRequest();
        r.setRefundId(refundId);
        r.setPolicyId(policyId);
        r.setCustomerId(customerId);
        r.setCreditId(creditId);
        r.setAmountVnd(amount);
        r.setStatus(RefundStatus.pending);
        r.setRequestedAt(OffsetDateTime.now());
        return r;
    }

    private PremiumCredit credit(UUID creditId, UUID policyId, UUID customerId, long original, long remaining, CreditStatus status) {
        PremiumCredit c = new PremiumCredit();
        c.setCreditId(creditId);
        c.setPolicyId(policyId);
        c.setCustomerId(customerId);
        c.setSourceEndorsementId(UUID.randomUUID());
        c.setOriginalAmountVnd(original);
        c.setRemainingAmountVnd(remaining);
        c.setStatus(status);
        c.setCreatedAt(OffsetDateTime.now());
        return c;
    }

    // ── T1: No manual refund creation — createRefund method removed ──

    @Test
    void t1_noManualCreateRefundMethod() {
        // Verify RefundService does not have a public createRefund method
        java.lang.reflect.Method[] methods = RefundService.class.getDeclaredMethods();
        for (java.lang.reflect.Method m : methods) {
            assertFalse(m.getName().equals("createRefund"),
                    "RefundService must not have a createRefund method (manual creation removed)");
        }
    }

    // ── T2: Admin list returns RefundResponse DTOs ──

    @Test
    void t2_adminListReturnsRefundResponsePage() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        RefundRequest r1 = pendingRefund(UUID.randomUUID(), policyId, customerId, null, 500_000L);
        RefundRequest r2 = pendingRefund(UUID.randomUUID(), policyId, customerId, null, 300_000L);
        Page<RefundRequest> page = new PageImpl<>(List.of(r1, r2), PageRequest.of(0, 20), 2);
        when(refundRepo.findFiltered(isNull(), isNull(), isNull(), any())).thenReturn(page);

        RefundService svc = refundServiceWith(refundRepo, mock(PremiumCreditRepository.class), mock(OutboxPublisher.class));
        PageResponse<RefundResponse> result = svc.listFiltered(null, null, null, PageRequest.of(0, 20));

        assertEquals(2, result.getContent().size());
        assertTrue(result.getContent().get(0) instanceof RefundResponse);
        assertEquals(500_000L, result.getContent().get(0).getAmountVnd());
        assertEquals(RefundStatus.pending, result.getContent().get(0).getStatus());
    }

    // ── T3: Complete refund — pending to completed ──

    @Test
    void t3_completeRefundTransitionsPendingToCompleted() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, UUID.randomUUID(), UUID.randomUUID(), null, 1_000_000L);
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));
        when(refundRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        RefundService svc = refundServiceWith(refundRepo, mock(PremiumCreditRepository.class), outbox);
        RefundResponse resp = svc.completeRefund(refundId, "PAY-REF-001", "admin-123", null);

        assertEquals(RefundStatus.completed, resp.getStatus());
        assertEquals("PAY-REF-001", resp.getPaymentReference());
        assertEquals("admin-123", resp.getCompletedBy());
        assertNotNull(resp.getCompletedAt());
        verify(outbox, times(1)).enqueue(eq("RefundCompleted"), anyString());
    }

    // ── T4: Complete refund — payment_reference mandatory ──

    @Test
    void t4_completeRefundRequiresPaymentReference() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, UUID.randomUUID(), UUID.randomUUID(), null, 1_000_000L);
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));

        RefundService svc = refundServiceWith(refundRepo, mock(PremiumCreditRepository.class), mock(OutboxPublisher.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.completeRefund(refundId, "", "admin-123", null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    // ── T5: Complete refund — idempotent (already completed returns same) ──

    @Test
    void t5_completeRefundIdempotentWhenAlreadyCompleted() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, UUID.randomUUID(), UUID.randomUUID(), null, 1_000_000L);
        r.setStatus(RefundStatus.completed);
        r.setPaymentReference("PAY-EXISTING");
        r.setCompletedBy("admin-old");
        r.setCompletedAt(OffsetDateTime.now());
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));

        RefundService svc = refundServiceWith(refundRepo, mock(PremiumCreditRepository.class), outbox);
        RefundResponse resp = svc.completeRefund(refundId, "PAY-NEW", "admin-new", null);

        assertEquals(RefundStatus.completed, resp.getStatus());
        assertEquals("PAY-EXISTING", resp.getPaymentReference());
        assertEquals("admin-old", resp.getCompletedBy());
        verify(refundRepo, never()).save(any());
        verify(outbox, never()).enqueue(any(), any());
    }

    // ── T6: Reject refund — pending to rejected ──

    @Test
    void t6_rejectRefundTransitionsPendingToRejected() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, UUID.randomUUID(), UUID.randomUUID(), null, 1_000_000L);
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));
        when(refundRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        RefundService svc = refundServiceWith(refundRepo, mock(PremiumCreditRepository.class), outbox);
        RefundResponse resp = svc.rejectRefund(refundId, "Invalid refund request");

        assertEquals(RefundStatus.rejected, resp.getStatus());
        assertEquals("Invalid refund request", resp.getNote());
        verify(outbox, times(1)).enqueue(eq("RefundRejected"), anyString());
    }

    // ── T7: Reject refund — reason mandatory ──

    @Test
    void t7_rejectRefundRequiresReason() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, UUID.randomUUID(), UUID.randomUUID(), null, 1_000_000L);
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));

        RefundService svc = refundServiceWith(refundRepo, mock(PremiumCreditRepository.class), mock(OutboxPublisher.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.rejectRefund(refundId, ""));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    // ── T8: Reject refund — credit restoration (refunded -> open) ──

    @Test
    void t8_rejectRefundRestoresCreditToOpenWhenRemainingEqualsOriginal() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        PremiumCreditRepository creditRepo = mock(PremiumCreditRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID creditId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PremiumCredit c = credit(creditId, policyId, customerId, 1_000_000L, 1_000_000L, CreditStatus.refunded);
        when(creditRepo.findById(creditId)).thenReturn(Optional.of(c));

        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, policyId, customerId, creditId, 1_000_000L);
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));
        when(refundRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        RefundService svc = refundServiceWith(refundRepo, creditRepo, outbox);
        svc.rejectRefund(refundId, "Not eligible");

        assertEquals(CreditStatus.open, c.getStatus());
        verify(creditRepo, times(1)).save(c);
    }

    // ── T8b: Reject refund — credit restoration (refunded -> partially_applied) ──

    @Test
    void t8b_rejectRefundRestoresCreditToPartiallyAppliedWhenRemainingLessThanOriginal() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        PremiumCreditRepository creditRepo = mock(PremiumCreditRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID creditId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PremiumCredit c = credit(creditId, policyId, customerId, 1_000_000L, 400_000L, CreditStatus.refunded);
        when(creditRepo.findById(creditId)).thenReturn(Optional.of(c));

        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, policyId, customerId, creditId, 400_000L);
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));
        when(refundRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        RefundService svc = refundServiceWith(refundRepo, creditRepo, outbox);
        svc.rejectRefund(refundId, "Not eligible");

        assertEquals(CreditStatus.partially_applied, c.getStatus());
    }

    // ── T8c: Reject refund — credit restoration (refunded -> exhausted) ──

    @Test
    void t8c_rejectRefundRestoresCreditToExhaustedWhenRemainingZero() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        PremiumCreditRepository creditRepo = mock(PremiumCreditRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID creditId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PremiumCredit c = credit(creditId, policyId, customerId, 1_000_000L, 0L, CreditStatus.refunded);
        when(creditRepo.findById(creditId)).thenReturn(Optional.of(c));

        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, policyId, customerId, creditId, 0L);
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));
        when(refundRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        RefundService svc = refundServiceWith(refundRepo, creditRepo, outbox);
        svc.rejectRefund(refundId, "Not eligible");

        assertEquals(CreditStatus.exhausted, c.getStatus());
    }

    // ── T9: Reject refund — only pending can be rejected ──

    @Test
    void t9_rejectRefundFailsOnNonPendingStatus() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, UUID.randomUUID(), UUID.randomUUID(), null, 1_000_000L);
        r.setStatus(RefundStatus.completed);
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));

        RefundService svc = refundServiceWith(refundRepo, mock(PremiumCreditRepository.class), mock(OutboxPublisher.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.rejectRefund(refundId, "reason"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    // ── T10: Customer billing view includes refunds ──

    @Test
    void t10_policyBillingIncludesRefundsList() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        AdjustmentRepository adjRepo = mock(AdjustmentRepository.class);
        OrderClient orderClient = mock(OrderClient.class);
        RefundService refundService = mock(RefundService.class);
        CreditService creditService = mock(CreditService.class);

        UUID policyId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(orderClient.getPolicyOwner(policyId)).thenReturn(owner);
        when(invRepo.findByPolicyIdOrderByCreatedAtAsc(policyId)).thenReturn(List.of());
        when(adjRepo.findByPolicyIdOrderByCreatedAtAsc(policyId)).thenReturn(List.of());
        when(creditService.getCreditsByPolicy(policyId)).thenReturn(List.of());

        RefundResponse refundResp = new RefundResponse();
        refundResp.setRefundId(UUID.randomUUID());
        refundResp.setPolicyId(policyId);
        refundResp.setAmountVnd(500_000L);
        refundResp.setStatus(RefundStatus.pending);
        when(refundService.listByPolicy(policyId)).thenReturn(List.of(refundResp));

        BillingService svc = new BillingService(invRepo, adjRepo, orderClient, mock(OutboxPublisher.class), creditService, refundService);
        PolicyBillingResponse resp = svc.getPolicyBilling(policyId, owner);

        assertNotNull(resp.getRefunds());
        assertEquals(1, resp.getRefunds().size());
        assertEquals(500_000L, resp.getRefunds().get(0).getAmountVnd());
    }

    // ── T11: Auto refund creation on policy cancellation ──

    @Test
    void t11_autoRefundCreatedForCreditWithRemainingOnCancellation() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        PremiumCreditRepository creditRepo = mock(PremiumCreditRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID creditId = UUID.randomUUID();
        PremiumCredit c = credit(creditId, policyId, customerId, 1_000_000L, 600_000L, CreditStatus.open);
        when(creditRepo.findByPolicyIdAndRemainingAmountVndGreaterThan(policyId, 0L)).thenReturn(List.of(c));
        when(refundRepo.save(any())).thenAnswer(a -> a.getArgument(0));
        when(creditRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        RefundService svc = refundServiceWith(refundRepo, creditRepo, outbox);
        svc.createRefundsForCancelledPolicy(policyId, customerId);

        verify(refundRepo, times(1)).save(any());
        assertEquals(CreditStatus.refunded, c.getStatus());
        verify(outbox, times(1)).enqueue(eq("RefundRequested"), anyString());
    }

    // ── T12: Auto refund — idempotent (skip if credit already refunded) ──

    @Test
    void t12_autoRefundSkipsAlreadyRefundedCredits() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        PremiumCreditRepository creditRepo = mock(PremiumCreditRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID creditId = UUID.randomUUID();
        PremiumCredit c = credit(creditId, policyId, customerId, 1_000_000L, 600_000L, CreditStatus.refunded);
        when(creditRepo.findByPolicyIdAndRemainingAmountVndGreaterThan(policyId, 0L)).thenReturn(List.of(c));

        RefundService svc = refundServiceWith(refundRepo, creditRepo, outbox);
        svc.createRefundsForCancelledPolicy(policyId, customerId);

        verify(refundRepo, never()).save(any());
        verify(outbox, never()).enqueue(any(), any());
    }

    // ── T13: RefundRejected event published on reject ──

    @Test
    void t13_rejectRefundPublishesRefundRejectedEvent() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, UUID.randomUUID(), UUID.randomUUID(), null, 1_000_000L);
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));
        when(refundRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        RefundService svc = refundServiceWith(refundRepo, mock(PremiumCreditRepository.class), outbox);
        svc.rejectRefund(refundId, "Duplicate request");

        verify(outbox, times(1)).enqueue(eq("RefundRejected"), anyString());
    }

    // ── T14: RefundCompleted event published on complete ──

    @Test
    void t14_completeRefundPublishesRefundCompletedEvent() {
        RefundRequestRepository refundRepo = mock(RefundRequestRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        UUID refundId = UUID.randomUUID();
        RefundRequest r = pendingRefund(refundId, UUID.randomUUID(), UUID.randomUUID(), null, 1_000_000L);
        when(refundRepo.findById(refundId)).thenReturn(Optional.of(r));
        when(refundRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        RefundService svc = refundServiceWith(refundRepo, mock(PremiumCreditRepository.class), outbox);
        svc.completeRefund(refundId, "PAY-001", "admin-456", "Processed via bank transfer");

        verify(outbox, times(1)).enqueue(eq("RefundCompleted"), anyString());
    }
}
