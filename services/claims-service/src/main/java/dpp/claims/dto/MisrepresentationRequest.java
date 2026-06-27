package dpp.claims.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MisrepresentationRequest {
    @NotNull
    private String sanction;
    @NotEmpty
    private List<String> reasons;

    private Long paidPremium;
    private Long shouldPremium;
}
