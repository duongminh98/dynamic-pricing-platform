package dpp.order.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class QuoteIdResponse {
    private UUID orderId;
    private UUID quoteId;
    private String line;

    public QuoteIdResponse() {}

    public QuoteIdResponse(UUID orderId, UUID quoteId, String line) {
        this.orderId = orderId;
        this.quoteId = quoteId;
        this.line = line;
    }
}
