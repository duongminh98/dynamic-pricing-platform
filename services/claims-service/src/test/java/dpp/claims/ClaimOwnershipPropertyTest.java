package dpp.claims;

import dpp.claims.entity.Claim;
import dpp.claims.entity.ClaimStatus;
import dpp.claims.entity.SeverityLevel;
import dpp.claims.repository.ClaimRepository;
import dpp.claims.service.ClaimsService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import net.jqwik.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 13")
class ClaimOwnershipPropertyTest {

    private Claim claimForOwner(UUID customerId) {
        Claim claim = new Claim();
        claim.setClaimId(UUID.randomUUID());
        claim.setPolicyId(UUID.randomUUID());
        claim.setCustomerId(customerId);
        claim.setExposureSegmentSeq(0);
        claim.setOccurrenceDate(OffsetDateTime.now().minusDays(5));
        claim.setReportDate(OffsetDateTime.now());
        claim.setLossType("collision");
        claim.setSeverityLevel(SeverityLevel.medium);
        claim.setIncurredAmount(1_000_000L);
        claim.setPaidAmount(500_000L);
        claim.setClaimStatus(ClaimStatus.approved);
        return claim;
    }

    private ClaimsService newService(ClaimRepository repo) {
        return new ClaimsService(repo, mock(dpp.claims.client.OrderClient.class), mock(OutboxPublisher.class));
    }

    @Property(tries = 100)
    void crossCustomerGetClaimRejected(@ForAll int seed) {
        UUID ownerCustomerId = UUID.nameUUIDFromBytes("owner-subject".getBytes());
        UUID claimId = UUID.randomUUID();
        Claim claim = claimForOwner(ownerCustomerId);
        claim.setClaimId(claimId);

        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ClaimsService svc = newService(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getClaim("intruder-subject", claimId));
        assertEquals(ErrorCode.FORBIDDEN_RESOURCE, ex.getErrorCode());
    }

    @Property(tries = 100)
    void sameCustomerGetClaimSucceeds(@ForAll int seed) {
        String subject = "owner-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID claimId = UUID.randomUUID();
        Claim claim = claimForOwner(customerId);
        claim.setClaimId(claimId);

        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ClaimsService svc = newService(repo);
        assertDoesNotThrow(() -> svc.getClaim(subject, claimId));
    }

    @Property(tries = 100)
    void nonExistentClaimRejected(@ForAll int seed) {
        UUID claimId = UUID.randomUUID();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.empty());

        ClaimsService svc = newService(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getClaim("any-subject", claimId));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Property(tries = 100)
    void myClaimsFiltersByCallerCustomerId(@ForAll int seed) {
        String subject = "owner-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findByCustomerIdOrderByCreatedAtDesc(customerId)).thenReturn(java.util.List.of());

        ClaimsService svc = newService(repo);
        svc.myClaims(subject);

        verify(repo, times(1)).findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    @Test
    void property13_sanity() {
        UUID ownerCustomerId = UUID.nameUUIDFromBytes("owner-subject".getBytes());
        UUID claimId = UUID.randomUUID();
        Claim claim = claimForOwner(ownerCustomerId);
        claim.setClaimId(claimId);

        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ClaimsService svc = newService(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getClaim("intruder-subject", claimId));
        assertEquals(ErrorCode.FORBIDDEN_RESOURCE, ex.getErrorCode());
    }
}
