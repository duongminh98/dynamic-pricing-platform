package dpp.notification.controller;

import dpp.notification.entity.Notification;
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
    public List<Notification> myNotifications(@AuthenticationPrincipal Jwt jwt) {
        UUID customerId = UUID.nameUUIDFromBytes(jwt.getSubject().getBytes());
        return notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }
}
