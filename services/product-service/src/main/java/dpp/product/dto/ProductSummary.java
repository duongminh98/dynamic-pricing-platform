package dpp.product.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductSummary {

    private String productId;
    private String line;
    private String productName;
    private Long coverageAmountVnd;
    private Long deductibleVnd;
}


