package dpp.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.order.dto.CancelRequest;
import dpp.order.dto.CancelResponse;
import dpp.order.dto.RenewalPreviewResponse;
import dpp.order.dto.RenewalResponse;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * I1-I13: Renewal & Cancellation Hardening test plan.
 */
class RenewalCancellationHardeningTest {

    private static final String SUBJECT = "hardening-subject";
    private static final UUID CUSTOMER_ID = UUID.nameUUIDFromBytes(SUBJECT.getBytes());

    private Policy activePolicy() {
        Policy p = new Policy();
        p.setPolicyId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setCustomerId(CUSTOMER_ID);
        p.setProductId("MOTOR_BASIC");
        p.setStatus(PolicyStatus.active);
        OffsetDateTime eff = OffsetDateTime.now().minusDays(30);
        p.setPolicyEffectiveDate(eff);
        p.setPolicyExpirationDate(eff.plus(365, ChronoUnit.DAYS));
        p.setFinalPremiumVnd(1_000_000L);
        p.setRenewalNumber(0);
        p.setRenewal(false);
        p.setYearsSinceFirstPolicy(0);
        p.setPolicyCountPrior(0);
        p.setAssetKey("asset-key-1");
        p.setCreatedAt(OffsetDateTime.now());
        return p;
    }

    private ExposureSegment baseSegment(Policy p) {
        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(p.getPolicyId());
        seg.setExposureSegmentSeq(0);
        seg.setSegmentStart(p.getPolicyEffectiveDate());
        seg.setSegmentEnd(p.getPolicyExpirationDate());
        seg.setCoverageAmountVnd(300_000_000L);
        seg.setDeductibleVnd(5_000_000L);
        seg.setRiskSnapshot("{\"age\":30,\"vehicle_value_vnd\":400000000}");
        return seg;
    }

    private PolicyLifecycleService newService(PolicyRepository repo, ExposureSegmentRepository segRepo,
                                              PricingClient pricing, BillingClient billing,
                                              OutboxPublisher outbox) {
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(any())).thenReturn(List.of());
        when(segRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new PolicyLifecycleService(repo, segRepo,
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class),
                pricing, billing, outbox);
    }

    // ── I1: Renewal on non-active policy → POLICY_NOT_MODIFIABLE ──
    @Test
    void i1_renewOnNonActivePolicyThrows() {
        Policy policy = activePolicy();
        policy.setStatus(PolicyStatus.expired);
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));

        PolicyLifecycleService svc = newService(repo, mock(ExposureSegmentRepository.class),
                mock(PricingClient.class), mock(BillingClient.class), mock(OutboxPublisher.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.renew(policy.getPolicyId(), SUBJECT));
        assertEquals(ErrorCode.POLICY_NOT_MODIFIABLE, ex.getErrorCode());
    }

    // ── I2: Duplicate renewal → RENEWAL_IN_PROGRESS ──
    @Test
    void i2_duplicateRenewalThrows() {
        Policy old = activePolicy();
        Policy existing = activePolicy();
        existing.setPolicyId(UUID.randomUUID());
        existing.setRenewalNumber(1);
        existing.setStatus(PolicyStatus.pending_payment);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(old.getPolicyId())).thenReturn(Optional.of(old));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByOrderIdAndStatusIn(eq(old.getOrderId()), anyList()))
                .thenReturn(List.of(old, existing));

        BillingClient billing = mock(BillingClient.class);
        when(billing.applyCreditAndQuote(any(), anyLong()))
                .thenReturn(Map.of("credit_applied_vnd", 0L, "net_due_vnd", 0L));

        PolicyLifecycleService svc = newService(repo, mock(ExposureSegmentRepository.class),
                mock(PricingClient.class), billing, mock(OutboxPublisher.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.renew(old.getPolicyId(), SUBJECT));
        assertEquals(ErrorCode.RENEWAL_IN_PROGRESS, ex.getErrorCode());
    }

    // ── I3: Renewal re-rate with full profile ──
    @Test
    void i3_renewalReRateWithFullProfile() {
        Policy old = activePolicy();
        ExposureSegment oldSeg = baseSegment(old);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(old.getPolicyId())).thenReturn(Optional.of(old));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 1_750_000L));

        BillingClient billing = mock(BillingClient.class);
        when(billing.applyCreditAndQuote(any(), anyLong()))
                .thenReturn(Map.of("credit_applied_vnd", 1_750_000L, "net_due_vnd", 0L));

        PolicyLifecycleService svc = newService(repo, segRepo, pricing, billing, mock(OutboxPublisher.class));

        // Override default mock after newService sets it
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(old.getPolicyId()))
                .thenReturn(List.of(oldSeg));

        RenewalResponse resp = svc.renew(old.getPolicyId(), SUBJECT);

        assertEquals(1_750_000L, resp.getRenewedPremiumVnd());
        assertEquals(1, resp.getRenewalNumber());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> profileCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pricing).rerate(eq("MOTOR_BASIC"), profileCaptor.capture());
        Map<String, Object> profile = profileCaptor.getValue();
        assertEquals(30, ((Number) profile.get("age")).intValue());
        assertEquals(true, profile.get("is_renewal"));
    }

    // ── I4: Renewal net-off credit ──
    @Test
    void i4_renewalNetOffCredit() {
        Policy old = activePolicy();
        ExposureSegment oldSeg = baseSegment(old);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(old.getPolicyId())).thenReturn(Optional.of(old));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 2_000_000L));

        BillingClient billing = mock(BillingClient.class);
        when(billing.applyCreditAndQuote(any(), anyLong()))
                .thenReturn(Map.of("credit_applied_vnd", 500_000L, "net_due_vnd", 1_500_000L));

        PolicyLifecycleService svc = newService(repo, segRepo, pricing, billing, mock(OutboxPublisher.class));

        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(old.getPolicyId()))
                .thenReturn(List.of(oldSeg));

        RenewalResponse resp = svc.renew(old.getPolicyId(), SUBJECT);

        assertEquals(500_000L, resp.getCreditAppliedVnd());
        assertEquals(1_500_000L, resp.getNetDueVnd());
    }

    // ── I5: Renewal gate-by-payment (pending_payment status) ──
    @Test
    void i5_renewalGateByPaymentPendingStatus() {
        Policy old = activePolicy();
        ExposureSegment oldSeg = baseSegment(old);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(old.getPolicyId())).thenReturn(Optional.of(old));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 2_000_000L));

        BillingClient billing = mock(BillingClient.class);
        when(billing.applyCreditAndQuote(any(), anyLong()))
                .thenReturn(Map.of("credit_applied_vnd", 0L, "net_due_vnd", 2_000_000L));
        UUID invoiceId = UUID.randomUUID();
        when(billing.createRenewalInvoice(any(), any(), anyLong(), any()))
                .thenReturn(Map.of("invoice_id", invoiceId.toString()));

        PolicyLifecycleService svc = newService(repo, segRepo, pricing, billing, mock(OutboxPublisher.class));

        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(old.getPolicyId()))
                .thenReturn(List.of(oldSeg));

        RenewalResponse resp = svc.renew(old.getPolicyId(), SUBJECT);

        assertTrue(resp.isPaymentRequired());
        assertEquals(PolicyStatus.pending_payment, resp.getStatus());
        assertEquals(invoiceId, resp.getInvoiceId());
    }

    // ── I6: Renewal segment stamping ──
    @Test
    void i6_renewalSegmentStamping() {
        Policy old = activePolicy();
        ExposureSegment oldSeg = baseSegment(old);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(old.getPolicyId())).thenReturn(Optional.of(old));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 1_750_000L));

        BillingClient billing = mock(BillingClient.class);
        when(billing.applyCreditAndQuote(any(), anyLong()))
                .thenReturn(Map.of("credit_applied_vnd", 1_750_000L, "net_due_vnd", 0L));

        PolicyLifecycleService svc = newService(repo, segRepo, pricing, billing, mock(OutboxPublisher.class));

        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(old.getPolicyId()))
                .thenReturn(List.of(oldSeg));

        svc.renew(old.getPolicyId(), SUBJECT);

        ArgumentCaptor<ExposureSegment> segCaptor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo).save(segCaptor.capture());
        ExposureSegment newSeg = segCaptor.getValue();
        assertEquals(0, newSeg.getExposureSegmentSeq());
        assertEquals(300_000_000L, newSeg.getCoverageAmountVnd());
        assertEquals(5_000_000L, newSeg.getDeductibleVnd());
        assertNotNull(newSeg.getRiskSnapshot());
        assertTrue(newSeg.getRiskSnapshot().contains("\"is_renewal\":true"));
    }

    // ── I7: Renewal preview endpoint ──
    @Test
    void i7_renewalPreview() {
        Policy old = activePolicy();
        ExposureSegment oldSeg = baseSegment(old);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(old.getPolicyId())).thenReturn(Optional.of(old));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 1_750_000L));

        BillingClient billing = mock(BillingClient.class);
        when(billing.applyCreditAndQuote(any(), anyLong()))
                .thenReturn(Map.of("credit_applied_vnd", 500_000L, "net_due_vnd", 1_250_000L));

        PolicyLifecycleService svc = newService(repo, segRepo, pricing, billing, mock(OutboxPublisher.class));

        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(old.getPolicyId()))
                .thenReturn(List.of(oldSeg));

        RenewalPreviewResponse resp = svc.previewRenewal(old.getPolicyId(), SUBJECT);

        assertEquals(1_000_000L, resp.getCurrentPremiumVnd());
        assertEquals(1_750_000L, resp.getRenewedPremiumVnd());
        assertEquals(500_000L, resp.getCreditAppliedVnd());
        assertEquals(1_250_000L, resp.getNetDueVnd());
        assertEquals(300_000_000L, resp.getCoverageAmountVnd());
        assertEquals(5_000_000L, resp.getDeductibleVnd());
        assertTrue(resp.isPaymentRequired());
        assertEquals(1, resp.getRenewalNumber());
    }

    // ── I8: Cancel with backdate → CANCEL_DATE_OUT_OF_RANGE ──
    @Test
    void i8_cancelBackdateThrows() {
        Policy policy = activePolicy();
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));

        PolicyLifecycleService svc = newService(repo, mock(ExposureSegmentRepository.class),
                mock(PricingClient.class), mock(BillingClient.class), mock(OutboxPublisher.class));

        CancelRequest req = new CancelRequest();
        req.setCancelDate(OffsetDateTime.now().minusDays(1));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.cancel(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.CANCEL_DATE_OUT_OF_RANGE, ex.getErrorCode());
    }

    // ── I9: Cancel with future date beyond expiration → CANCEL_DATE_OUT_OF_RANGE ──
    @Test
    void i9_cancelFutureDateThrows() {
        Policy policy = activePolicy();
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));

        PolicyLifecycleService svc = newService(repo, mock(ExposureSegmentRepository.class),
                mock(PricingClient.class), mock(BillingClient.class), mock(OutboxPublisher.class));

        CancelRequest req = new CancelRequest();
        req.setCancelDate(policy.getPolicyExpirationDate().plusDays(1));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.cancel(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.CANCEL_DATE_OUT_OF_RANGE, ex.getErrorCode());
    }

    // ── I10: Cancel response shape ──
    @Test
    void i10_cancelResponseShape() {
        Policy policy = activePolicy();
        ExposureSegment seg = baseSegment(policy);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        OutboxPublisher outbox = mock(OutboxPublisher.class);

        PolicyLifecycleService svc = newService(repo, segRepo,
                mock(PricingClient.class), mock(BillingClient.class), outbox);

        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(seg));

        CancelRequest req = new CancelRequest();
        OffsetDateTime cancelDate = OffsetDateTime.now().plusDays(10);
        req.setCancelDate(cancelDate);

        CancelResponse resp = svc.cancel(policy.getPolicyId(), req, SUBJECT);

        assertEquals(policy.getPolicyId(), resp.getPolicyId());
        assertEquals(PolicyStatus.cancelled, resp.getStatus());
        assertEquals(cancelDate, resp.getCancelDate());
        assertTrue(resp.getRemainingDays() > 0);
        assertTrue(resp.getTermDays() > 0);
    }

    // ── I11: Admin cancel backdate → CANCEL_DATE_OUT_OF_RANGE ──
    @Test
    void i11_adminCancelBackdateThrows() {
        Policy policy = activePolicy();
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(any())).thenReturn(List.of());
        when(segRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo, segRepo,
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class),
                mock(PricingClient.class), mock(BillingClient.class), mock(OutboxPublisher.class));

        OffsetDateTime pastDate = OffsetDateTime.now().minusDays(1);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.adminCancelPolicy(policy.getPolicyId(), pastDate));
        assertEquals(ErrorCode.CANCEL_DATE_OUT_OF_RANGE, ex.getErrorCode());
    }

    // ── I12: PolicyCancelled event includes refundable_credit_vnd ──
    @Test
    void i12_policyCancelledEventIncludesRefundableCredit() {
        Policy policy = activePolicy();
        ExposureSegment seg = baseSegment(policy);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        OutboxPublisher outbox = mock(OutboxPublisher.class);

        PolicyLifecycleService svc = newService(repo, segRepo,
                mock(PricingClient.class), mock(BillingClient.class), outbox);

        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(seg));

        CancelRequest req = new CancelRequest();
        req.setCancelDate(OffsetDateTime.now().plusDays(10));

        svc.cancel(policy.getPolicyId(), req, SUBJECT);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("PolicyCancelled"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"refundable_credit_vnd\""), "event must include refundable_credit_vnd");
        assertTrue(payload.contains("\"remaining_days\""), "event must include remaining_days");
        assertTrue(payload.contains("\"term_days\""), "event must include term_days");
    }

    // ── I13: Renewal notification message (payment_required=true → RENEWAL_SUBMITTED) ──
    @Test
    void i13_renewalNotificationPaymentRequired() {
        Policy old = activePolicy();
        ExposureSegment oldSeg = baseSegment(old);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(old.getPolicyId())).thenReturn(Optional.of(old));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 2_000_000L));

        BillingClient billing = mock(BillingClient.class);
        when(billing.applyCreditAndQuote(any(), anyLong()))
                .thenReturn(Map.of("credit_applied_vnd", 0L, "net_due_vnd", 2_000_000L));
        when(billing.createRenewalInvoice(any(), any(), anyLong(), any()))
                .thenReturn(Map.of("invoice_id", UUID.randomUUID().toString()));

        OutboxPublisher outbox = mock(OutboxPublisher.class);

        PolicyLifecycleService svc = newService(repo, segRepo, pricing, billing, outbox);

        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(old.getPolicyId()))
                .thenReturn(List.of(oldSeg));

        RenewalResponse resp = svc.renew(old.getPolicyId(), SUBJECT);

        assertTrue(resp.isPaymentRequired());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("PolicyRenewed"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"payment_required\":true"), "event must carry payment_required=true");
        assertTrue(payload.contains("\"renewed_premium_vnd\":2000000"), "event must carry renewed_premium_vnd");
        assertTrue(payload.contains("\"previous_policy_id\""), "event must carry previous_policy_id");
        assertTrue(payload.contains("\"invoice_id\""), "event must carry invoice_id when payment required");
    }
}
