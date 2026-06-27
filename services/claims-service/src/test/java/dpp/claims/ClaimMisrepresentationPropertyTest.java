package dpp.claims;

import dpp.claims.dto.ClaimResponse;
import dpp.claims.dto.MisrepresentationRequest;
import dpp.claims.entity.Claim;
import dpp.claims.entity.ClaimStatus;
import dpp.claims.entity.MisrepresentationSanction;
import dpp.claims.repository.ClaimRepository;
import dpp.claims.service.ClaimsService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import net.jqwik.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 12")
class ClaimMisrepresentationPropertyTest {

    private Claim claimWithStatus(ClaimStatus status) {
        Claim claim = new Claim();
        claim.setClaimId(UUID.randomUUID());
        claim.setPolicyId(UUID.randomUUID());
        claim.setCustomerId(UUID.randomUUID());
        claim.setExposureSegmentSeq(0);
        claim.setOccurrenceDate(OffsetDateTime.now().minusDays(5));
        claim.setReportDate(OffsetDateTime.now());
        claim.setLossType("collision");
        claim.setIncurredAmount(1_000_000L);
        claim.setPaidAmount(800_000L);
        claim.setClaimStatus(status);
        return claim;
    }

    private ClaimsService newService(ClaimRepository repo) {
        when(repo.sumApprovedPaidOnSegment(any(), anyInt(), any(), any())).thenReturn(0L);
        return new ClaimsService(repo, mock(dpp.claims.client.OrderClient.class), mock(OutboxPublisher.class));
    }

    private MisrepresentationRequest rejectRequest() {
        MisrepresentationRequest req = new MisrepresentationRequest();
        req.setSanction(MisrepresentationSanction.reject.name());
        req.setReasons(List.of("undisclosed_prior_loss"));
        return req;
    }

    @Property(tries = 100)
    void misrepresentationOnRejectedClaimRejected(@ForAll int seed) {
        Claim claim = claimWithStatus(ClaimStatus.rejected);
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ClaimsService svc = newService(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.misrepresentation(claimId, rejectRequest()));
        assertEquals(ErrorCode.INVALID_CLAIM_TRANSITION, ex.getErrorCode());
    }

    @Property(tries = 100)
    void misrepresentationOnPendingClaimSucceeds(@ForAll int seed) {
        Claim claim = claimWithStatus(ClaimStatus.pending);
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo);
        ClaimResponse resp = svc.misrepresentation(claimId, rejectRequest());
        assertEquals(0, resp.getPaidAmount());
        assertEquals(MisrepresentationSanction.reject, resp.getMisrepresentationSanction());
    }

    @Property(tries = 100)
    void misrepresentationOnApprovedClaimSucceeds(@ForAll int seed) {
        Claim claim = claimWithStatus(ClaimStatus.approved);
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo);
        ClaimResponse resp = svc.misrepresentation(claimId, rejectRequest());
        assertEquals(0, resp.getPaidAmount());
    }

    @Test
    void property12_sanity_rejectedClaimCannotBeSanctioned() {
        Claim claim = claimWithStatus(ClaimStatus.rejected);
        UUID claimId = claim.getClaimId();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ClaimsService svc = newService(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.misrepresentation(claimId, rejectRequest()));
        assertEquals(ErrorCode.INVALID_CLAIM_TRANSITION, ex.getErrorCode());
    }
}
