package dpp.notification.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification")
@Getter
@Setter
public class Notification {

    @Id
    private UUID notificationId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(name = "message", nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private NotificationStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;
}
