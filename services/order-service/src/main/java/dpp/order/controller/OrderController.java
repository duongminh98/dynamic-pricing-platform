package dpp.order.controller;

import dpp.order.dto.CreateOrderRequest;
import dpp.order.dto.OrderResponse;
import dpp.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody CreateOrderRequest request) {
        return orderService.createOrder(jwt.getSubject(), request);
    }
}
