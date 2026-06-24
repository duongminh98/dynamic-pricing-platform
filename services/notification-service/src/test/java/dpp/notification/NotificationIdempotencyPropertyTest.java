package dpp.notification;

import dpp.notification.entity.Notification;
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
 * Property 21: notification idempotency. Dedup keys on the producer event_id
 * (R7.7): the same delivered event (same X-Event-Id) creates exactly one
 * notification, while distinct events each create their own ? even for the same
 * policy_id and type (a policy can receive many ClaimStatusChanged events).
 */
@Tag("Feature: dynamic-pricing-platform, Property 21")
class NotificationIdempotencyPropertyTest {

    @Property(tries = 100)
    void duplicateEventIdCreatesOnlyOneNotification(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        Notification existing = new Notification();
        when(repo.findByEventId(anyString())).thenReturn(Optional.of(existing));
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo);
        svc.createNotification(UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), "PolicyIssued", "msg");

        verify(repo, never()).save(any(Notification.class));
    }

    @Property(tries = 100)
    void firstEventCreatesNotification(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByEventId(anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo);
        svc.createNotification(UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), "PolicyIssued", "msg");

        verify(repo, times(1)).save(any(Notification.class));
    }

    @Property(tries = 100)
    void distinctEventsForSamePolicyAndTypeBothPersist(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByEventId(anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo);
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

        NotificationService svc = new NotificationService(repo);
        svc.createNotification(null, UUID.randomUUID(), UUID.randomUUID(), "PolicyIssued", "msg");

        verify(repo, times(1)).save(any(Notification.class));
        verify(repo, never()).findByEventId(anyString());
    }

    @Test
    void property21_sanity() {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByEventId(anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo);
        svc.createNotification(UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), "PolicyIssued", "msg");
        verify(repo, times(1)).save(any(Notification.class));
    }
}
