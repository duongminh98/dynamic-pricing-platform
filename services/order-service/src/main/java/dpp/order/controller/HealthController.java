package dpp.order.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/actuator/health/info")
    public Map<String, Object> info() {
        return Map.of("service", "order-service", "status", "UP");
    }
}
