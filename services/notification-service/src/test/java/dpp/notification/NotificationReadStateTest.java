package dpp.notification;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.notification.controller.NotificationController;
import dpp.notification.dto.NotificationResponse;
import dpp.notification.entity.Notification;
import dpp.notification.entity.NotificationChannel;
import dpp.notification.entity.NotificationStatus;
import dpp.notification.repository.NotificationRepository;
import dpp.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NotificationReadStateTest {

    private Notification notificationFor(UUID customerId, boolean read) {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID());
        n.setCustomerId(customerId);
        n.setPolicyId(UUID.randomUUID());
        n.setType("PolicyIssued");
        n.setChannel(NotificationChannel.in_app);
        n.setMessage("msg");
        n.setStatus(NotificationStatus.sent);
        n.setRetryCount(0);
        n.setCreatedAt(OffsetDateTime.now());
        if (read) {
            n.setReadAt(OffsetDateTime.now());
        }
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

    @Test
    void unreadCountReturnsCorrectCount() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.countByCustomerIdAndChannelAndReadAtIsNull(customerId, NotificationChannel.in_app)).thenReturn(3L);

        NotificationController controller = controllerWith(repo);
        Map<String, Long> result = controller.unreadCount(jwtFor(subject));

        assertEquals(3L, result.get("unreadCount"));
    }

    @Test
    void markReadSetsReadAtWhenNull() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        Notification n = notificationFor(customerId, false);

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findById(n.getNotificationId())).thenReturn(Optional.of(n));
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationController controller = controllerWith(repo);
        NotificationResponse result = controller.markRead(jwtFor(subject), n.getNotificationId());

        assertNotNull(result.getReadAt());
        assertTrue(result.isRead());
        verify(repo, times(1)).save(any(Notification.class));
    }

    @Test
    void markReadIsIdempotentWhenAlreadyRead() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        Notification n = notificationFor(customerId, true);
        OffsetDateTime originalReadAt = n.getReadAt();

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findById(n.getNotificationId())).thenReturn(Optional.of(n));

        NotificationController controller = controllerWith(repo);
        NotificationResponse result = controller.markRead(jwtFor(subject), n.getNotificationId());

        assertEquals(originalReadAt, result.getReadAt());
        verify(repo, never()).save(any(Notification.class));
    }

    @Test
    void markReadRejectsForeignNotification() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID otherId = UUID.nameUUIDFromBytes("other-subject".getBytes());
        Notification n = notificationFor(otherId, false);

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findById(n.getNotificationId())).thenReturn(Optional.of(n));

        NotificationController controller = controllerWith(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.markRead(jwtFor(subject), n.getNotificationId()));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
        verify(repo, never()).save(any(Notification.class));
    }

    @Test
    void markReadRejectsNonExistentNotification() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID randomId = UUID.randomUUID();

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findById(randomId)).thenReturn(Optional.empty());

        NotificationController controller = controllerWith(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.markRead(jwtFor(subject), randomId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void markAllReadReturnsUpdatedCount() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.markAllRead(eq(customerId), any(OffsetDateTime.class))).thenReturn(5);

        NotificationController controller = controllerWith(repo);
        Map<String, Integer> result = controller.markAllRead(jwtFor(subject));

        assertEquals(5, result.get("updated"));
    }

    @Test
    void listUnreadOnlyReturnsUnreadNotifications() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        Notification unread = notificationFor(customerId, false);

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByCustomerIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(customerId, NotificationChannel.in_app))
                .thenReturn(List.of(unread));

        NotificationController controller = controllerWith(repo);
        List<NotificationResponse> result = controller.myNotifications(jwtFor(subject), true);

        assertEquals(1, result.size());
        assertFalse(result.get(0).isRead());
    }

    @Test
    void listAllReturnsBothReadAndUnread() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        Notification read = notificationFor(customerId, true);
        Notification unread = notificationFor(customerId, false);

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findByCustomerIdAndChannelOrderByCreatedAtDesc(customerId, NotificationChannel.in_app))
                .thenReturn(List.of(read, unread));

        NotificationController controller = controllerWith(repo);
        List<NotificationResponse> result = controller.myNotifications(jwtFor(subject), false);

        assertEquals(2, result.size());
        assertTrue(result.get(0).isRead());
        assertFalse(result.get(1).isRead());
    }
}
