package dpp.order.controller;

import dpp.order.dto.*;
import dpp.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/review-queue")
    @PreAuthorize("hasRole(''Administrator'')")
    public List<ReviewQueueItem> reviewQueue() {
        return orderService.reviewQueue();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole(''Administrator'')")
    public OrderResponse getOrder(@PathVariable UUID id) {
        return orderService.getOrder(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole(''Administrator'')")
    public OrderResponse approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return orderService.approve(id, jwt.getSubject());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole(''Administrator'')")
    public OrderResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        return orderService.reject(id, request.getReason(), jwt.getSubject());
    }
}
