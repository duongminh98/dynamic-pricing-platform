package dpp.notification;

import dpp.notification.controller.NotificationController;
import dpp.notification.dto.NotificationResponse;
import dpp.notification.entity.Notification;
import dpp.notification.entity.NotificationChannel;
import dpp.notification.entity.NotificationStatus;
import dpp.notification.repository.NotificationRepository;
import dpp.notification.service.NotificationService;
import net.jqwik.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 13")
class NotificationOwnershipPropertyTest {

    private Notification notificationFor(UUID customerId, String type) {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID());
        n.setCustomerId(customerId);
        n.setPolicyId(UUID.randomUUID());
        n.setType(type);
        n.setChannel(NotificationChannel.in_app);
        n.setMessage("msg");
        n.setStatus(NotificationStatus.sent);
        n.setRetryCount(0);
        n.setCreatedAt(OffsetDateTime.now());
        return n;
    }

    private Jwt jwtFor(String subject) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject);
        return jwt;
    }

    private NotificationController controllerWith(NotificationRepository repo) {
        return new NotificationController(new NotificationService(repo, null, null, false));
    }

    @Property(tries = 100)
    void myNotificationsReturnsOnlyOwnNotifications(@ForAll int seed) {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID otherId = UUID.nameUUIDFromBytes("other-subject".getBytes());

        Notification own = notificationFor(customerId, "PolicyIssued");

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByCustomerIdAndChannelOrderByCreatedAtDesc(customerId, NotificationChannel.in_app)).thenReturn(List.of(own));

        NotificationController controller = controllerWith(repo);
        List<NotificationResponse> result = controller.myNotifications(jwtFor(subject), false);

        assertEquals(1, result.size());
        assertEquals(customerId, result.get(0).getCustomerId());
        verify(repo, times(1)).findByCustomerIdAndChannelOrderByCreatedAtDesc(customerId, NotificationChannel.in_app);
        verify(repo, never()).findByCustomerIdAndChannelOrderByCreatedAtDesc(otherId, NotificationChannel.in_app);
    }

    @Property(tries = 100)
    void myNotificationsUsesCorrectCustomerIdFromSubject(@ForAll int seed) {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByCustomerIdAndChannelOrderByCreatedAtDesc(customerId, NotificationChannel.in_app)).thenReturn(List.of());

        NotificationController controller = controllerWith(repo);
        controller.myNotifications(jwtFor(subject), false);

        org.mockito.ArgumentCaptor<UUID> captor = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(repo, times(1)).findByCustomerIdAndChannelOrderByCreatedAtDesc(captor.capture(), eq(NotificationChannel.in_app));
        assertEquals(customerId, captor.getValue());
    }

    @Property(tries = 100)
    void unreadOnlyFilterDelegatesToUnreadQuery(@ForAll int seed) {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByCustomerIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(customerId, NotificationChannel.in_app))
                .thenReturn(List.of());

        NotificationController controller = controllerWith(repo);
        controller.myNotifications(jwtFor(subject), true);

        verify(repo, times(1)).findByCustomerIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(customerId, NotificationChannel.in_app);
        verify(repo, never()).findByCustomerIdAndChannelOrderByCreatedAtDesc(customerId, NotificationChannel.in_app);
    }

    @Test
    void property13_sanity() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByCustomerIdAndChannelOrderByCreatedAtDesc(customerId, NotificationChannel.in_app)).thenReturn(List.of());

        NotificationController controller = controllerWith(repo);
        List<NotificationResponse> result = controller.myNotifications(jwtFor(subject), false);
        assertTrue(result.isEmpty());
    }
}
