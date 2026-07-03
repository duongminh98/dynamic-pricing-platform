package dpp.billing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class CreateInvoiceRequest {
    @NotNull
    private UUID orderId;
    private UUID policyId;
    @NotNull
    @Positive
    private long amountVnd;
    private UUID endorsementRequestId;
    private OffsetDateTime dueDate;
    @NotNull
    private UUID customerId;
}
