package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.order.dto.EndorsementCancelResponse;
import dpp.order.dto.EndorsementPreviewResponse;
import dpp.order.dto.EndorsementRequest;
import dpp.order.dto.EndorsementResult;
import dpp.order.entity.EndorsementPreviewEntity;
import dpp.order.entity.EndorsementRequestEntity;
import dpp.order.entity.EndorsementStatus;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.EndorsementPreviewRepository;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Endorsement Hardening")
class EndorsementHardeningTest {

    private static final String SUBJECT = "owner-subject";
    private static final String ADMIN = "admin-subject";

    private Policy activePolicy() {
        Policy p = new Policy();
        p.setPolicyId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setCustomerId(UUID.nameUUIDFromBytes(SUBJECT.getBytes()));
        p.setProductId("MOTOR_BASIC");
        p.setStatus(PolicyStatus.active);
        OffsetDateTime eff = OffsetDateTime.now().plusDays(1);
        p.setPolicyEffectiveDate(eff);
        p.setPolicyExpirationDate(eff.plus(365, ChronoUnit.DAYS));
        p.setFinalPremiumVnd(1_000_000L);
        return p;
    }

    private EndorsementRequest validRequest(Policy policy) {
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("vehicle_value_vnd", 500_000_000L);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        return req;
    }

    private EndorsementRequestEntity pendingReviewEntity(Policy policy) {
        EndorsementRequestEntity e = new EndorsementRequestEntity();
        e.setEndorsementRequestId(UUID.randomUUID());
        e.setPolicyId(policy.getPolicyId());
        e.setCustomerId(policy.getCustomerId());
        e.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        e.setStatus(EndorsementStatus.PENDING_REVIEW);
        e.setChangeSet("{\"vehicle_value_vnd\":500000000}");
        e.setQuotedPremiumVnd(2_200_000L);
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }

    private EndorsementRequestEntity approvedPendingPaymentEntity(Policy policy) {
        EndorsementRequestEntity e = pendingReviewEntity(policy);
        e.setStatus(EndorsementStatus.APPROVED_PENDING_PAYMENT);
        e.setInvoiceId(UUID.randomUUID());
        e.setDueDate(OffsetDateTime.now().plusDays(14));
        return e;
    }

    private EndorsementRequestEntity appliedEntity(Policy policy) {
        EndorsementRequestEntity e = pendingReviewEntity(policy);
        e.setStatus(EndorsementStatus.APPLIED);
        return e;
    }

    private PolicyLifecycleService newService(Policy policy, EndorsementRequestRepository endRepo,
                                               PricingClient pricing, BillingClient billing,
                                               OutboxPublisher outbox) {
        return newService(policy, endRepo, mock(EndorsementPreviewRepository.class), pricing, billing, outbox);
    }

    private PolicyLifecycleService newService(Policy policy, EndorsementRequestRepository endRepo,
                                               EndorsementPreviewRepository previewRepo,
                                               PricingClient pricing, BillingClient billing,
                                               OutboxPublisher outbox) {
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of());

        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        when(docRepo.findByPolicyIdOrderByVersionDesc(policy.getPolicyId())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        return new PolicyLifecycleService(repo, segRepo, docRepo, endRepo, previewRepo, billing, outbox);
    }

    private ExposureSegment activeSegment(Policy policy) {
        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(policy.getPolicyId());
        seg.setExposureSegmentSeq(0);
        seg.setSegmentStart(policy.getPolicyEffectiveDate());
        seg.setSegmentEnd(policy.getPolicyExpirationDate());
        seg.setCoverageAmountVnd(100_000_000L);
        seg.setDeductibleVnd(1_000_000L);
        seg.setRiskSnapshot("{\"vehicle_value_vnd\":400000000,\"vehicle_age\":2}");
        return seg;
    }

    @Test
    void previewEndorsementReturnsPendingAndPersistsAsyncPreview() {
        Policy policy = activePolicy();
        ExposureSegment base = activeSegment(policy);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId())).thenReturn(List.of(base));
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        EndorsementPreviewRepository previewRepo = mock(EndorsementPreviewRepository.class);
        when(previewRepo.save(any(EndorsementPreviewEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        PolicyLifecycleService s = new PolicyLifecycleService(repo, segRepo, mock(PolicyDocumentRepository.class),
                endRepo, previewRepo, mock(BillingClient.class), outbox);

        EndorsementPreviewResponse response = s.previewEndorsement(policy.getPolicyId(), validRequest(policy), SUBJECT);

        assertEquals("PRICING_PENDING", response.getStatus());
        assertNotNull(response.getPricingRequestId());
        assertNull(response.getQuotedPremiumVnd());
        assertEquals(policy.getFinalPremiumVnd(), response.getCurrentPremiumVnd());
        verify(endRepo, never()).save(any());
        ArgumentCaptor<EndorsementPreviewEntity> previewCaptor = ArgumentCaptor.forClass(EndorsementPreviewEntity.class);
        verify(previewRepo).save(previewCaptor.capture());
        EndorsementPreviewEntity saved = previewCaptor.getValue();
        assertEquals(response.getPricingRequestId(), saved.getPricingRequestId());
        assertEquals("PRICING_PENDING", saved.getStatus());
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("RepriceRequested"), payloadCaptor.capture());
        assertTrue(payloadCaptor.getValue().contains("\"workflow\":\"ENDORSEMENT_PREVIEW\""));
        assertTrue(payloadCaptor.getValue().contains(response.getPricingRequestId().toString()));
    }

    @Test
    void previewStatusReturnsPricedResultAfterRepriceCompleted() {
        Policy policy = activePolicy();
        EndorsementPreviewRepository previewRepo = mock(EndorsementPreviewRepository.class);
        EndorsementPreviewEntity preview = new EndorsementPreviewEntity();
        UUID pricingRequestId = UUID.randomUUID();
        preview.setPricingRequestId(pricingRequestId);
        preview.setPolicyId(policy.getPolicyId());
        preview.setCustomerId(policy.getCustomerId());
        preview.setChangeSet("{\"vehicle_value_vnd\":500000000}");
        preview.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        preview.setCurrentPremiumVnd(policy.getFinalPremiumVnd());
        preview.setCoverageAmountVnd(100_000_000L);
        preview.setDeductibleVnd(1_000_000L);
        preview.setRemainingDays(355);
        preview.setTermDays(365);
        preview.setStatus("PRICING_PENDING");
        preview.setCreatedAt(OffsetDateTime.now());
        when(previewRepo.findById(pricingRequestId)).thenReturn(Optional.of(preview));
        when(previewRepo.save(any(EndorsementPreviewEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        PolicyLifecycleService s = new PolicyLifecycleService(repo, mock(ExposureSegmentRepository.class),
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class), previewRepo,
                mock(BillingClient.class), mock(OutboxPublisher.class));

        s.handleRepriceCompleted(pricingRequestId.toString(), "ENDORSEMENT_PREVIEW", 2_200_000L, null);
        EndorsementPreviewResponse response = s.getEndorsementPreview(policy.getPolicyId(), pricingRequestId, SUBJECT);

        assertEquals("PRICED", response.getStatus());
        assertEquals(2_200_000L, response.getQuotedPremiumVnd());
        assertEquals(1_200_000L, response.getDifferenceVnd());
        assertEquals(Math.round(1_200_000L * (355 / 365.0)), response.getProRatedChargeVnd());
        verify(previewRepo, times(1)).save(preview);
    }

    // -- A4: Concurrent endorsement block --

    @Test
    void concurrentEndorsementInPendingReviewIsBlocked() {
        Policy policy = activePolicy();
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        EndorsementRequestEntity existing = pendingReviewEntity(policy);
        when(endRepo.findByPolicyIdOrderByCreatedAtDesc(policy.getPolicyId()))
                .thenReturn(List.of(existing));

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(any(), anyMap())).thenReturn(Map.of("final_premium_vnd", 2_200_000L));

        PolicyLifecycleService s = newService(policy, endRepo, pricing, mock(BillingClient.class), mock(OutboxPublisher.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.endorse(policy.getPolicyId(), validRequest(policy), SUBJECT));
        assertEquals(ErrorCode.ENDORSEMENT_IN_PROGRESS, ex.getErrorCode());
        verify(endRepo, never()).save(any());
    }

    @Test
    void concurrentEndorsementInApprovedPendingPaymentIsBlocked() {
        Policy policy = activePolicy();
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        EndorsementRequestEntity existing = approvedPendingPaymentEntity(policy);
        when(endRepo.findByPolicyIdOrderByCreatedAtDesc(policy.getPolicyId()))
                .thenReturn(List.of(existing));

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(any(), anyMap())).thenReturn(Map.of("final_premium_vnd", 2_200_000L));

        PolicyLifecycleService s = newService(policy, endRepo, pricing, mock(BillingClient.class), mock(OutboxPublisher.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.endorse(policy.getPolicyId(), validRequest(policy), SUBJECT));
        assertEquals(ErrorCode.ENDORSEMENT_IN_PROGRESS, ex.getErrorCode());
    }

    @Test
    void endorseSucceedsWhenPriorEndorsementIsApplied() {
        Policy policy = activePolicy();
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        EndorsementRequestEntity prior = appliedEntity(policy);
        when(endRepo.findByPolicyIdOrderByCreatedAtDesc(policy.getPolicyId()))
                .thenReturn(List.of(prior));
        when(endRepo.save(any(EndorsementRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(any(), anyMap())).thenReturn(Map.of("final_premium_vnd", 2_200_000L));

        PolicyLifecycleService s = newService(policy, endRepo, pricing, mock(BillingClient.class), mock(OutboxPublisher.class));

        EndorsementResult result = s.endorse(policy.getPolicyId(), validRequest(policy), SUBJECT);
        assertEquals("PRICING_PENDING", result.getStatus());
    }

    @Test
    void endorseSucceedsWhenPriorEndorsementIsCancelled() {
        Policy policy = activePolicy();
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        EndorsementRequestEntity prior = pendingReviewEntity(policy);
        prior.setStatus(EndorsementStatus.CANCELLED);
        when(endRepo.findByPolicyIdOrderByCreatedAtDesc(policy.getPolicyId()))
                .thenReturn(List.of(prior));
        when(endRepo.save(any(EndorsementRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(any(), anyMap())).thenReturn(Map.of("final_premium_vnd", 2_200_000L));

        PolicyLifecycleService s = newService(policy, endRepo, pricing, mock(BillingClient.class), mock(OutboxPublisher.class));

        EndorsementResult result = s.endorse(policy.getPolicyId(), validRequest(policy), SUBJECT);
        assertEquals("PRICING_PENDING", result.getStatus());
    }

    // -- A3: Backdate prevention --

    @Test
    void backdatedEffectiveDateIsRejected() {
        Policy policy = activePolicy();
        // Use an effective date in the past (but within policy term)
        EndorsementRequest req = validRequest(policy);
        req.setEffectiveDate(OffsetDateTime.now().minusDays(5));

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        when(endRepo.findByPolicyIdOrderByCreatedAtDesc(policy.getPolicyId()))
                .thenReturn(List.of());

        PricingClient pricing = mock(PricingClient.class);

        PolicyLifecycleService s = newService(policy, endRepo, pricing, mock(BillingClient.class), mock(OutboxPublisher.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.endorse(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE, ex.getErrorCode());
        verify(endRepo, never()).save(any());
    }

    @Test
    void backdatedPreviewIsRejected() {
        Policy policy = activePolicy();
        EndorsementRequest req = validRequest(policy);
        req.setEffectiveDate(OffsetDateTime.now().minusDays(5));

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PricingClient pricing = mock(PricingClient.class);

        PolicyLifecycleService s = newService(policy, endRepo, pricing, mock(BillingClient.class), mock(OutboxPublisher.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.previewEndorsement(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE, ex.getErrorCode());
    }

    // -- A5: Customer self-cancel --

    @Test
    void cancelPendingReviewEndorsementSucceeds() {
        Policy policy = activePolicy();
        EndorsementRequestEntity pending = pendingReviewEntity(policy);

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));
        when(endRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService s = newService(policy, endRepo, mock(PricingClient.class),
                mock(BillingClient.class), mock(OutboxPublisher.class));

        EndorsementCancelResponse resp = s.cancelEndorsement(
                policy.getPolicyId(), pending.getEndorsementRequestId(), SUBJECT, "Changed my mind");

        assertEquals(EndorsementStatus.CANCELLED, resp.getStatus());
        assertFalse(resp.isInvoiceVoided(), "no invoice for PENDING_REVIEW");
        assertFalse(resp.isPolicyChanged());
        assertNotNull(resp.getCancelledAt());
        assertEquals(EndorsementStatus.CANCELLED, pending.getStatus());
        assertEquals("Changed my mind", pending.getReviewReason());
    }

    @Test
    void cancelApprovedPendingPaymentVoidsInvoice() {
        Policy policy = activePolicy();
        EndorsementRequestEntity pending = approvedPendingPaymentEntity(policy);

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));
        when(endRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BillingClient billing = mock(BillingClient.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        PolicyLifecycleService s = newService(policy, endRepo, mock(PricingClient.class),
                billing, outbox);

        EndorsementCancelResponse resp = s.cancelEndorsement(
                policy.getPolicyId(), pending.getEndorsementRequestId(), SUBJECT, "No longer needed");

        assertEquals(EndorsementStatus.CANCELLED, resp.getStatus());
        assertTrue(resp.isInvoiceVoided(), "invoice must be voided for APPROVED_PENDING_PAYMENT");
        assertFalse(resp.isPolicyChanged());
        verify(billing, never()).createEndorsementInvoice(any(), any(), anyLong(), any(), any());
        verify(outbox, times(1)).enqueue(eq("EndorsementInvoiceVoidRequested"), contains(pending.getEndorsementRequestId().toString()));
    }

    @Test
    void cancelAppliedEndorsementIsRejected() {
        Policy policy = activePolicy();
        EndorsementRequestEntity applied = appliedEntity(policy);

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        when(endRepo.findById(applied.getEndorsementRequestId())).thenReturn(Optional.of(applied));

        PolicyLifecycleService s = newService(policy, endRepo, mock(PricingClient.class),
                mock(BillingClient.class), mock(OutboxPublisher.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.cancelEndorsement(
                        policy.getPolicyId(), applied.getEndorsementRequestId(), SUBJECT, "Try to cancel"));
        assertEquals(ErrorCode.ENDORSEMENT_NOT_CANCELLABLE, ex.getErrorCode());
        verify(endRepo, never()).save(any());
    }

    @Test
    void cancelRejectedEndorsementIsRejected() {
        Policy policy = activePolicy();
        EndorsementRequestEntity rejected = pendingReviewEntity(policy);
        rejected.setStatus(EndorsementStatus.REJECTED);

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        when(endRepo.findById(rejected.getEndorsementRequestId())).thenReturn(Optional.of(rejected));

        PolicyLifecycleService s = newService(policy, endRepo, mock(PricingClient.class),
                mock(BillingClient.class), mock(OutboxPublisher.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.cancelEndorsement(
                        policy.getPolicyId(), rejected.getEndorsementRequestId(), SUBJECT, "Try to cancel"));
        assertEquals(ErrorCode.ENDORSEMENT_NOT_CANCELLABLE, ex.getErrorCode());
    }

    @Test
    void cancelEndorsementForWrongPolicyReturns404() {
        Policy policy = activePolicy();
        EndorsementRequestEntity pending = pendingReviewEntity(policy);
        UUID wrongPolicyId = UUID.randomUUID();

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));

        PolicyRepository repo = mock(PolicyRepository.class);
        Policy wrongPolicy = activePolicy();
        wrongPolicy.setPolicyId(wrongPolicyId);
        when(repo.findById(wrongPolicyId)).thenReturn(Optional.of(wrongPolicy));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);

        PolicyLifecycleService s = new PolicyLifecycleService(repo, segRepo, docRepo, endRepo,
                mock(PricingClient.class), mock(BillingClient.class), mock(OutboxPublisher.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.cancelEndorsement(wrongPolicyId, pending.getEndorsementRequestId(), SUBJECT, "test"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void cancelEmitsEndorsementCancelledEvent() {
        Policy policy = activePolicy();
        EndorsementRequestEntity pending = pendingReviewEntity(policy);

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));
        when(endRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OutboxPublisher outbox = mock(OutboxPublisher.class);

        PolicyLifecycleService s = newService(policy, endRepo, mock(PricingClient.class),
                mock(BillingClient.class), outbox);

        s.cancelEndorsement(policy.getPolicyId(), pending.getEndorsementRequestId(), SUBJECT, "Cancelled");

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).enqueue(eventCaptor.capture(), anyString());
        assertEquals("EndorsementCancelled", eventCaptor.getValue());
    }

    // -- A6: Segment overlap prevention (earlier-dated endorsement after a future-dated one) --

    private ExposureSegment segment(Policy policy, int seq, OffsetDateTime start, OffsetDateTime end) {
        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(policy.getPolicyId());
        seg.setExposureSegmentSeq(seq);
        seg.setSegmentStart(start);
        seg.setSegmentEnd(end);
        seg.setCoverageAmountVnd(100_000_000L);
        seg.setDeductibleVnd(0L);
        seg.setRiskSnapshot("{}");
        return seg;
    }

    private PolicyLifecycleService serviceWithSegments(Policy policy, EndorsementRequestRepository endRepo,
                                                       List<ExposureSegment> segments) {
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(segments);

        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        when(docRepo.findByPolicyIdOrderByVersionDesc(policy.getPolicyId())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(any(), anyMap())).thenReturn(Map.of("final_premium_vnd", 2_200_000L));

        return new PolicyLifecycleService(repo, segRepo, docRepo, endRepo,
                pricing, mock(BillingClient.class), mock(OutboxPublisher.class));
    }

    @Test
    void endorseBeforeLatestSegmentStartIsRejected() {
        Policy policy = activePolicy();
        OffsetDateTime pEff = policy.getPolicyEffectiveDate();
        // A future-dated endorsement already created seg1 starting at pEff+40.
        List<ExposureSegment> segments = new ArrayList<>();
        segments.add(segment(policy, 0, pEff, pEff.plusDays(40)));
        segments.add(segment(policy, 1, pEff.plusDays(40), policy.getPolicyExpirationDate()));

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        when(endRepo.findByPolicyIdOrderByCreatedAtDesc(policy.getPolicyId())).thenReturn(List.of());

        PolicyLifecycleService s = serviceWithSegments(policy, endRepo, segments);

        // A second endorsement dated BEFORE seg1's start (pEff+25) would overlap seg0.
        EndorsementRequest req = validRequest(policy);
        req.setEffectiveDate(pEff.plusDays(25));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.endorse(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE, ex.getErrorCode());
        verify(endRepo, never()).save(any());
    }

    @Test
    void previewBeforeLatestSegmentStartIsRejected() {
        Policy policy = activePolicy();
        OffsetDateTime pEff = policy.getPolicyEffectiveDate();
        List<ExposureSegment> segments = new ArrayList<>();
        segments.add(segment(policy, 0, pEff, pEff.plusDays(40)));
        segments.add(segment(policy, 1, pEff.plusDays(40), policy.getPolicyExpirationDate()));

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = serviceWithSegments(policy, endRepo, segments);

        EndorsementRequest req = validRequest(policy);
        req.setEffectiveDate(pEff.plusDays(25));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.previewEndorsement(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE, ex.getErrorCode());
    }

    @Test
    void endorseAfterLatestSegmentStartIsAccepted() {
        Policy policy = activePolicy();
        OffsetDateTime pEff = policy.getPolicyEffectiveDate();
        List<ExposureSegment> segments = new ArrayList<>();
        segments.add(segment(policy, 0, pEff, pEff.plusDays(40)));
        segments.add(segment(policy, 1, pEff.plusDays(40), policy.getPolicyExpirationDate()));

        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        when(endRepo.findByPolicyIdOrderByCreatedAtDesc(policy.getPolicyId())).thenReturn(List.of());
        when(endRepo.save(any(EndorsementRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService s = serviceWithSegments(policy, endRepo, segments);

        // Dated strictly after seg1's start — contiguous, no overlap.
        EndorsementRequest req = validRequest(policy);
        req.setEffectiveDate(pEff.plusDays(60));

        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);
        assertEquals("PRICING_PENDING", result.getStatus());
    }
}

