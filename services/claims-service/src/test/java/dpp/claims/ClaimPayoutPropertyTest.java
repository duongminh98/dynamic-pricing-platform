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
        return new ClaimsService(repo, mock(dpp.claims.client.OrderClient.class), mock(OutboxPublisher.class));
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
