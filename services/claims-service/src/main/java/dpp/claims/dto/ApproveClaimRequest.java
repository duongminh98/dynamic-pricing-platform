package dpp.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
public class ApproveClaimRequest {
    @NotNull
    @Positive
    private Long incurredAmount;
    @NotNull
    @PositiveOrZero
    private Long paidAmount;
    @NotBlank
    private String paymentReference;
    private OffsetDateTime paidAt;
    private String adminNote;
}
