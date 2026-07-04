package dpp.order;

import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.BillingClient;
import dpp.order.dto.CancelResponse;
import dpp.order.dto.EndorsementRequestResponse;
import dpp.order.dto.PageResponse;
import dpp.order.dto.PolicyDetailResponse;
import dpp.order.dto.PolicyResponse;
import dpp.order.entity.EndorsementRequestEntity;
import dpp.order.entity.EndorsementStatus;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyDocument;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the read/admin paths of PolicyLifecycleService using mocked
 * repositories — no DB. Covers admin listing, policy detail assembly, admin
 * cancellation, endorsement queues, ownership-guarded reads, and due-date extend.
 */
@Tag("Feature: dynamic-pricing-platform")
class PolicyLifecycleServiceCoverageTest {

    private final PolicyRepository policyRepo = mock(PolicyRepository.class);
    private final ExposureSegmentRepository segmentRepo = mock(ExposureSegmentRepository.class);
    private final PolicyDocumentRepository documentRepo = mock(PolicyDocumentRepository.class);
    private final EndorsementRequestRepository endorsementRepo = mock(EndorsementRequestRepository.class);
    private final BillingClient billingClient = mock(BillingClient.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);

    private PolicyLifecycleService service() {
        return new PolicyLifecycleService(policyRepo, segmentRepo, documentRepo, endorsementRepo, billingClient, outbox);
    }

    private Policy policy(UUID policyId, UUID customerId, PolicyStatus status) {
        Policy p = new Policy();
        p.setPolicyId(policyId);
        p.setOrderId(UUID.randomUUID());
        p.setCustomerId(customerId);
        p.setProductId("HEALTH_BASIC");
        p.setLine("health");
        p.setStatus(status);
        p.setPolicyEffectiveDate(OffsetDateTime.now().minusDays(100));
        p.setPolicyExpirationDate(OffsetDateTime.now().plusDays(265));
        p.setFinalPremiumVnd(298000L);
        p.setCreatedAt(OffsetDateTime.now().minusDays(100));
        return p;
    }

    private EndorsementRequestEntity endorsement(UUID id, UUID policyId, EndorsementStatus status) {
        EndorsementRequestEntity r = new EndorsementRequestEntity();
        r.setEndorsementRequestId(id);
        r.setPolicyId(policyId);
        r.setCustomerId(UUID.randomUUID());
        r.setStatus(status);
        r.setChangeSet("{\"smoker\":true}");
        r.setEffectiveDate(OffsetDateTime.now().plusDays(10));
        r.setQuotedPremiumVnd(400000L);
        r.setCreatedAt(OffsetDateTime.now());
        return r;
    }

    // ── admin listing ──

    @Test
    void adminListAllAndByStatus() {
        Policy p = policy(UUID.randomUUID(), UUID.randomUUID(), PolicyStatus.active);
        when(policyRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(p));
        when(policyRepo.findByStatusOrderByCreatedAtDesc(PolicyStatus.active)).thenReturn(List.of(p));

        assertEquals(1, service().adminListAllPolicies().size());
        assertEquals(1, service().adminListPoliciesByStatus(PolicyStatus.active).size());
    }

    @Test
    void adminListPagedMapsResponses() {
        Policy p = policy(UUID.randomUUID(), UUID.randomUUID(), PolicyStatus.active);
        when(policyRepo.findFiltered(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(p)));

        PageResponse<PolicyResponse> resp = service().adminListPoliciesPaged(
                PolicyStatus.active, null, "health", PageRequest.of(0, 20));
        assertEquals(1, resp.getContent().size());
    }

    @Test
    void adminGetPolicyReturnsResponseOrThrows() {
        UUID policyId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(policy(policyId, UUID.randomUUID(), PolicyStatus.active)));
        assertEquals(policyId, service().adminGetPolicy(policyId).getPolicyId());

        UUID missing = UUID.randomUUID();
        when(policyRepo.findById(missing)).thenReturn(Optional.empty());
        assertThrows(ServiceException.class, () -> service().adminGetPolicy(missing));
    }

    // ── policy detail assembly ──

    @Test
    void adminGetPolicyDetailAssemblesSegmentsEndorsementsDocuments() {
        UUID policyId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(policy(policyId, UUID.randomUUID(), PolicyStatus.active)));

        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(policyId);
        seg.setExposureSegmentSeq(0);
        seg.setSegmentStart(OffsetDateTime.now().minusDays(100));
        seg.setSegmentEnd(OffsetDateTime.now().plusDays(265));
        seg.setEarnedExposureYears(0.27);
        seg.setCoverageAmountVnd(100_000_000L);
        seg.setDeductibleVnd(0L);
        when(segmentRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId)).thenReturn(List.of(seg));

        when(endorsementRepo.findByPolicyIdOrderByCreatedAtDesc(policyId))
                .thenReturn(List.of(endorsement(UUID.randomUUID(), policyId, EndorsementStatus.APPLIED)));

        PolicyDocument doc = new PolicyDocument();
        doc.setDocumentId(UUID.randomUUID());
        doc.setPolicyId(policyId);
        doc.setVersion(1);
        doc.setContent("{}");
        doc.setCreatedAt(OffsetDateTime.now());
        when(documentRepo.findByPolicyIdOrderByVersionDesc(policyId)).thenReturn(List.of(doc));

        PolicyDetailResponse resp = service().adminGetPolicyDetail(policyId);
        assertEquals(policyId, resp.getPolicyId());
        assertEquals(1, resp.getExposureSegments().size());
        assertEquals(1, resp.getEndorsements().size());
        assertEquals(1, resp.getDocuments().size());
    }

    // ── admin cancel ──

    @Test
    void adminCancelPolicySucceedsAndEnqueuesEvent() throws Exception {
        UUID policyId = UUID.randomUUID();
        Policy p = policy(policyId, UUID.randomUUID(), PolicyStatus.active);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));
        when(segmentRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId)).thenReturn(List.of());

        CancelResponse resp = service().adminCancelPolicy(policyId, OffsetDateTime.now().plusDays(5));
        assertEquals(PolicyStatus.cancelled, resp.getStatus());
        assertEquals(PolicyStatus.cancelled, p.getStatus());
        verify(outbox).enqueue(eq("PolicyCancelled"), anyString());
    }

    @Test
    void adminCancelPolicyRejectsNonActive() {
        UUID policyId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(policy(policyId, UUID.randomUUID(), PolicyStatus.cancelled)));
        assertThrows(ServiceException.class, () -> service().adminCancelPolicy(policyId, null));
    }

    @Test
    void adminCancelPolicyRejectsOutOfRangeDate() {
        UUID policyId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(policy(policyId, UUID.randomUUID(), PolicyStatus.active)));
        assertThrows(ServiceException.class,
                () -> service().adminCancelPolicy(policyId, OffsetDateTime.now().plusDays(9999)));
    }

    // ── endorsement queues ──

    @Test
    void endorsementQueuesReturnMappedResponses() {
        EndorsementRequestEntity e = endorsement(UUID.randomUUID(), UUID.randomUUID(), EndorsementStatus.PENDING_REVIEW);
        when(endorsementRepo.findByStatusOrderByCreatedAtAsc(EndorsementStatus.PENDING_REVIEW)).thenReturn(List.of(e));
        when(endorsementRepo.findByStatusOrderByDueDateAsc(EndorsementStatus.APPROVED_PENDING_PAYMENT)).thenReturn(List.of(e));
        when(endorsementRepo.findByStatusOrderByCreatedAtAsc(EndorsementStatus.VOID)).thenReturn(List.of(e));
        when(endorsementRepo.findFiltered(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(e)));

        assertEquals(1, service().endorsementReviewQueue().size());
        assertEquals(1, service().pendingPaymentQueue().size());
        assertEquals(1, service().voidedEndorsements().size());
        assertEquals(1, service().adminEndorsementQueuePaged(
                EndorsementStatus.PENDING_REVIEW, null, null, PageRequest.of(0, 20)).getContent().size());
    }

    @Test
    void adminGetEndorsementDetailReturnsOrThrows() {
        UUID id = UUID.randomUUID();
        when(endorsementRepo.findById(id)).thenReturn(Optional.of(endorsement(id, UUID.randomUUID(), EndorsementStatus.APPLIED)));
        assertEquals(id, service().adminGetEndorsementDetail(id).getEndorsementRequestId());

        UUID missing = UUID.randomUUID();
        when(endorsementRepo.findById(missing)).thenReturn(Optional.empty());
        assertThrows(ServiceException.class, () -> service().adminGetEndorsementDetail(missing));
    }

    // ── ownership-guarded customer reads ──

    @Test
    void policyEndorsementsRequiresOwnership() {
        UUID policyId = UUID.randomUUID();
        UUID customerId = dpp.common.security.CustomerId.fromSubject("owner-sub");
        Policy p = policy(policyId, customerId, PolicyStatus.active);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));
        when(endorsementRepo.findByPolicyIdOrderByCreatedAtDesc(policyId))
                .thenReturn(List.of(endorsement(UUID.randomUUID(), policyId, EndorsementStatus.APPLIED)));

        assertEquals(1, service().policyEndorsements(policyId, "owner-sub").size());
        // Non-owner subject → RESOURCE_NOT_FOUND
        assertThrows(ServiceException.class, () -> service().policyEndorsements(policyId, "intruder-sub"));
    }

    @Test
    void policyEndorsementsPagedRequiresOwnership() {
        UUID policyId = UUID.randomUUID();
        UUID customerId = dpp.common.security.CustomerId.fromSubject("owner-sub");
        Policy p = policy(policyId, customerId, PolicyStatus.active);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));
        when(endorsementRepo.findByPolicyIdOrderByCreatedAtDesc(eq(policyId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(endorsement(UUID.randomUUID(), policyId, EndorsementStatus.APPLIED))));

        PageResponse<EndorsementRequestResponse> resp =
                service().policyEndorsementsPaged(policyId, "owner-sub", PageRequest.of(0, 20));
        assertEquals(1, resp.getContent().size());
    }

    @Test
    void getEndorsementValidatesOwnershipAndPolicyMatch() {
        UUID policyId = UUID.randomUUID();
        UUID endorsementId = UUID.randomUUID();
        UUID customerId = dpp.common.security.CustomerId.fromSubject("owner-sub");
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(policy(policyId, customerId, PolicyStatus.active)));
        when(endorsementRepo.findById(endorsementId)).thenReturn(Optional.of(endorsement(endorsementId, policyId, EndorsementStatus.APPLIED)));

        assertEquals(endorsementId, service().getEndorsement(policyId, endorsementId, "owner-sub").getEndorsementRequestId());

        // Endorsement belongs to a different policy → not found
        UUID otherEndorsement = UUID.randomUUID();
        when(endorsementRepo.findById(otherEndorsement))
                .thenReturn(Optional.of(endorsement(otherEndorsement, UUID.randomUUID(), EndorsementStatus.APPLIED)));
        assertThrows(ServiceException.class, () -> service().getEndorsement(policyId, otherEndorsement, "owner-sub"));
    }

    // ── extend due date ──

    @Test
    void extendDueDateReschedulesAndReEnqueues() throws Exception {
        UUID endorsementId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        EndorsementRequestEntity req = endorsement(endorsementId, policyId, EndorsementStatus.APPROVED_PENDING_PAYMENT);
        when(endorsementRepo.findById(endorsementId)).thenReturn(Optional.of(req));
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(policy(policyId, UUID.randomUUID(), PolicyStatus.active)));

        EndorsementRequestResponse resp = service().extendDueDate(endorsementId, 7);
        assertEquals(EndorsementStatus.APPROVED_PENDING_PAYMENT, resp.getStatus());
        assertNull(req.getInvoiceId());
        verify(outbox).enqueue(eq("EndorsementPendingPayment"), anyString());
    }

    @Test
    void extendDueDateRejectsWrongStatus() {
        UUID endorsementId = UUID.randomUUID();
        when(endorsementRepo.findById(endorsementId))
                .thenReturn(Optional.of(endorsement(endorsementId, UUID.randomUUID(), EndorsementStatus.PENDING_REVIEW)));
        assertThrows(ServiceException.class, () -> service().extendDueDate(endorsementId, 7));
    }
}
