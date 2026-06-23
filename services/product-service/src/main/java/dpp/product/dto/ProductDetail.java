package dpp.product.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductDetail {

    private String productId;
    private String category;
    private String productName;
    private Long coverageAmountVnd;
    private Long deductibleVnd;
    private Long basePremiumVnd;
    private Long adminFeeVnd;
}


