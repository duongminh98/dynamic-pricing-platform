package dpp.order.dto;

import java.util.UUID;

/**
 * Minimal owner-lookup payload returned by the INTERNAL owner endpoints.
 * Serializes as {@code {"customer_id": "<uuid>"}} under the global SNAKE_CASE policy.
 */
public class OwnerResponse {

    private UUID customerId;

    public OwnerResponse() {
    }

    public OwnerResponse(UUID customerId) {
        this.customerId = customerId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }
}
