package dpp.notification;

import dpp.notification.controller.NotificationController;
import dpp.notification.entity.Notification;
import dpp.notification.repository.NotificationRepository;
import net.jqwik.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 13")
class NotificationOwnershipPropertyTest {

    private Notification notificationFor(UUID customerId, String type) {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID());
        n.setCustomerId(customerId);
        n.setPolicyId(UUID.randomUUID());
        n.setType(type);
        n.setChannel(dpp.notification.entity.NotificationChannel.in_app);
        n.setMessage("msg");
        n.setStatus(dpp.notification.entity.NotificationStatus.sent);
        n.setRetryCount(0);
        n.setCreatedAt(OffsetDateTime.now());
        return n;
    }

    private Jwt jwtFor(String subject) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject);
        return jwt;
    }

    @Property(tries = 100)
    void myNotificationsReturnsOnlyOwnNotifications(@ForAll int seed) {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID otherId = UUID.nameUUIDFromBytes("other-subject".getBytes());

        Notification own = notificationFor(customerId, "PolicyIssued");
        Notification others = notificationFor(otherId, "PolicyIssued");

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByCustomerIdOrderByCreatedAtDesc(customerId)).thenReturn(List.of(own));

        NotificationController controller = new NotificationController(repo);
        List<Notification> result = controller.myNotifications(jwtFor(subject), null);

        assertEquals(1, result.size());
        assertEquals(customerId, result.get(0).getCustomerId());
        verify(repo, times(1)).findByCustomerIdOrderByCreatedAtDesc(customerId);
        verify(repo, never()).findByCustomerIdOrderByCreatedAtDesc(otherId);
    }

    @Property(tries = 100)
    void myNotificationsUsesCorrectCustomerIdFromSubject(@ForAll int seed) {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByCustomerIdOrderByCreatedAtDesc(customerId)).thenReturn(List.of());

        NotificationController controller = new NotificationController(repo);
        controller.myNotifications(jwtFor(subject), null);

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(repo, times(1)).findByCustomerIdOrderByCreatedAtDesc(captor.capture());
        assertEquals(customerId, captor.getValue());
    }

    @Test
    void property13_sanity() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByCustomerIdOrderByCreatedAtDesc(customerId)).thenReturn(List.of());

        NotificationController controller = new NotificationController(repo);
        List<Notification> result = controller.myNotifications(jwtFor(subject), null);
        assertTrue(result.isEmpty());
    }
}
