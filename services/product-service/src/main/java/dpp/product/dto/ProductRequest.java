package dpp.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

/** Request body for creating/updating a product (admin). Avoids exposing the JPA entity. */
@Getter
@Setter
public class ProductRequest {
    @NotBlank
    private String productId;
    @NotBlank
    private String category;
    @NotBlank
    private String productName;
    @NotNull
    @PositiveOrZero
    private Long coverageAmountVnd;
    @NotNull
    @PositiveOrZero
    private Long deductibleVnd;
    @NotNull
    @PositiveOrZero
    private Long basePremiumVnd;
    @NotNull
    @PositiveOrZero
    private Long adminFeeVnd;
    private Boolean active;
}
