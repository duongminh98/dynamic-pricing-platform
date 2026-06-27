package dpp.notification.service;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.notification.dto.NotificationResponse;
import dpp.notification.entity.*;
import dpp.notification.repository.NotificationRepository;
import dpp.notification.client.CustomerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final int MAX_SEND_ATTEMPTS = 3;

    /** Event types that should also be delivered via email (R7.2, design 2.2). */
    static final Set<String> EMAIL_EVENT_TYPES = Set.of(
            "PolicyIssued", "PolicyCancelled", "ClaimStatusChanged",
            "EndorsementSubmitted", "EndorsementApplied", "EndorsementRejected",
            "EndorsementCancelled", "PolicyRenewed",
            "OrderApproved", "OrderRejected",
            "EndorsementPendingPayment", "EndorsementOverdue",
            "EndorsementCreditIssued", "RefundRequested", "RefundCompleted");

    private final NotificationRepository notificationRepository;
    private final EmailSender emailSender;
    private final CustomerClient customerClient;
    private final boolean emailEnabled;

    public NotificationService(NotificationRepository notificationRepository,
                               EmailSender emailSender,
                               CustomerClient customerClient,
                               @Value("${notification.email.enabled:false}") boolean emailEnabled) {
        this.notificationRepository = notificationRepository;
        this.emailSender = emailSender;
        this.customerClient = customerClient;
        this.emailEnabled = emailEnabled;
    }

    /**
     * Persist notifications for a delivered event, one per channel. Idempotent on
     * (event_id, channel) (R7.7): a redelivered event with the same X-Event-Id for
     * the same channel is a no-op. Status is derived from a real send with up to
     * {@link #MAX_SEND_ATTEMPTS} attempts; the final state is {@code sent} or
     * {@code failed} and retry_count is persisted (R7.3, R7.5).
     */
    @Transactional
    public void createNotification(String eventId, UUID customerId, UUID policyId, String type, String message) {
        for (NotificationChannel channel : resolveChannels(type)) {
            if (eventId != null
                    && notificationRepository.findByEventIdAndChannel(eventId, channel).isPresent()) {
                continue;
            }
            Notification n = new Notification();
            n.setNotificationId(UUID.randomUUID());
            n.setEventId(eventId);
            n.setCustomerId(customerId);
            n.setPolicyId(policyId);
            n.setType(type);
            n.setChannel(channel);
            n.setMessage(message);
            n.setCreatedAt(OffsetDateTime.now());

            deliver(n);
            notificationRepository.save(n);
        }
    }

    /**
     * Determine which channels to deliver on. In-app is always used; email is added
     * for key event types when email is enabled (R7.2).
     */
    private List<NotificationChannel> resolveChannels(String type) {
        List<NotificationChannel> channels = new ArrayList<>();
        channels.add(NotificationChannel.in_app);
        if (emailEnabled && EMAIL_EVENT_TYPES.contains(type)) {
            channels.add(NotificationChannel.email);
        }
        return channels;
    }

    /**
     * Delivery with retry: tries up to MAX_SEND_ATTEMPTS times, recording the
     * attempt count. In-app delivery always succeeds; email delivery delegates to
     * {@link EmailSender} which may fail, flipping to {@code failed} after
     * exhausting attempts (R7.3, R7.5 - no throw, no infinite redelivery).
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
            log.warn("Notification {} failed after {} attempts (type={}, channel={})",
                    n.getNotificationId(), attempts, n.getType(), n.getChannel());
        }
    }

    /** Single delivery attempt. In-app always succeeds; email fetches address + sends. */
    private boolean sendOnce(Notification n) {
        if (n.getChannel() == NotificationChannel.in_app) {
            return true;
        }
        if (n.getChannel() == NotificationChannel.email && emailSender != null && customerClient != null) {
            String email = customerClient.getEmail(n.getCustomerId());
            if (email == null) {
                return false;
            }
            return emailSender.send(email, subjectFor(n.getType()), n.getMessage());
        }
        return false;
    }

    private String subjectFor(String type) {
        return switch (type) {
            case "PolicyIssued" -> "Your insurance policy has been issued";
            case "PolicyCancelled" -> "Your insurance policy has been cancelled";
            case "ClaimStatusChanged" -> "Your claim status has changed";
            case "PolicyRenewed" -> "Your insurance policy has been renewed";
            case "EndorsementSubmitted" -> "Your endorsement request has been submitted";
            case "EndorsementApplied" -> "Your policy endorsement has been applied";
            case "EndorsementRejected" -> "Your endorsement request has been rejected";
            case "EndorsementCancelled" -> "Your endorsement request has been cancelled";
            case "EndorsementPendingPayment" -> "Payment required for your endorsement";
            case "EndorsementOverdue" -> "Your endorsement payment has expired";
            case "OrderApproved" -> "Your order has been approved";
            case "OrderRejected" -> "Your order has been rejected";
            case "OrderSubmitted" -> "Your order has been submitted";
            case "EndorsementCreditIssued" -> "Premium credit issued for your endorsement";
            case "RefundRequested" -> "Refund request created for your policy";
            case "RefundCompleted" -> "Your refund has been completed";
            case "InvoiceVoided" -> "Your invoice has been voided";
            default -> "Insurance notification";
        };
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> listForCustomer(UUID customerId, boolean unreadOnly) {
        List<Notification> rows = unreadOnly
                ? notificationRepository.findByCustomerIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(customerId, NotificationChannel.in_app)
                : notificationRepository.findByCustomerIdAndChannelOrderByCreatedAtDesc(customerId, NotificationChannel.in_app);
        return rows.stream().map(NotificationService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public long countUnread(UUID customerId) {
        return notificationRepository.countByCustomerIdAndChannelAndReadAtIsNull(customerId, NotificationChannel.in_app);
    }

    @Transactional
    public NotificationResponse markRead(UUID customerId, UUID notificationId) {
        Notification n = notificationRepository.findById(notificationId).orElse(null);
        if (n == null || !n.getCustomerId().equals(customerId)) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Notification not found", null);
        }
        if (n.getReadAt() == null) {
            n.setReadAt(OffsetDateTime.now());
            notificationRepository.save(n);
        }
        return toResponse(n);
    }

    @Transactional
    public int markAllRead(UUID customerId) {
        return notificationRepository.markAllRead(customerId, OffsetDateTime.now());
    }

    static NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .notificationId(n.getNotificationId())
                .customerId(n.getCustomerId())
                .policyId(n.getPolicyId())
                .type(n.getType())
                .channel(n.getChannel())
                .message(n.getMessage())
                .status(n.getStatus())
                .retryCount(n.getRetryCount())
                .createdAt(n.getCreatedAt())
                .readAt(n.getReadAt())
                .read(n.getReadAt() != null)
                .build();
    }
}
