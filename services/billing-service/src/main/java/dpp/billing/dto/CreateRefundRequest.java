package dpp.billing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class CreateRefundRequest {
    @NotNull
    private UUID policyId;
    @NotNull
    private UUID customerId;
    private UUID creditId;
    @NotNull
    @Min(1)
    private Long amountVnd;
    private String note;
}
