package dpp.product.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/** Response view of a loading factor (no JPA entity leak). */
@Getter
@Builder
public class LoadingFactorResponse {
    private UUID loadingFactorId;
    private UUID rateVersionId;
    private String line;
    private double loadingValue;
}
