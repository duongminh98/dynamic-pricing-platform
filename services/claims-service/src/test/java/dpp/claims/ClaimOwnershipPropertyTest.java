package dpp.claims;

import dpp.claims.entity.Claim;
import dpp.claims.entity.ClaimStatus;
import dpp.claims.repository.ClaimRepository;
import dpp.claims.service.ClaimsService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import net.jqwik.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
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
                () -> svc.getClaim("intruder-subject", claimId, false));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
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
        assertDoesNotThrow(() -> svc.getClaim(subject, claimId, false));
    }

    @Property(tries = 100)
    void nonExistentClaimRejected(@ForAll int seed) {
        UUID claimId = UUID.randomUUID();
        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.empty());

        ClaimsService svc = newService(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getClaim("any-subject", claimId, false));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Property(tries = 100)
    void myClaimsFiltersByCallerCustomerId(@ForAll int seed) {
        String subject = "owner-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        ClaimRepository repo = mock(ClaimRepository.class);
        Page<Claim> emptyPage = new PageImpl<>(List.of());
        when(repo.findByCustomerIdOrderByCreatedAtDesc(eq(customerId), any(PageRequest.class))).thenReturn(emptyPage);

        ClaimsService svc = newService(repo);
        svc.myClaims(subject, 0, 20);

        verify(repo, times(1)).findByCustomerIdOrderByCreatedAtDesc(eq(customerId), any(PageRequest.class));
    }

    @Property(tries = 100)
    void administratorCanViewAnyClaim(@ForAll int seed) {
        UUID ownerCustomerId = UUID.nameUUIDFromBytes("owner-subject".getBytes());
        UUID claimId = UUID.randomUUID();
        Claim claim = claimForOwner(ownerCustomerId);
        claim.setClaimId(claimId);

        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.findById(claimId)).thenReturn(Optional.of(claim));

        ClaimsService svc = newService(repo);
        // Admin (isAdmin=true) is not the owner but must still see the claim (design 3.6, R28.6).
        assertDoesNotThrow(() -> svc.getClaim("admin-subject", claimId, true));
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
                () -> svc.getClaim("intruder-subject", claimId, false));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }
}
