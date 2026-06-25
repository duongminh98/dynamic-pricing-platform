package dpp.billing;

import dpp.billing.entity.*;
import dpp.billing.repository.AdjustmentRepository;
import dpp.billing.repository.ProcessedEventRepository;
import dpp.billing.service.AdjustmentService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 9")
class ProRataPropertyTest {

    @Property(tries = 100)
    void endorsementAdjustmentIsProRata(
            @ForAll @LongRange(min = 0, max = 100000000) long premiumOld,
            @ForAll @LongRange(min = 0, max = 100000000) long premiumNew,
            @ForAll @LongRange(min = 0, max = 365) long remainingDays,
            @ForAll @LongRange(min = 1, max = 365) long termDays) {
        AdjustmentRepository repo = mock(AdjustmentRepository.class);
        AdjustmentService service = new AdjustmentService(repo, mock(ProcessedEventRepository.class));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.applyEndorsement(UUID.randomUUID().toString(), UUID.randomUUID(), premiumOld, premiumNew, remainingDays, termDays);
        ArgumentCaptor<Adjustment> captor = ArgumentCaptor.forClass(Adjustment.class);
        verify(repo, times(1)).save(captor.capture());
        Adjustment adj = captor.getValue();
        double rawFraction = (double) remainingDays / termDays;
        double fraction = Math.max(0, Math.min(1, rawFraction));
        assertTrue(fraction >= 0 && fraction <= 1);
        long expectedDelta = Math.round((premiumNew - premiumOld) * fraction);
        if (expectedDelta >= 0) {
            assertEquals(AdjustmentType.additional_charge, adj.getType());
        } else {
            assertEquals(AdjustmentType.refund, adj.getType());
        }
        assertEquals(Math.abs(expectedDelta), adj.getAmountVnd());
        assertEquals(AdjustmentReason.endorsement, adj.getReason());
    }

    @Property(tries = 100)
    void cancellationRefundIsProRataAndNonNegative(
            @ForAll @LongRange(min = 0, max = 10000000) long finalPremiumVnd,
            @ForAll @LongRange(min = 0, max = 365) long remainingDays,
            @ForAll @LongRange(min = 1, max = 365) long termDays) {
        AdjustmentRepository repo = mock(AdjustmentRepository.class);
        AdjustmentService service = new AdjustmentService(repo, mock(ProcessedEventRepository.class));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.applyCancellation(UUID.randomUUID().toString(), UUID.randomUUID(), finalPremiumVnd, remainingDays, termDays);
        ArgumentCaptor<Adjustment> captor = ArgumentCaptor.forClass(Adjustment.class);
        verify(repo, times(1)).save(captor.capture());
        Adjustment adj = captor.getValue();
        double rawFraction = (double) remainingDays / termDays;
        double fraction = Math.max(0, Math.min(1, rawFraction));
        assertTrue(fraction >= 0 && fraction <= 1);
        long expectedRefund = Math.round(finalPremiumVnd * fraction);
        assertEquals(AdjustmentType.refund, adj.getType());
        assertEquals(expectedRefund, adj.getAmountVnd());
        assertTrue(adj.getAmountVnd() >= 0);
        assertEquals(AdjustmentReason.cancellation, adj.getReason());
    }

    @Test
    void remainingFractionClampedToRange() {
        AdjustmentRepository repo = mock(AdjustmentRepository.class);
        AdjustmentService service = new AdjustmentService(repo, mock(ProcessedEventRepository.class));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.applyCancellation(UUID.randomUUID().toString(), UUID.randomUUID(), 1000000, 400, 365);
        ArgumentCaptor<Adjustment> captor = ArgumentCaptor.forClass(Adjustment.class);
        verify(repo).save(captor.capture());
        assertTrue(captor.getValue().getAmountVnd() <= 1000000);
    }
}
