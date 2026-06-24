package dpp.notification.controller;

import dpp.common.security.CustomerId;
import dpp.notification.dto.NotificationResponse;
import dpp.notification.entity.NotificationStatus;
import dpp.notification.service.NotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
                                                      @RequestParam(name = "status", required = false) NotificationStatus status) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        return notificationService.listForCustomer(customerId, status);
    }
}
