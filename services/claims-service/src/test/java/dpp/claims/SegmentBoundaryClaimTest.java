package dpp.claims;

import dpp.claims.dto.ClaimResponse;
import dpp.claims.dto.FnolRequest;
import dpp.claims.entity.Claim;
import dpp.claims.entity.ClaimStatus;
import dpp.claims.repository.ClaimRepository;
import dpp.claims.service.ClaimsService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A6: Segment boundary test — verifies that a claim occurring on the exact
 * effective date of a new endorsement segment resolves to the NEW segment,
 * not the prior one. This is the core half-open [start, end) interval fix.
 *
 * <p>Before the fix, the prior segment used a closed interval [start, end]
 * and the new segment also used [start, end], so a claim on the boundary
 * date could match the prior segment (wrong) or both segments (ambiguous).
 * The fix makes non-final segments half-open: [start, end), so the boundary
 * date falls exclusively into the new segment.
 */
@Tag("Feature: dynamic-pricing-platform, Property 12")
class SegmentBoundaryClaimTest {

    private Map<String, Object> policyMap(UUID customerId) {
        Map<String, Object> policy = new HashMap<>();
        policy.put("customer_id", customerId.toString());
        policy.put("status", "active");
        return policy;
    }

    private Map<String, Object> segment(int seq, OffsetDateTime start, OffsetDateTime end) {
        Map<String, Object> s = new HashMap<>();
        s.put("exposure_segment_seq", seq);
        s.put("segment_start", start.toString());
        s.put("segment_end", end.toString());
        s.put("coverage_amount_vnd", 100_000_000L);
        s.put("deductible_vnd", 0L);
        return s;
    }

    private FnolRequest fnolRequest(UUID policyId, OffsetDateTime occurrence) {
        FnolRequest req = new FnolRequest();
        req.setPolicyId(policyId);
        req.setOccurrenceDate(occurrence);
        req.setLossType("collision");
        return req;
    }

    private ClaimsService newService(ClaimRepository repo, dpp.claims.client.OrderClient orderClient) {
        return new ClaimsService(repo, orderClient, mock(OutboxPublisher.class));
    }

    /**
     * Core A6 test: claim on the exact boundary date (segment 1 start = segment 0 end)
     * must resolve to segment 1 (the new segment), not segment 0.
     *
     * This is the exact scenario that was broken before the half-open fix:
     * - Segment 0: [eff, mid]  (old: closed, so mid matched segment 0)
     * - Segment 1: [mid, exp]  (old: closed, so mid also matched segment 1 — ambiguous)
     *
     * After fix:
     * - Segment 0: [eff, mid)  (half-open, mid excluded)
     * - Segment 1: [mid, exp]  (final segment, closed)
     *
     * Claim on `mid` → must resolve to segment 1.
     */
    @Test
    void claimOnSegmentBoundaryResolvesToNewSegment() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        OffsetDateTime eff = OffsetDateTime.now().minusDays(100);
        OffsetDateTime mid = OffsetDateTime.now().minusDays(50);
        OffsetDateTime exp = OffsetDateTime.now().plusDays(265);

        dpp.claims.client.OrderClient orderClient = mock(dpp.claims.client.OrderClient.class);
        when(orderClient.getPolicy(policyId)).thenReturn(policyMap(customerId));
        // Segment 0 ends exactly at `mid`, segment 1 starts exactly at `mid`.
        // No gap, no overlap — the boundary date is shared.
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(
                segment(0, eff, mid),
                segment(1, mid, exp)));

        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo, orderClient);
        // Claim occurrence is exactly on the boundary date `mid`
        ClaimResponse resp = svc.fnol(subject, fnolRequest(policyId, mid));

        assertEquals(ClaimStatus.pending, resp.getClaimStatus());

        ArgumentCaptor<Claim> captor = ArgumentCaptor.forClass(Claim.class);
        verify(repo, times(1)).save(captor.capture());
        assertEquals(1, captor.getValue().getExposureSegmentSeq(),
                "claim on boundary date must resolve to the NEW segment (seq=1), not the prior (seq=0)");
    }

    /**
     * Claim one second before the boundary must still resolve to the prior segment.
     */
    @Test
    void claimJustBeforeBoundaryResolvesToPriorSegment() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        OffsetDateTime eff = OffsetDateTime.now().minusDays(100);
        OffsetDateTime mid = OffsetDateTime.now().minusDays(50);
        OffsetDateTime exp = OffsetDateTime.now().plusDays(265);

        dpp.claims.client.OrderClient orderClient = mock(dpp.claims.client.OrderClient.class);
        when(orderClient.getPolicy(policyId)).thenReturn(policyMap(customerId));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(
                segment(0, eff, mid),
                segment(1, mid, exp)));

        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo, orderClient);
        // One second before the boundary
        ClaimResponse resp = svc.fnol(subject, fnolRequest(policyId, mid.minusSeconds(1)));

        ArgumentCaptor<Claim> captor = ArgumentCaptor.forClass(Claim.class);
        verify(repo, times(1)).save(captor.capture());
        assertEquals(0, captor.getValue().getExposureSegmentSeq(),
                "claim just before boundary must resolve to the PRIOR segment (seq=0)");
    }

    /**
     * Claim on the final segment's end date must be accepted (final segment is inclusive).
     */
    @Test
    void claimOnFinalSegmentEndDateIsAccepted() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        // Policy already expired — final segment end date is in the past
        OffsetDateTime eff = OffsetDateTime.now().minusDays(365);
        OffsetDateTime exp = OffsetDateTime.now().minusDays(1);

        dpp.claims.client.OrderClient orderClient = mock(dpp.claims.client.OrderClient.class);
        when(orderClient.getPolicy(policyId)).thenReturn(policyMap(customerId));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(
                segment(0, eff, exp)));

        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo, orderClient);
        // Claim on the exact expiration date — must be accepted (final segment is inclusive)
        ClaimResponse resp = svc.fnol(subject, fnolRequest(policyId, exp));
        assertEquals(ClaimStatus.pending, resp.getClaimStatus());
    }

    /**
     * Three segments with contiguous boundaries: claim on each boundary resolves correctly.
     */
    @Test
    void claimOnMultipleBoundariesResolvesCorrectly() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        OffsetDateTime eff = OffsetDateTime.now().minusDays(300);
        OffsetDateTime b1 = OffsetDateTime.now().minusDays(200);
        OffsetDateTime b2 = OffsetDateTime.now().minusDays(100);
        OffsetDateTime exp = OffsetDateTime.now().plusDays(65);

        dpp.claims.client.OrderClient orderClient = mock(dpp.claims.client.OrderClient.class);
        when(orderClient.getPolicy(policyId)).thenReturn(policyMap(customerId));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(
                segment(0, eff, b1),
                segment(1, b1, b2),
                segment(2, b2, exp)));

        // Claim on b1 → segment 1
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo, orderClient);

        svc.fnol(subject, fnolRequest(policyId, b1));
        ArgumentCaptor<Claim> captor1 = ArgumentCaptor.forClass(Claim.class);
        verify(repo, times(1)).save(captor1.capture());
        assertEquals(1, captor1.getValue().getExposureSegmentSeq(),
                "claim on b1 must resolve to segment 1");

        // Claim on b2 → segment 2
        reset(repo);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        svc.fnol(subject, fnolRequest(policyId, b2));
        ArgumentCaptor<Claim> captor2 = ArgumentCaptor.forClass(Claim.class);
        verify(repo, times(1)).save(captor2.capture());
        assertEquals(2, captor2.getValue().getExposureSegmentSeq(),
                "claim on b2 must resolve to segment 2");
    }

    /**
     * Defense-in-depth: if two segments ever overlap the occurrence date (e.g. a legacy
     * out-of-order endorsement produced overlapping windows before the order-service guard
     * existed), the claim must resolve to the MOST RECENT segment (highest seq), not the
     * stale earlier one. This mirrors the wrong-coverage bug where first-match-by-seq picked
     * the pre-endorsement segment.
     */
    @Test
    void claimInOverlappingWindowResolvesToLatestSegment() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        OffsetDateTime eff = OffsetDateTime.now().minusDays(100);
        OffsetDateTime exp = OffsetDateTime.now().plusDays(265);
        OffsetDateTime overlapStart = OffsetDateTime.now().minusDays(60);
        OffsetDateTime occurrence = OffsetDateTime.now().minusDays(40);

        dpp.claims.client.OrderClient orderClient = mock(dpp.claims.client.OrderClient.class);
        when(orderClient.getPolicy(policyId)).thenReturn(policyMap(customerId));
        // seg0 spans the whole term [eff, exp]; seg1 (newer) starts mid-term and also
        // reaches exp. The window [overlapStart, exp] is covered by BOTH — the occurrence
        // falls inside both segments.
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(
                segment(0, eff, exp),
                segment(1, overlapStart, exp)));

        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo, orderClient);
        ClaimResponse resp = svc.fnol(subject, fnolRequest(policyId, occurrence));

        assertEquals(ClaimStatus.pending, resp.getClaimStatus());
        ArgumentCaptor<Claim> captor = ArgumentCaptor.forClass(Claim.class);
        verify(repo, times(1)).save(captor.capture());
        assertEquals(1, captor.getValue().getExposureSegmentSeq(),
                "claim in an overlapping window must resolve to the LATEST segment (seq=1), not the stale seq=0");
    }
}
