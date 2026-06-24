package dpp.claims.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MisrepresentationRequest {
    @NotNull
    private String sanction;
    @NotNull
    private List<String> reasons;

    /** Premium actually paid by the customer (VND). Required for proportional sanction (R28.9). */
    private Long paidPremium;

    /** Premium that should have been charged given true risk (VND). Required for proportional sanction (R28.9). */
    private Long shouldPremium;
}
