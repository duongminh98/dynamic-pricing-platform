package dpp.product.dto;

import lombok.Builder;
import lombok.Getter;

/** Response view of a product (no JPA entity leak). Monetary amounts are VND integers. */
@Getter
@Builder
public class ProductResponse {
    private String productId;
    private String category;
    private String productName;
    private long coverageAmountVnd;
    private long deductibleVnd;
    private long basePremiumVnd;
    private long adminFeeVnd;
    private boolean active;
}
