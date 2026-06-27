package dpp.claims;

import dpp.claims.client.OrderClient;
import dpp.claims.dto.*;
import dpp.claims.entity.*;
import dpp.claims.repository.ClaimRepository;
import dpp.claims.service.ClaimsService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.dto.PageResponse;
import dpp.common.outbox.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Claims Hardening")
class ClaimsLifecycleHardeningTest {

    private ClaimRepository repo;
    private OrderClient orderClient;
    private OutboxPublisher outbox;
    private ClaimsService svc;
    private final ObjectMapper om = new ObjectMapper();

    private final UUID customerId = UUID.nameUUIDFromBytes("owner-subject".getBytes());
    private final UUID otherCustomerId = UUID.nameUUIDFromBytes("intruder-subject".getBytes());
    private final UUID policyId = UUID.randomUUID();
    private final String subject = "owner-subject";
    private final String intruderSubject = "intruder-subject";

    @BeforeEach
    void setUp() {
        repo = mock(ClaimRepository.class);
        orderClient = mock(OrderClient.class);
        outbox = mock(OutboxPublisher.class);
        svc = new ClaimsService(repo, orderClient, outbox);
        when(repo.sumApprovedPaidOnSegment(any(), anyInt(), any(), any())).thenReturn(0L);
    }

    private Map<String, Object> activePolicy(UUID owner) {
        Map<String, Object> p = new HashMap<>();
        p.put("customer_id", owner.toString());
        p.put("status", "active");
        return p;
    }

    private Map<String, Object> policyWithStatus(UUID owner, String status) {
        Map<String, Object> p = new HashMap<>();
        p.put("customer_id", owner.toString());
        p.put("status", status);
        return p;
    }

    private Map<String, Object> segment(int seq, long coverage, long deductible) {
        Map<String, Object> s = new HashMap<>();
        s.put("exposure_segment_seq", seq);
        s.put("segment_start", OffsetDateTime.now().minusDays(365).toString());
        s.put("segment_end", OffsetDateTime.now().plusDays(365).toString());
        s.put("coverage_amount_vnd", coverage);
        s.put("deductible_vnd", deductible);
        return s;
    }

    private FnolRequest fnolRequest(OffsetDateTime occurrence) {
        FnolRequest req = new FnolRequest();
        req.setPolicyId(policyId);
        req.setOccurrenceDate(occurrence);
        req.setLossType("collision");
        req.setDescription("Front bumper damaged");
        req.setEstimatedCost(12_000_000L);
        req.setAttachments(List.of("https://cdn.example.com/photo-1.jpg"));
        return req;
    }

    private Claim pendingClaim() {
        Claim c = new Claim();
        c.setClaimId(UUID.randomUUID());
        c.setPolicyId(policyId);
        c.setCustomerId(customerId);
        c.setExposureSegmentSeq(0);
        c.setOccurrenceDate(OffsetDateTime.now().minusDays(5));
        c.setReportDate(OffsetDateTime.now());
        c.setLossType("collision");
        c.setIncurredAmount(0);
        c.setPaidAmount(0);
        c.setClaimStatus(ClaimStatus.pending);
        c.setCreatedAt(OffsetDateTime.now());
        return c;
    }

    private Claim approvedClaim(long paidAmount) {
        Claim c = pendingClaim();
        c.setClaimStatus(ClaimStatus.approved);
        c.setIncurredAmount(paidAmount);
        c.setPaidAmount(paidAmount);
        return c;
    }

    // ── T1: FNOL ownership hidden ──
    @Test
    void t1_fnolOwnershipHiddenReturns404() {
        when(orderClient.getPolicy(policyId)).thenReturn(activePolicy(otherCustomerId));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.fnol(subject, fnolRequest(OffsetDateTime.now().minusDays(5))));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
        verify(repo, never()).save(any());
    }

    // ── T2: Get claim ownership hidden ──
    @Test
    void t2_getClaimOwnershipHiddenReturns404() {
        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getClaim(intruderSubject, claimId, false));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    // ── T3: FNOL policy not active ──
    @Test
    void t3_fnolPolicyNotActiveReturns409() {
        when(orderClient.getPolicy(policyId)).thenReturn(policyWithStatus(customerId, "pending_payment"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.fnol(subject, fnolRequest(OffsetDateTime.now().minusDays(5))));
        assertEquals(ErrorCode.POLICY_NOT_MODIFIABLE, ex.getErrorCode());
        verify(repo, never()).save(any());
    }

    // ── T4: FNOL occurrence outside coverage ──
    @Test
    void t4_fnolOccurrenceOutsideCoverageReturns400() {
        when(orderClient.getPolicy(policyId)).thenReturn(activePolicy(customerId));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(segment(0, 100_000_000, 0)));
        OffsetDateTime pastOccurrence = OffsetDateTime.now().minusDays(400);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.fnol(subject, fnolRequest(pastOccurrence)));
        assertEquals(ErrorCode.OCCURRENCE_OUT_OF_COVERAGE, ex.getErrorCode());
    }

    // ── T5: FNOL claim submitted notification ──
    @Test
    void t5_fnolEmitsClaimSubmittedEvent() throws Exception {
        when(orderClient.getPolicy(policyId)).thenReturn(activePolicy(customerId));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(segment(0, 100_000_000, 0)));
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimResponse resp = svc.fnol(subject, fnolRequest(OffsetDateTime.now().minusDays(5)));

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).enqueue(eq("ClaimSubmitted"), eventCaptor.capture());
        JsonNode payload = om.readTree(eventCaptor.getValue());
        assertEquals(resp.getClaimId().toString(), payload.get("claim_id").asText());
        assertEquals(policyId.toString(), payload.get("policy_id").asText());
        assertEquals(customerId.toString(), payload.get("customer_id").asText());
        assertEquals("collision", payload.get("loss_type").asText());
        assertTrue(payload.has("occurrence_date"));
        assertEquals(12_000_000L, payload.get("estimated_cost").asLong());
    }

    // ── T6: Approve per-claim cap ──
    @Test
    void t6_approvePerClaimCapExceeded() {
        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(segment(0, 100_000_000, 10_000_000)));

        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(95_000_000L);
        req.setPaidAmount(95_000_000L);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.approve(claimId, req));
        assertEquals(ErrorCode.PAID_AMOUNT_EXCEEDS_REMAINING_COVERAGE, ex.getErrorCode());
    }

    // ── T7: Approve aggregate cap exceeded ──
    @Test
    void t7_approveAggregateCapExceeded() {
        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(segment(0, 100_000_000, 10_000_000)));
        when(repo.sumApprovedPaidOnSegment(eq(policyId), eq(0), eq(ClaimStatus.approved), eq(claimId)))
                .thenReturn(70_000_000L);

        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(30_000_000L);
        req.setPaidAmount(30_000_000L);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.approve(claimId, req));
        assertEquals(ErrorCode.PAID_AMOUNT_EXCEEDS_REMAINING_COVERAGE, ex.getErrorCode());
    }

    // ── T8: Approve within aggregate cap ──
    @Test
    void t8_approveWithinAggregateCapAccepted() {
        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(segment(0, 100_000_000, 10_000_000)));
        when(repo.sumApprovedPaidOnSegment(eq(policyId), eq(0), eq(ClaimStatus.approved), eq(claimId)))
                .thenReturn(70_000_000L);
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(20_000_000L);
        req.setPaidAmount(20_000_000L);
        req.setAdminNote("Approved after review");
        ClaimResponse resp = svc.approve(claimId, req);
        assertEquals(ClaimStatus.approved, resp.getClaimStatus());
        assertEquals(20_000_000L, resp.getPaidAmount());
        assertEquals("Approved after review", resp.getAdminNote());
    }

    // ── T9: Reject requires reason ──
    @Test
    void t9_rejectWithoutReasonFailsValidation() {
        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        RejectClaimRequest req = new RejectClaimRequest();
        req.setReason("");
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.reject(claimId, req));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    // ── T10: Reject stores admin note + notification ──
    @Test
    void t10_rejectStoresAdminNoteAndEmitsNotification() throws Exception {
        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        RejectClaimRequest req = new RejectClaimRequest();
        req.setReason("The loss is not covered by policy terms");
        ClaimResponse resp = svc.reject(claimId, req);

        assertEquals(ClaimStatus.rejected, resp.getClaimStatus());
        assertEquals(0, resp.getPaidAmount());
        assertEquals("The loss is not covered by policy terms", resp.getAdminNote());

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).enqueue(eq("ClaimStatusChanged"), eventCaptor.capture());
        JsonNode payload = om.readTree(eventCaptor.getValue());
        assertEquals("rejected", payload.get("status").asText());
        assertEquals("The loss is not covered by policy terms", payload.get("admin_note").asText());
    }

    // ── T11: Misrepresentation stores reasons + adjusts paid ──
    @Test
    void t11_misrepresentationProportionalStoresReasonsAndAdjustsPaid() {
        Claim claim = approvedClaim(8_000_000L);
        UUID claimId = claim.getClaimId();
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(segment(0, 100_000_000, 0)));
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        MisrepresentationRequest req = new MisrepresentationRequest();
        req.setSanction("proportional");
        req.setReasons(List.of("Customer reported personal use, but evidence shows commercial use"));
        req.setPaidPremium(5_000_000L);
        req.setShouldPremium(7_000_000L);

        ClaimResponse resp = svc.misrepresentation(claimId, req);
        assertEquals(ClaimStatus.approved, resp.getClaimStatus());
        assertEquals(MisrepresentationSanction.proportional, resp.getMisrepresentationSanction());
        long expected = Math.round(8_000_000L * Math.min(1.0, 5_000_000.0 / 7_000_000.0));
        assertEquals(expected, resp.getPaidAmount());
        assertEquals("Customer reported personal use, but evidence shows commercial use", resp.getAdminNote());
    }

    // ── T12: Customer list pagination ──
    @Test
    void t12_customerListReturnsPageResponse() {
        Claim c1 = pendingClaim();
        Claim c2 = pendingClaim();
        Page<Claim> page = new PageImpl<>(List.of(c1, c2), PageRequest.of(0, 20), 2);
        when(repo.findByCustomerIdOrderByCreatedAtDesc(eq(customerId), any(PageRequest.class))).thenReturn(page);

        PageResponse<ClaimResponse> resp = svc.myClaims(subject, 0, 20);
        assertEquals(2, resp.getContent().size());
        assertEquals(0, resp.getPage());
        assertEquals(20, resp.getSize());
        assertEquals(2, resp.getTotalElements());
        assertEquals(1, resp.getTotalPages());
    }

    // ── T13: Admin list pagination + filters ──
    @Test
    void t13_adminListWithFiltersReturnsPageResponse() {
        Claim c1 = pendingClaim();
        Page<Claim> page = new PageImpl<>(List.of(c1), PageRequest.of(0, 20), 1);
        when(repo.findAdminFiltered(eq(ClaimStatus.pending), eq(customerId), eq(policyId), any(PageRequest.class)))
                .thenReturn(page);

        PageResponse<ClaimResponse> resp = svc.adminListClaims(ClaimStatus.pending, customerId, policyId, 0, 20);
        assertEquals(1, resp.getContent().size());
        assertEquals(ClaimStatus.pending, resp.getContent().get(0).getClaimStatus());
        assertEquals(1, resp.getTotalElements());
    }

    // ── T14: ClaimStatusChanged detailed notification for approve ──
    @Test
    void t14_approveEmitsDetailedNotification() throws Exception {
        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(segment(0, 100_000_000, 0)));
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(10_000_000L);
        req.setPaidAmount(8_000_000L);
        req.setAdminNote("Approved after repair estimate review");
        svc.approve(claimId, req);

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).enqueue(eq("ClaimStatusChanged"), eventCaptor.capture());
        JsonNode payload = om.readTree(eventCaptor.getValue());
        assertEquals("approved", payload.get("status").asText());
        assertEquals(10_000_000L, payload.get("incurred_amount_vnd").asLong());
        assertEquals(8_000_000L, payload.get("paid_amount_vnd").asLong());
        assertEquals("Approved after repair estimate review", payload.get("admin_note").asText());
        assertEquals(claimId.toString(), payload.get("claim_id").asText());
        assertEquals(policyId.toString(), payload.get("policy_id").asText());
        assertEquals(customerId.toString(), payload.get("customer_id").asText());
    }
}
