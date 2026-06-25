package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.PricingClient;
import dpp.order.dto.CancelRequest;
import dpp.order.dto.PolicyResponse;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Renewal re-rating + cancellation exposure cut (R24.2, R25.3). */
class RenewalAndCancellationTest {

    private static final String SUBJECT = "owner-subject";

    private Policy activePolicy() {
        Policy p = new Policy();
        p.setPolicyId(UUID.randomUUID());
        p.setCustomerId(UUID.nameUUIDFromBytes(SUBJECT.getBytes()));
        p.setProductId("MOTOR_BASIC");
        p.setStatus(PolicyStatus.active);
        OffsetDateTime eff = OffsetDateTime.now().minusDays(30);
        p.setPolicyEffectiveDate(eff);
        p.setPolicyExpirationDate(eff.plus(365, ChronoUnit.DAYS));
        p.setFinalPremiumVnd(1_000_000L);
        return p;
    }

    @Test
    void renewalReRatesPremiumFromPricing() {
        Policy old = activePolicy();
        old.setPolicyExpirationDate(OffsetDateTime.now().minusDays(1)); // expired -> renew now
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(old.getPolicyId())).thenReturn(Optional.of(old));
        when(repo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));
        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap())).thenReturn(Map.of("final_premium_vnd", 1_750_000L));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo, mock(ExposureSegmentRepository.class),
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class),
                pricing, mock(OutboxPublisher.class));

        PolicyResponse resp = svc.renew(old.getPolicyId(), SUBJECT);

        assertEquals(1_750_000L, resp.getFinalPremiumVnd(), "renewal premium must come from re-rating, not cloned");
        verify(pricing, times(1)).rerate(eq("MOTOR_BASIC"), anyMap());
    }

    @Test
    void cancellationCutsCoveringSegmentAndUsesCancelDateError() {
        Policy policy = activePolicy();
        OffsetDateTime eff = policy.getPolicyEffectiveDate();
        OffsetDateTime exp = policy.getPolicyExpirationDate();
        OffsetDateTime cancelDate = eff.plusDays(100);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(policy.getPolicyId());
        seg.setExposureSegmentSeq(0);
        seg.setSegmentStart(eff);
        seg.setSegmentEnd(exp);
        seg.setEarnedExposureYears(1.0);
        seg.setCoverageAmountVnd(100_000_000L);
        seg.setDeductibleVnd(0L);
        seg.setRiskSnapshot("{}");
        List<ExposureSegment> segs = new ArrayList<>(List.of(seg));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId())).thenReturn(segs);
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo, segRepo,
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class),
                mock(PricingClient.class), mock(OutboxPublisher.class));

        CancelRequest req = new CancelRequest();
        req.setCancelDate(cancelDate);
        svc.cancel(policy.getPolicyId(), req, SUBJECT);

        ArgumentCaptor<ExposureSegment> captor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(captor.capture());
        ExposureSegment cut = captor.getValue();
        assertEquals(cancelDate, cut.getSegmentEnd(), "segment must be cut at cancel_date");
        double expectedYears = Math.max(0, ChronoUnit.DAYS.between(eff, cancelDate)) / 365.25;
        assertEquals(expectedYears, cut.getEarnedExposureYears(), 1e-9);
    }

    @Test
    void cancelDateOutsideRangeUsesCancelDateError() {
        Policy policy = activePolicy();
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo, mock(ExposureSegmentRepository.class),
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class),
                mock(PricingClient.class), mock(OutboxPublisher.class));

        CancelRequest req = new CancelRequest();
        req.setCancelDate(policy.getPolicyExpirationDate().plusDays(5));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.cancel(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.CANCEL_DATE_OUT_OF_RANGE, ex.getErrorCode());
    }
}
