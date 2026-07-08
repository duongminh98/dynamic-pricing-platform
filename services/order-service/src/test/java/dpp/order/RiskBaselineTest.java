package dpp.order;

import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The endorsement form pre-populates from {@code policyRiskBaseline}. The baseline must
 * flatten the stored snapshot (health risk attributes live nested under line_attributes)
 * to the flat endorsement keys, and must never leak coverage/deductible or non-line keys -
 * so it can be shown as a display baseline without polluting the change set.
 */
@Tag("Feature: dynamic-pricing-platform, Property 10")
class RiskBaselineTest {

    private static final String SUBJECT = "owner-subject";

    private Policy healthPolicy() {
        Policy p = new Policy();
        p.setPolicyId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setCustomerId(UUID.nameUUIDFromBytes(SUBJECT.getBytes()));
        p.setProductId("HEALTH_STANDARD");
        p.setStatus(PolicyStatus.active);
        OffsetDateTime eff = OffsetDateTime.now().minusDays(10);
        p.setPolicyEffectiveDate(eff);
        p.setPolicyExpirationDate(eff.plus(365, ChronoUnit.DAYS));
        p.setFinalPremiumVnd(3_000_000L);
        return p;
    }

    private ExposureSegment segment(UUID policyId, String snapshot) {
        ExposureSegment s = new ExposureSegment();
        s.setSegmentId(UUID.randomUUID());
        s.setPolicyId(policyId);
        s.setExposureSegmentSeq(0);
        s.setSegmentStart(OffsetDateTime.now().minusDays(10));
        s.setSegmentEnd(OffsetDateTime.now().plusDays(355));
        s.setEarnedExposureYears(0.0);
        s.setCoverageAmountVnd(500_000_000L);
        s.setDeductibleVnd(1_000_000L);
        s.setRiskSnapshot(snapshot);
        return s;
    }

    private PolicyLifecycleService svc(Policy policy, ExposureSegmentRepository segRepo) {
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        return new PolicyLifecycleService(repo, segRepo, mock(PolicyDocumentRepository.class),
                mock(EndorsementRequestRepository.class), mock(PricingClient.class),
                mock(BillingClient.class), mock(OutboxPublisher.class));
    }

    @Test
    void flattensNestedHealthAttributesAndDropsCoverageAndNonLineKeys() {
        Policy policy = healthPolicy();
        // The quote-time snapshot nests risk attributes under line_attributes, alongside
        // demographic top-level keys (age) and locked coverage that must not appear.
        String snapshot = "{\"age\":45,\"coverage_amount_vnd\":500000000,"
                + "\"line_attributes\":{\"smoker\":true,\"diabetes\":true,\"bmi\":27.5,"
                + "\"chronic_disease\":false}}";
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(segment(policy.getPolicyId(), snapshot)));

        Map<String, Object> baseline = svc(policy, segRepo).policyRiskBaseline(policy.getPolicyId(), SUBJECT);

        assertEquals(Boolean.TRUE, baseline.get("smoker"), "nested smoker must flatten to top level");
        assertEquals(Boolean.TRUE, baseline.get("diabetes"));
        assertEquals(Boolean.FALSE, baseline.get("chronic_disease"));
        assertEquals(27.5, baseline.get("bmi"));
        assertFalse(baseline.containsKey("coverage_amount_vnd"), "locked coverage must never surface");
        assertFalse(baseline.containsKey("age"), "non-line keys must not surface as editable fields");
    }

    @Test
    void topLevelSnapshotKeysWinAndUnsetKeysAreOmitted() {
        Policy policy = healthPolicy();
        // A snapshot already flattened by a prior endorsement: flat top-level keys only.
        String snapshot = "{\"smoker\":true,\"bmi\":30}";
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(segment(policy.getPolicyId(), snapshot)));

        Map<String, Object> baseline = svc(policy, segRepo).policyRiskBaseline(policy.getPolicyId(), SUBJECT);

        assertEquals(Boolean.TRUE, baseline.get("smoker"));
        assertEquals(30, baseline.get("bmi"));
        assertFalse(baseline.containsKey("diabetes"), "unset attributes are omitted, not defaulted to false");
    }

    @Test
    void emptySnapshotYieldsEmptyBaseline() {
        Policy policy = healthPolicy();
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(segment(policy.getPolicyId(), "{}")));

        Map<String, Object> baseline = svc(policy, segRepo).policyRiskBaseline(policy.getPolicyId(), SUBJECT);

        assertTrue(baseline.isEmpty());
    }

    @Test
    void rejectsNonOwner() {
        Policy policy = healthPolicy();
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        assertThrows(ServiceException.class,
                () -> svc(policy, segRepo).policyRiskBaseline(policy.getPolicyId(), "someone-else"));
    }
}
