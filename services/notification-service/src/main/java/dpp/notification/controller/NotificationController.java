package dpp.notification.controller;

import dpp.common.security.CustomerId;
import dpp.notification.entity.Notification;
import dpp.notification.entity.NotificationStatus;
import dpp.notification.repository.NotificationRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping
    public List<Notification> myNotifications(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(name = "status", required = false) NotificationStatus status) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        if (status != null) {
            return notificationRepository.findByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, status);
        }
        return notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }
}
