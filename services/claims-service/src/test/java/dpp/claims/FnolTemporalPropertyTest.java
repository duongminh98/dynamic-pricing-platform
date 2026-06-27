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
import net.jqwik.api.*;
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

@Tag("Feature: dynamic-pricing-platform, Property 12")
class FnolTemporalPropertyTest {

    private Map<String, Object> policyMap(UUID customerId) {
        Map<String, Object> policy = new HashMap<>();
        policy.put("customer_id", customerId.toString());
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

    @Property(tries = 100)
    void fnolResolvesSegmentSeqFromCoveringSegment(@ForAll int seed) {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        OffsetDateTime eff = OffsetDateTime.now().minusDays(100);
        OffsetDateTime mid = OffsetDateTime.now().minusDays(50);
        OffsetDateTime exp = OffsetDateTime.now().plusDays(265);
        OffsetDateTime occurrence = OffsetDateTime.now().minusDays(10);

        dpp.claims.client.OrderClient orderClient = mock(dpp.claims.client.OrderClient.class);
        when(orderClient.getPolicy(policyId)).thenReturn(policyMap(customerId));
        // Two segments; occurrence falls in the second (seq=1), proving seq is read, not hardcoded 0.
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(
                segment(0, eff, mid.minusSeconds(1)),
                segment(1, mid, exp)));

        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo, orderClient);
        ClaimResponse resp = svc.fnol(subject, fnolRequest(policyId, occurrence));

        assertEquals(ClaimStatus.pending, resp.getClaimStatus());
        assertFalse(resp.getReportDate().isBefore(resp.getOccurrenceDate()));

        ArgumentCaptor<Claim> captor = ArgumentCaptor.forClass(Claim.class);
        verify(repo, times(1)).save(captor.capture());
        assertEquals(1, captor.getValue().getExposureSegmentSeq());
    }

    @Property(tries = 100)
    void fnolWithOccurrenceOutsideAnySegmentRejected(@ForAll int seed) {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        OffsetDateTime eff = OffsetDateTime.now().minusDays(100);
        OffsetDateTime exp = OffsetDateTime.now().plusDays(265);
        OffsetDateTime occurrence = eff.minusDays(10);

        dpp.claims.client.OrderClient orderClient = mock(dpp.claims.client.OrderClient.class);
        when(orderClient.getPolicy(policyId)).thenReturn(policyMap(customerId));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(segment(0, eff, exp)));

        ClaimRepository repo = mock(ClaimRepository.class);

        ClaimsService svc = newService(repo, orderClient);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.fnol(subject, fnolRequest(policyId, occurrence)));
        assertEquals(ErrorCode.OCCURRENCE_OUT_OF_COVERAGE, ex.getErrorCode());
        verify(repo, never()).save(any());
    }

    @Property(tries = 100)
    void fnolWithFutureOccurrenceRejected(@ForAll int seed) {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        OffsetDateTime occurrence = OffsetDateTime.now().plusDays(10);

        dpp.claims.client.OrderClient orderClient = mock(dpp.claims.client.OrderClient.class);
        when(orderClient.getPolicy(policyId)).thenReturn(policyMap(customerId));

        ClaimRepository repo = mock(ClaimRepository.class);

        ClaimsService svc = newService(repo, orderClient);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.fnol(subject, fnolRequest(policyId, occurrence)));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void property12_sanity() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        OffsetDateTime eff = OffsetDateTime.now().minusDays(100);
        OffsetDateTime exp = OffsetDateTime.now().plusDays(265);
        OffsetDateTime occurrence = OffsetDateTime.now().minusDays(10);

        dpp.claims.client.OrderClient orderClient = mock(dpp.claims.client.OrderClient.class);
        when(orderClient.getPolicy(policyId)).thenReturn(policyMap(customerId));
        when(orderClient.getExposureSegments(policyId)).thenReturn(List.of(segment(0, eff, exp)));

        ClaimRepository repo = mock(ClaimRepository.class);
        when(repo.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimsService svc = newService(repo, orderClient);
        ClaimResponse resp = svc.fnol(subject, fnolRequest(policyId, occurrence));
        assertFalse(resp.getReportDate().isBefore(resp.getOccurrenceDate()));
    }
}
