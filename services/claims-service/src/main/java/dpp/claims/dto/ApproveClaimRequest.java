package dpp.claims.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApproveClaimRequest {
    @NotNull
    private Long incurredAmount;
    @NotNull
    private Long paidAmount;
}
