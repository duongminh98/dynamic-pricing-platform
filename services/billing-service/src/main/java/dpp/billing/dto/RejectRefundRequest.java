package dpp.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RejectRefundRequest {
    @NotBlank
    private String reason;
}
