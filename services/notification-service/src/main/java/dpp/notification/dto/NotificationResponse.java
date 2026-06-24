package dpp.notification.dto;

import dpp.notification.entity.NotificationChannel;
import dpp.notification.entity.NotificationStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Response view of a notification (no JPA entity leak). */
@Getter
@Builder
public class NotificationResponse {
    private UUID notificationId;
    private UUID customerId;
    private UUID policyId;
    private String type;
    private NotificationChannel channel;
    private String message;
    private NotificationStatus status;
    private int retryCount;
    private OffsetDateTime createdAt;
}
