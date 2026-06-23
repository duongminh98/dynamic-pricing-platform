package dpp.notification.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification", uniqueConstraints = @UniqueConstraint(columnNames = {"policy_id", "type"}))
@Getter
@Setter
public class Notification {

    @Id
    private UUID notificationId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "policy_id")
    private UUID policyId;

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
}
