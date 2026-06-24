package dpp.notification.repository;

import dpp.notification.entity.Notification;
import dpp.notification.entity.NotificationChannel;
import dpp.notification.entity.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
    List<Notification> findByCustomerIdAndStatusOrderByCreatedAtDesc(UUID customerId, NotificationStatus status);
    Optional<Notification> findByEventId(String eventId);
    Optional<Notification> findByEventIdAndChannel(String eventId, NotificationChannel channel);
}
