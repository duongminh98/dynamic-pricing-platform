package dpp.claims;

import dpp.claims.consumer.ClaimsPolicyProjectionListener;
import dpp.claims.entity.ClaimExposureSegmentProjection;
import dpp.claims.entity.ClaimPolicyProjection;
import dpp.claims.repository.ClaimExposureSegmentProjectionRepository;
import dpp.claims.repository.ClaimPolicyProjectionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the claims policy/segment read-model projection listener.
 * Uses mocked repositories — no RabbitMQ, no DB.
 */
@Tag("Feature: dynamic-pricing-platform")
class ClaimsPolicyProjectionListenerTest {

    private final UUID policyId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID quoteId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private ClaimsPolicyProjectionListener newListener(
            ClaimPolicyProjectionRepository policyRepo,
            ClaimExposureSegmentProjectionRepository segmentRepo) {
        return new ClaimsPolicyProjectionListener(policyRepo, segmentRepo);
    }

    @Test
    void policyIssuedCreatesPolicyAndSegmentProjection() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);
        when(policyRepo.findById(policyId)).thenReturn(Optional.empty());
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(segmentRepo.findByPolicyIdAndExposureSegmentSeq(policyId, 0)).thenReturn(Optional.empty());
        when(segmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OffsetDateTime eff = OffsetDateTime.now().minusDays(10);
        OffsetDateTime exp = OffsetDateTime.now().plusDays(355);
        String message = String.format("""
                {
                  "policy_id": "%s",
                  "customer_id": "%s",
                  "order_id": "%s",
                  "quote_id": "%s",
                  "product_id": "HEALTH_BASIC",
                  "line": "health",
                  "status": "active",
                  "policy_effective_date": "%s",
                  "policy_expiration_date": "%s",
                  "final_premium_vnd": 1200000,
                  "exposure_segment_seq": 0,
                  "segment_start": "%s",
                  "segment_end": "%s",
                  "earned_exposure_years": 1.0,
                  "coverage_amount_vnd": 100000000,
                  "deductible_vnd": 500000
                }
                """, policyId, customerId, orderId, quoteId, eff, exp, eff, exp);

        newListener(policyRepo, segmentRepo).onPolicyIssued(message);

        ArgumentCaptor<ClaimPolicyProjection> policyCaptor = ArgumentCaptor.forClass(ClaimPolicyProjection.class);
        verify(policyRepo).save(policyCaptor.capture());
        ClaimPolicyProjection policy = policyCaptor.getValue();
        assertEquals(policyId, policy.getPolicyId());
        assertEquals(customerId, policy.getCustomerId());
        assertEquals(quoteId, policy.getQuoteId());
        assertEquals(orderId, policy.getOrderId());
        assertEquals("HEALTH_BASIC", policy.getProductId());
        assertEquals("health", policy.getLine());
        assertEquals("active", policy.getStatus());
        assertEquals(1200000L, policy.getFinalPremiumVnd());

        ArgumentCaptor<ClaimExposureSegmentProjection> segmentCaptor =
                ArgumentCaptor.forClass(ClaimExposureSegmentProjection.class);
        verify(segmentRepo).save(segmentCaptor.capture());
        ClaimExposureSegmentProjection segment = segmentCaptor.getValue();
        assertEquals(policyId, segment.getPolicyId());
        assertEquals(0, segment.getExposureSegmentSeq());
        assertEquals(100000000L, segment.getCoverageAmountVnd());
        assertEquals(500000L, segment.getDeductibleVnd());
        // segment id is derived deterministically from policyId:seq when exposure_id absent
        assertEquals(UUID.nameUUIDFromBytes((policyId + ":0").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                segment.getSegmentId());
    }

    @Test
    void renewedUsesNewEffectiveAndExpirationDates() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);
        when(policyRepo.findById(policyId)).thenReturn(Optional.empty());
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OffsetDateTime newEff = OffsetDateTime.now().plusDays(355);
        OffsetDateTime newExp = OffsetDateTime.now().plusDays(720);
        String message = String.format("""
                {
                  "policy_id": "%s",
                  "customer_id": "%s",
                  "status": "active",
                  "new_effective_date": "%s",
                  "new_expiration_date": "%s"
                }
                """, policyId, customerId, newEff, newExp);

        newListener(policyRepo, segmentRepo).onPolicyRenewed(message);

        ArgumentCaptor<ClaimPolicyProjection> captor = ArgumentCaptor.forClass(ClaimPolicyProjection.class);
        verify(policyRepo).save(captor.capture());
        assertEquals(newEff.toInstant(), captor.getValue().getPolicyEffectiveDate().toInstant());
        assertEquals(newExp.toInstant(), captor.getValue().getPolicyExpirationDate().toInstant());
        // no segment fields → no segment save
        verify(segmentRepo, never()).save(any());
    }

    @Test
    void endorsementUpsertsExistingSegment() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);

        ClaimPolicyProjection existingPolicy = new ClaimPolicyProjection();
        existingPolicy.setPolicyId(policyId);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(existingPolicy));
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClaimExposureSegmentProjection existingSegment = new ClaimExposureSegmentProjection();
        existingSegment.setSegmentId(UUID.randomUUID());
        when(segmentRepo.findByPolicyIdAndExposureSegmentSeq(policyId, 1)).thenReturn(Optional.of(existingSegment));
        when(segmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID exposureId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.now().minusDays(5);
        OffsetDateTime end = OffsetDateTime.now().plusDays(360);
        String message = String.format("""
                {
                  "policy_id": "%s",
                  "customer_id": "%s",
                  "status": "active",
                  "exposure_id": "%s",
                  "exposure_segment_seq": 1,
                  "segment_start": "%s",
                  "segment_end": "%s",
                  "coverage_amount_vnd": 200000000,
                  "deductible_vnd": 1000000
                }
                """, policyId, customerId, exposureId, start, end);

        newListener(policyRepo, segmentRepo).onEndorsementApplied(message);

        ArgumentCaptor<ClaimExposureSegmentProjection> captor =
                ArgumentCaptor.forClass(ClaimExposureSegmentProjection.class);
        verify(segmentRepo).save(captor.capture());
        // reused existing row, and segment id came from the explicit exposure_id
        assertSame(existingSegment, captor.getValue());
        assertEquals(exposureId, captor.getValue().getSegmentId());
        assertEquals(200000000L, captor.getValue().getCoverageAmountVnd());
    }

    @Test
    void cancelledUpdatesPolicyButSkipsSegment() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);
        when(policyRepo.findById(policyId)).thenReturn(Optional.empty());
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String message = String.format("""
                {
                  "policy_id": "%s",
                  "customer_id": "%s",
                  "status": "cancelled",
                  "exposure_segment_seq": 0,
                  "segment_start": "%s",
                  "segment_end": "%s"
                }
                """, policyId, customerId, OffsetDateTime.now().minusDays(5), OffsetDateTime.now());

        newListener(policyRepo, segmentRepo).onPolicyCancelled(message);

        verify(policyRepo).save(any());
        // includeSegment=false for cancellation → never touches segment repo
        verify(segmentRepo, never()).save(any());
    }

    @Test
    void missingPolicyOrCustomerIdIsIgnored() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);

        newListener(policyRepo, segmentRepo).onPolicyIssued("{\"customer_id\": \"" + customerId + "\"}");

        verify(policyRepo, never()).save(any());
        verify(segmentRepo, never()).save(any());
    }

    @Test
    void malformedMessageThrowsRuntimeException() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);

        assertThrows(RuntimeException.class,
                () -> newListener(policyRepo, segmentRepo).onPolicyIssued("not-json"));
    }
}
