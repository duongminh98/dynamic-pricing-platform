package dpp.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** Request body for PUT /admin/loading-factors. */
@Getter
@Setter
public class LoadingFactorRequest {
    @NotBlank
    private String line;
    @NotNull
    private Double loadingValue;
}
