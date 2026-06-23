package dpp.notification.service;

import dpp.notification.entity.*;
import dpp.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void createNotification(UUID customerId, UUID policyId, String type, String message) {
        if (policyId != null && notificationRepository.findByPolicyIdAndType(policyId, type).isPresent()) {
            return;
        }
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID());
        n.setCustomerId(customerId);
        n.setPolicyId(policyId);
        n.setType(type);
        n.setChannel(NotificationChannel.in_app);
        n.setMessage(message);
        n.setStatus(NotificationStatus.sent);
        n.setRetryCount(0);
        notificationRepository.save(n);
    }
}
