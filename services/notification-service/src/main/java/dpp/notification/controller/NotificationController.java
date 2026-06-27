package dpp.notification.controller;

import dpp.common.security.CustomerId;
import dpp.notification.dto.NotificationResponse;
import dpp.notification.service.NotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationResponse> myNotifications(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        return notificationService.listForCustomer(customerId, unreadOnly);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        return Map.of("unreadCount", notificationService.countUnread(customerId));
    }

    @PatchMapping("/{id}/read")
    public NotificationResponse markRead(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable("id") UUID notificationId) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        return notificationService.markRead(customerId, notificationId);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        return Map.of("updated", notificationService.markAllRead(customerId));
    }
}
