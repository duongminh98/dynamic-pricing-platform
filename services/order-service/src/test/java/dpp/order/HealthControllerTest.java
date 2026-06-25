package dpp.order;

import dpp.order.controller.HealthController;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthControllerTest {

    @Test
    void infoReturnsServiceStatus() {
        HealthController controller = new HealthController();
        Map<String, Object> result = controller.info();

        assertEquals("order-service", result.get("service"));
        assertEquals("UP", result.get("status"));
    }
}
