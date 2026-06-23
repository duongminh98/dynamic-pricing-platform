package dpp.product.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CoverageOptionResponse {

    private Long coverageAmountVnd;
    private Long deductibleVnd;
    private Long basePremiumVnd;
    private Long adminFeeVnd;
}


