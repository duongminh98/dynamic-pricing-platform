package dpp.order.dto;

import dpp.order.entity.OrderStatus;
import dpp.order.entity.ReviewDecision;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class OrderResponse {
    private UUID orderId;
    private UUID quoteId;
    private UUID customerId;
    private String productId;
    private long finalPremiumVnd;
    private String line;
    private Integer tripDurationDays;
    private Long coverageAmountVnd;
    private Long deductibleVnd;
    private Map<String, Object> riskProfile;
    private OrderStatus status;
    private ReviewDecision reviewDecision;
    private String reviewReason;
    private String reviewedBy;
    private OffsetDateTime reviewedAt;
    private OffsetDateTime createdAt;
    private UUID invoiceId;
}
