package dpp.order;

import dpp.common.outbox.OutboxPublisher;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.entity.Policy;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.repository.ProcessedEventRepository;
import dpp.order.service.PolicyIssuanceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Travel term length from trip_duration_days (R22.3, R34.1). */
class TravelTermTest {

    private OrderEntity order(String line, Integer tripDays) {
        OrderEntity o = new OrderEntity();
        o.setOrderId(UUID.randomUUID());
        o.setCustomerId(UUID.randomUUID());
        o.setProductId("TRAVEL_BASIC");
        o.setFinalPremiumVnd(500_000L);
        o.setStatus(OrderStatus.PENDING_PAYMENT);
        o.setLine(line);
        o.setTripDurationDays(tripDays);
        o.setCreatedAt(OffsetDateTime.now());
        return o;
    }

    private PolicyIssuanceService svc(OrderEntity o, PolicyRepository policyRepo) {
        OrderRepository orderRepo = mock(OrderRepository.class);
        when(orderRepo.findById(o.getOrderId())).thenReturn(Optional.of(o));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ProcessedEventRepository pe = mock(ProcessedEventRepository.class);
        return new PolicyIssuanceService(orderRepo, policyRepo, segRepo, docRepo, pe, mock(OutboxPublisher.class));
    }

    @Test
    void travelPolicyUsesTripDurationDays() {
        OrderEntity o = order("travel", 10);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        PolicyIssuanceService s = svc(o, policyRepo);

        s.issuePolicy(null, o.getOrderId(), null);

        ArgumentCaptor<Policy> captor = ArgumentCaptor.forClass(Policy.class);
        verify(policyRepo, times(1)).save(captor.capture());
        Policy p = captor.getValue();
        long days = ChronoUnit.DAYS.between(p.getPolicyEffectiveDate(), p.getPolicyExpirationDate());
        assertEquals(10, days, "travel term must equal trip_duration_days");
    }

    @Test
    void nonTravelPolicyUsesYearTerm() {
        OrderEntity o = order("health", null);
        o.setProductId("HEALTH_BASIC");
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        PolicyIssuanceService s = svc(o, policyRepo);

        s.issuePolicy(null, o.getOrderId(), null);

        ArgumentCaptor<Policy> captor = ArgumentCaptor.forClass(Policy.class);
        verify(policyRepo, times(1)).save(captor.capture());
        Policy p = captor.getValue();
        long days = ChronoUnit.DAYS.between(p.getPolicyEffectiveDate(), p.getPolicyExpirationDate());
        assertEquals(365, days, "non-travel term must be one year");
    }
}
