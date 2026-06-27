package dpp.claims.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApproveClaimRequest {
    @NotNull
    @Positive
    private Long incurredAmount;
    @NotNull
    @PositiveOrZero
    private Long paidAmount;
    private String adminNote;
}
