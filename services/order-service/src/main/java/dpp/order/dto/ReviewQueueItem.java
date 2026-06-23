package dpp.order.dto;

import dpp.order.entity.OrderStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class ReviewQueueItem {
    private UUID orderId;
    private UUID customerId;
    private String productId;
    private long finalPremiumVnd;
    private OrderStatus status;
    private OffsetDateTime createdAt;
}
