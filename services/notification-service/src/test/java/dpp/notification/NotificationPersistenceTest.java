package dpp.notification;

import dpp.notification.entity.Notification;
import dpp.notification.entity.NotificationStatus;
import dpp.notification.repository.NotificationRepository;
import dpp.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-DB persistence tests (design 5.7, R7.1/R7.3/R7.5/R7.7). Exercises the JPA
 * insert path so created_at NOT NULL and event_id idempotency are verified
 * against a real schema. Requires dpp-postgres-notification (5439) running.
 * Email is disabled by default in test (NOTIFICATION_EMAIL_ENABLED=false), so
 * only in_app notifications are created here.
 * Requirements: R7.1, R7.3, R7.5, R7.7.
 */
@SpringBootTest
@Transactional
class NotificationPersistenceTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void notificationPersistsWithCreatedAtAndSentStatus() {
        UUID customerId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        notificationService.createNotification(eventId, customerId, policyId, "PolicyIssued", "msg");

        List<Notification> saved = notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
        assertEquals(1, saved.size());
        assertNotNull(saved.get(0).getCreatedAt(), "created_at must be set");
        assertEquals(NotificationStatus.sent, saved.get(0).getStatus());
        assertEquals(0, saved.get(0).getRetryCount());
        assertEquals(eventId, saved.get(0).getEventId());
    }

    @Test
    void duplicateEventIdIsIdempotent() {
        UUID customerId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        notificationService.createNotification(eventId, customerId, policyId, "ClaimStatusChanged", "msg1");
        notificationService.createNotification(eventId, customerId, policyId, "ClaimStatusChanged", "msg2");

        assertEquals(1, notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).size(),
                "Same event_id+channel must not create a second notification (R7.7)");
    }

    @Test
    void samePolicyAndTypeWithDifferentEventIdsBothPersist() {
        UUID customerId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();

        notificationService.createNotification(UUID.randomUUID().toString(), customerId, policyId, "ClaimStatusChanged", "first");
        notificationService.createNotification(UUID.randomUUID().toString(), customerId, policyId, "ClaimStatusChanged", "second");

        assertEquals(2, notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).size(),
                "Multiple legitimate events of the same type for a policy must all be stored");
    }

    @Test
    void statusFilterReturnsOnlyMatching() {
        UUID customerId = UUID.randomUUID();
        notificationService.createNotification(UUID.randomUUID().toString(), customerId, UUID.randomUUID(), "PolicyIssued", "msg");

        assertEquals(1, notificationRepository.findByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, NotificationStatus.sent).size());
        assertEquals(0, notificationRepository.findByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, NotificationStatus.failed).size());
    }
}
