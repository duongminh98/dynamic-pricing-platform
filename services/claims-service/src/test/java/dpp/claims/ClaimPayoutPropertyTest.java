package dpp.claims;

import dpp.claims.dto.ApproveClaimRequest;
import dpp.claims.dto.ClaimResponse;
import dpp.claims.entity.Claim;
import dpp.claims.entity.ClaimStatus;
import dpp.claims.entity.SeverityLevel;
import dpp.claims.repository.ClaimRepository;
import dpp.claims.service.ClaimsService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 11")
class ClaimPayoutPropertyTest {

    private Claim pendingClaim() {
        Claim claim = new Claim();
        claim.setClaimId(UUID.randomUUID());
        claim.setPolicyId(UUID.randomUUID());
        claim.setCustomerId(UUID.randomUUID());
        claim.setExposureSegmentSeq(0);
        claim.setOccurrenceDate(OffsetDateTime.now().minusDays(5));
        claim.setReportDate(OffsetDateTime.now());
        claim.setLossType("collision");
        claim.setSeverityLevel(SeverityLevel.medium);
        claim.setIncurredAmount(0);
        claim.setPaidAmount(0);
        claim.setClaimStatus(ClaimStatus.pending);
        return claim;
    }

    private ClaimsService newService(ClaimRepository repo) {
        return newService(repo, 100_000_000L, 0L);
    }

    private ClaimsService newService(ClaimRepository repo, long coverageVnd, long deductibleVnd) {
        dpp.claims.client.OrderClient orderClient = mock(dpp.claims.client.OrderClient.class);
        Map<String, Object> seg = new java.util.HashMap<>();
        seg.put("exposure_segment_seq", 0);
        seg.put("segment_start", OffsetDateTime.now().minusDays(365).toString());
        seg.put("segment_end", OffsetDateTime.now().plusDays(365).toString());
        seg.put("coverage_amount_vnd", coverageVnd);
        seg.put("deductible_vnd", deductibleVnd);
        when(orderClient.getExposureSegments(any(UUID.class))).thenReturn(List.of(seg));
        return new ClaimsService(repo, orderClient, mock(OutboxPublisher.class));
    }

    @Property(tries = 100)
    void approveWithValidAmountsSetsPaidNotExceedingIncurred(
            @ForAll @LongRange(min = 1, max = 100_000_000) long incurred,
            @ForAll @LongRange(min = 0, max = 100_000_000) long paid) {
        Assume.that(paid <= incurred);

        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo);
        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(incurred);
        req.setPaidAmount(paid);
        ClaimResponse resp = svc.approve(claimId, req);

        assertEquals(ClaimStatus.approved, resp.getClaimStatus());
        assertEquals(incurred, resp.getIncurredAmount());
        assertEquals(paid, resp.getPaidAmount());
        assertTrue(resp.getPaidAmount() <= resp.getIncurredAmount());
    }

    @Property(tries = 100)
    void approveWithNonPositiveIncurredRejected(
            @ForAll @LongRange(max = 0) long incurred) {
        Assume.that(incurred <= 0);

        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ClaimsService svc = newService(repo);
        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(incurred);
        req.setPaidAmount(0L);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.approve(claimId, req));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Property(tries = 100)
    void approveWithPaidExceedingIncurredRejected(
            @ForAll @LongRange(min = 1, max = 50_000_000) long incurred) {
        long paid = incurred + 1;

        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ClaimsService svc = newService(repo);
        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(incurred);
        req.setPaidAmount(paid);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.approve(claimId, req));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Property(tries = 100)
    void rejectSetsPaidToZero(@ForAll int seed) {
        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo);
        ClaimResponse resp = svc.reject(claimId);

        assertEquals(ClaimStatus.rejected, resp.getClaimStatus());
        assertEquals(0, resp.getPaidAmount());
    }

    @Property(tries = 100)
    void approveOnNonPendingRejected(@ForAll int seed) {
        Claim claim = pendingClaim();
        claim.setClaimStatus(ClaimStatus.approved);
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ClaimsService svc = newService(repo);
        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(1_000L);
        req.setPaidAmount(500L);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.approve(claimId, req));
        assertEquals(ErrorCode.INVALID_CLAIM_TRANSITION, ex.getErrorCode());
    }

    @Property(tries = 100)
    void approveWithPaidExceedingCoverageMinusDeductibleRejected(
            @ForAll @LongRange(min = 1, max = 50_000_000) long cap) {
        long coverage = cap + 1_000_000L;
        long deductible = 1_000_000L; // so coverage - deductible == cap
        long paid = cap + 1;          // exceeds cap by 1
        long incurred = paid;         // valid vs incurred, but exceeds cap

        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ClaimsService svc = newService(repo, coverage, deductible);
        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(incurred);
        req.setPaidAmount(paid);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.approve(claimId, req));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Property(tries = 100)
    void approveWithPaidAtCapAccepted(
            @ForAll @LongRange(min = 1, max = 50_000_000) long cap) {
        long coverage = cap;
        long deductible = 0L; // coverage - deductible == cap
        long paid = cap;
        long incurred = cap;

        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo, coverage, deductible);
        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(incurred);
        req.setPaidAmount(paid);
        ClaimResponse resp = svc.approve(claimId, req);
        assertEquals(ClaimStatus.approved, resp.getClaimStatus());
        assertEquals(paid, resp.getPaidAmount());
    }

    @Test
    void property11_sanity() {
        Claim claim = pendingClaim();
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo);
        ApproveClaimRequest req = new ApproveClaimRequest();
        req.setIncurredAmount(1_000_000L);
        req.setPaidAmount(800_000L);
        ClaimResponse resp = svc.approve(claimId, req);
        assertEquals(ClaimStatus.approved, resp.getClaimStatus());
        assertEquals(800_000L, resp.getPaidAmount());
    }
}
