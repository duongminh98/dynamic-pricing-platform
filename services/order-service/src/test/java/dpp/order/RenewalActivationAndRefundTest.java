package dpp.order;

import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.order.consumer.InvoicePaidListener;
import dpp.order.dto.CancelRequest;
import dpp.order.dto.CancelResponse;
import dpp.order.dto.RenewalResponse;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyIssuanceService;
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
 * C1-C6: Renewal activation + cancel refund enrich tests.
 */
class RenewalActivationAndRefundTest {

    private static final String SUBJECT = "activation-subject";
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

    /**
     * C1: Renewal end-to-end: renew() → pending_payment → simulate InvoicePaid → active + notification.
     */
    @Test
    void c1_renewalEndToEndActivation() {
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

        OutboxPublisher outbox = mock(OutboxPublisher.class);

        PolicyLifecycleService svc = newService(repo, segRepo, pricing, billing, outbox);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(old.getPolicyId()))
                .thenReturn(List.of(oldSeg));

        // Step 1: renew → pending_payment
        RenewalResponse renewResp = svc.renew(old.getPolicyId(), SUBJECT);
        assertTrue(renewResp.isPaymentRequired());
        assertEquals(PolicyStatus.pending_payment, renewResp.getStatus());

        // Capture the renewed policy
        ArgumentCaptor<Policy> policyCaptor = ArgumentCaptor.forClass(Policy.class);
        verify(repo, times(1)).save(policyCaptor.capture());
        Policy renewedPolicy = policyCaptor.getValue();
        assertEquals(PolicyStatus.pending_payment, renewedPolicy.getStatus());
        assertTrue(renewedPolicy.isRenewal());

        // Step 2: simulate InvoicePaid for the renewal invoice
        when(repo.findById(renewedPolicy.getPolicyId())).thenReturn(Optional.of(renewedPolicy));

        PolicyIssuanceService issuanceService = mock(PolicyIssuanceService.class);
        InvoicePaidListener listener = new InvoicePaidListener(issuanceService, svc, repo);

        String invoicePaidMessage = "{\"order_id\":\"" + renewedPolicy.getOrderId()
                + "\",\"policy_id\":\"" + renewedPolicy.getPolicyId() + "\"}";
        listener.onInvoicePaid(invoicePaidMessage, UUID.randomUUID().toString());

        // Verify: policy is now active
        assertEquals(PolicyStatus.active, renewedPolicy.getStatus());

        // Verify: issuePolicy was NOT called (renewal branch returned early)
        verify(issuanceService, never()).issuePolicy(any(), any(), any());

        // Verify: PolicyRenewed event emitted (renewal submit + activation = 2 events)
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(2)).enqueue(eq("PolicyRenewed"), payloadCaptor.capture());
        String activationPayload = payloadCaptor.getAllValues().get(1);
        assertTrue(activationPayload.contains("\"renewed_premium_vnd\":2000000"),
                "activation event must carry renewed_premium_vnd");
    }

    /**
     * C2: activateRenewedPolicy idempotent — calling twice, second is no-op, no duplicate notification.
     */
    @Test
    void c2_activateRenewedPolicyIdempotent() {
        Policy pendingPolicy = activePolicy();
        pendingPolicy.setStatus(PolicyStatus.pending_payment);
        pendingPolicy.setRenewal(true);
        pendingPolicy.setRenewalNumber(1);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(pendingPolicy.getPolicyId())).thenReturn(Optional.of(pendingPolicy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OutboxPublisher outbox = mock(OutboxPublisher.class);
        PolicyLifecycleService svc = newService(repo, mock(ExposureSegmentRepository.class),
                mock(PricingClient.class), mock(BillingClient.class), outbox);

        // First call: activates
        svc.activateRenewedPolicy(pendingPolicy.getPolicyId());
        assertEquals(PolicyStatus.active, pendingPolicy.getStatus());
        verify(outbox, times(1)).enqueue(eq("PolicyRenewed"), anyString());

        // Second call: no-op (status is now active, not pending_payment)
        svc.activateRenewedPolicy(pendingPolicy.getPolicyId());
        verify(outbox, times(1)).enqueue(eq("PolicyRenewed"), anyString());
    }

    /**
     * C3: issuePolicy NOT called for renewal invoice (no third policy created).
     */
    @Test
    void c3_issuePolicyNotCalledForRenewal() {
        Policy pendingPolicy = activePolicy();
        pendingPolicy.setStatus(PolicyStatus.pending_payment);
        pendingPolicy.setRenewal(true);
        pendingPolicy.setRenewalNumber(1);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(pendingPolicy.getPolicyId())).thenReturn(Optional.of(pendingPolicy));

        PolicyIssuanceService issuanceService = mock(PolicyIssuanceService.class);
        PolicyLifecycleService lifecycleService = mock(PolicyLifecycleService.class);

        InvoicePaidListener listener = new InvoicePaidListener(issuanceService, lifecycleService, repo);

        String message = "{\"order_id\":\"" + pendingPolicy.getOrderId()
                + "\",\"policy_id\":\"" + pendingPolicy.getPolicyId() + "\"}";
        listener.onInvoicePaid(message, UUID.randomUUID().toString());

        verify(lifecycleService, times(1)).activateRenewedPolicy(pendingPolicy.getPolicyId());
        verify(issuanceService, never()).issuePolicy(any(), any(), any());
    }

    /**
     * C4: Cancel with remaining credit → event carries correct refundable_credit_vnd.
     */
    @Test
    void c4_cancelWithRefundableCredit() {
        Policy policy = activePolicy();
        ExposureSegment seg = baseSegment(policy);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        BillingClient billing = mock(BillingClient.class);
        when(billing.getRefundableCredit(policy.getPolicyId())).thenReturn(800_000L);

        OutboxPublisher outbox = mock(OutboxPublisher.class);

        PolicyLifecycleService svc = newService(repo, segRepo,
                mock(PricingClient.class), billing, outbox);

        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(seg));

        CancelRequest req = new CancelRequest();
        req.setCancelDate(OffsetDateTime.now().plusDays(10));

        CancelResponse resp = svc.cancel(policy.getPolicyId(), req, SUBJECT);

        assertEquals(800_000L, resp.getRefundableCreditVnd(),
                "CancelResponse must carry refundable_credit_vnd from billing");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("PolicyCancelled"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"refundable_credit_vnd\":800000"),
                "PolicyCancelled event must carry correct refundable_credit_vnd");
    }

    /**
     * C5: Cancel when billing fails → still cancels, refundable_credit_vnd = 0.
     */
    @Test
    void c5_cancelWhenBillingFails() {
        Policy policy = activePolicy();
        ExposureSegment seg = baseSegment(policy);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        BillingClient billing = mock(BillingClient.class);
        when(billing.getRefundableCredit(policy.getPolicyId()))
                .thenThrow(new RuntimeException("billing unavailable"));

        OutboxPublisher outbox = mock(OutboxPublisher.class);

        PolicyLifecycleService svc = newService(repo, segRepo,
                mock(PricingClient.class), billing, outbox);

        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(seg));

        CancelRequest req = new CancelRequest();
        req.setCancelDate(OffsetDateTime.now().plusDays(10));

        CancelResponse resp = svc.cancel(policy.getPolicyId(), req, SUBJECT);

        assertEquals(PolicyStatus.cancelled, resp.getStatus(),
                "policy must still be cancelled even if billing fails");
        assertEquals(0L, resp.getRefundableCreditVnd(),
                "refundable_credit_vnd must default to 0 when billing fails");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("PolicyCancelled"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"refundable_credit_vnd\":0"),
                "event must carry refundable_credit_vnd=0 when billing fails");
    }

    /**
     * C6: RENEWAL_ACTIVATED notification serializes all required fields.
     */
    @Test
    void c6_renewalActivatedNotificationFields() {
        Policy pendingPolicy = activePolicy();
        pendingPolicy.setStatus(PolicyStatus.pending_payment);
        pendingPolicy.setRenewal(true);
        pendingPolicy.setRenewalNumber(1);
        pendingPolicy.setFinalPremiumVnd(2_500_000L);
        OffsetDateTime eff = OffsetDateTime.now();
        pendingPolicy.setPolicyEffectiveDate(eff);
        pendingPolicy.setPolicyExpirationDate(eff.plus(365, ChronoUnit.DAYS));

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(pendingPolicy.getPolicyId())).thenReturn(Optional.of(pendingPolicy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OutboxPublisher outbox = mock(OutboxPublisher.class);
        PolicyLifecycleService svc = newService(repo, mock(ExposureSegmentRepository.class),
                mock(PricingClient.class), mock(BillingClient.class), outbox);

        svc.activateRenewedPolicy(pendingPolicy.getPolicyId());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("PolicyRenewed"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();

        assertTrue(payload.contains("\"customer_id\""), "must carry customer_id");
        assertTrue(payload.contains("\"renewal_number\":1"), "must carry renewal_number");
        assertTrue(payload.contains("\"renewed_premium_vnd\":2500000"), "must carry renewed_premium_vnd");
        assertTrue(payload.contains("\"new_effective_date\""), "must carry new_effective_date");
        assertTrue(payload.contains("\"new_expiration_date\""), "must carry new_expiration_date");
        assertTrue(payload.contains("\"policy_id\""), "must carry policy_id");
    }
}
