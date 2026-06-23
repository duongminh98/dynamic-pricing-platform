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

@Tag("Feature: dynamic-pricing-platform, Property 21")
class NotificationIdempotencyPropertyTest {

    @Property(tries = 100)
    void duplicatePolicyIssuedCreatesOnlyOneNotification(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        Notification existing = new Notification();
        when(repo.findByPolicyIdAndType(any(UUID.class), eq("PolicyIssued")))
                .thenReturn(Optional.of(existing));
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        svc.createNotification(customerId, policyId, "PolicyIssued", "msg");
        svc.createNotification(customerId, policyId, "PolicyIssued", "msg");

        verify(repo, never()).save(any(Notification.class));
    }

    @Property(tries = 100)
    void firstEventCreatesNotification(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByPolicyIdAndType(any(UUID.class), anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        svc.createNotification(customerId, policyId, "PolicyIssued", "msg");

        verify(repo, times(1)).save(any(Notification.class));
    }

    @Property(tries = 100)
    void differentTypeForSamePolicyCreatesSeparateNotifications(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByPolicyIdAndType(any(UUID.class), anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        svc.createNotification(customerId, policyId, "PolicyIssued", "msg1");
        svc.createNotification(customerId, policyId, "PolicyCancelled", "msg2");

        verify(repo, times(2)).save(any(Notification.class));
    }

    @Property(tries = 100)
    void nullPolicyIdAlwaysCreatesNotification(@ForAll int seed) {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo);
        UUID customerId = UUID.randomUUID();

        svc.createNotification(customerId, null, "PolicyIssued", "msg");

        verify(repo, times(1)).save(any(Notification.class));
        verify(repo, never()).findByPolicyIdAndType(any(), anyString());
    }

    @Test
    void property21_sanity() {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByPolicyIdAndType(any(UUID.class), anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationService svc = new NotificationService(repo);
        svc.createNotification(UUID.randomUUID(), UUID.randomUUID(), "PolicyIssued", "msg");
        verify(repo, times(1)).save(any(Notification.class));
    }
}
