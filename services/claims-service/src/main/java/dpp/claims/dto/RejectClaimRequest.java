package dpp.claims.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RejectClaimRequest {
    @NotBlank
    private String reason;
}
