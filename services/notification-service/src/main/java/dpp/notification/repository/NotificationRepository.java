package dpp.notification.repository;

import dpp.notification.entity.Notification;
import dpp.notification.entity.NotificationChannel;
import dpp.notification.entity.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
    List<Notification> findByCustomerIdAndStatusOrderByCreatedAtDesc(UUID customerId, NotificationStatus status);
    Optional<Notification> findByEventId(String eventId);
    Optional<Notification> findByEventIdAndChannel(String eventId, NotificationChannel channel);

    List<Notification> findByCustomerIdAndChannelOrderByCreatedAtDesc(UUID customerId, NotificationChannel channel);
    List<Notification> findByCustomerIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(UUID customerId, NotificationChannel channel);
    long countByCustomerIdAndChannelAndReadAtIsNull(UUID customerId, NotificationChannel channel);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.customerId = :cid and n.channel = dpp.notification.entity.NotificationChannel.in_app and n.readAt is null")
    int markAllRead(@Param("cid") UUID customerId, @Param("now") OffsetDateTime now);
}
