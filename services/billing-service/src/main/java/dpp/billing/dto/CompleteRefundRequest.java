package dpp.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompleteRefundRequest {
    @NotBlank
    private String paymentReference;
    private String note;
}
