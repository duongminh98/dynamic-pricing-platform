package dpp.notification.service;

import dpp.notification.entity.*;
import dpp.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final int MAX_SEND_ATTEMPTS = 3;

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * Persist a notification for a delivered event. Idempotent on event_id (R7.7):
     * a redelivered event with the same X-Event-Id is a no-op. Status is derived
     * from a simulated send with up to {@link #MAX_SEND_ATTEMPTS} attempts; the
     * final state is {@code sent} or {@code failed} and retry_count is persisted
     * (R7.3, R7.5).
     */
    @Transactional
    public void createNotification(String eventId, UUID customerId, UUID policyId, String type, String message) {
        if (eventId != null && notificationRepository.findByEventId(eventId).isPresent()) {
            return;
        }
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID());
        n.setEventId(eventId);
        n.setCustomerId(customerId);
        n.setPolicyId(policyId);
        n.setType(type);
        n.setChannel(NotificationChannel.in_app);
        n.setMessage(message);
        n.setCreatedAt(OffsetDateTime.now());

        deliver(n);
        notificationRepository.save(n);
    }

    /**
     * Simulated delivery: tries up to MAX_SEND_ATTEMPTS times, recording the
     * attempt count. In-app delivery always succeeds; the retry scaffold lets
     * real channels (email) flip to {@code failed} after exhausting attempts.
     */
    private void deliver(Notification n) {
        int attempts = 0;
        boolean delivered = false;
        while (attempts < MAX_SEND_ATTEMPTS && !delivered) {
            attempts++;
            delivered = sendOnce(n);
        }
        n.setRetryCount(attempts - 1);
        n.setStatus(delivered ? NotificationStatus.sent : NotificationStatus.failed);
        if (!delivered) {
            log.warn("Notification {} failed after {} attempts (type={})", n.getNotificationId(), attempts, n.getType());
        }
    }

    /** In-app channel delivery. Returns true on success. */
    private boolean sendOnce(Notification n) {
        return n.getChannel() == NotificationChannel.in_app;
    }
}
