package dpp.notification;

import dpp.notification.entity.Notification;
import dpp.notification.entity.NotificationChannel;
import dpp.notification.repository.NotificationRepository;
import dpp.notification.service.NotificationService;
import net.jqwik.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Property 21: notification idempotency. Dedup keys on (producer event_id, channel)
 * (R7.7, task 20.25): the same delivered event for the same channel is a no-op,
 * while the same event for a different channel is allowed (an event can produce
 * both in_app and email notifications). Distinct events each create their own.
 */
@Tag("Feature: dynamic-pricing-platform, Property 21")
class NotificationIdempotencyPropertyTest {

    private NotificationService serviceWith(NotificationRepository repo) {
        return new NotificationService(repo, null, null, false);
    }

    @Property(tries = 100)
    void duplicateEventIdForSameChannelCreatesOnlyOne(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        Notification existing = new Notification();
        when(repo.findByEventIdAndChannel(anyString(), eq(NotificationChannel.in_app)))
                .thenReturn(Optional.of(existing));
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = serviceWith(repo);
        svc.createNotification(UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), "PolicyIssued", "msg");

        verify(repo, never()).save(any(Notification.class));
    }

    @Property(tries = 100)
    void firstEventCreatesNotification(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByEventIdAndChannel(anyString(), any(NotificationChannel.class)))
                .thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = serviceWith(repo);
        svc.createNotification(UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), "PolicyIssued", "msg");

        verify(repo, times(1)).save(any(Notification.class));
    }

    @Property(tries = 100)
    void distinctEventsForSamePolicyAndTypeBothPersist(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByEventIdAndChannel(anyString(), any(NotificationChannel.class)))
                .thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = serviceWith(repo);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        svc.createNotification(UUID.randomUUID().toString(), customerId, policyId, "ClaimStatusChanged", "msg1");
        svc.createNotification(UUID.randomUUID().toString(), customerId, policyId, "ClaimStatusChanged", "msg2");

        verify(repo, times(2)).save(any(Notification.class));
    }

    @Property(tries = 100)
    void nullEventIdAlwaysCreatesNotification(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = serviceWith(repo);
        svc.createNotification(null, UUID.randomUUID(), UUID.randomUUID(), "PolicyIssued", "msg");

        verify(repo, times(1)).save(any(Notification.class));
        verify(repo, never()).findByEventIdAndChannel(anyString(), any(NotificationChannel.class));
    }

    @Property(tries = 100)
    void emailChannelAddedWhenEnabledAndEventTypeMatches(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByEventIdAndChannel(anyString(), any(NotificationChannel.class)))
                .thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo, null, null, true);
        svc.createNotification(UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), "PolicyIssued", "msg");

        // Two saves: one in_app + one email (email fails because sender is null, but row persists)
        verify(repo, times(2)).save(any(Notification.class));
    }

    @Property(tries = 100)
    void emailChannelNotAddedWhenEnabledButEventTypeDoesNotMatch(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByEventIdAndChannel(anyString(), any(NotificationChannel.class)))
                .thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo, null, null, true);
        svc.createNotification(UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), "OrderCreated", "msg");

        // Only in_app for non-email event types
        verify(repo, times(1)).save(any(Notification.class));
    }

    @Test
    void property21_sanity() {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByEventIdAndChannel(anyString(), any(NotificationChannel.class)))
                .thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = serviceWith(repo);
        svc.createNotification(UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), "PolicyIssued", "msg");
        verify(repo, times(1)).save(any(Notification.class));
    }
}
