package dpp.product.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class RateVersionResponse {

    private UUID rateVersionId;
    private Instant effectiveAt;
    private String createdBy;
    private Boolean isCurrent;
    private Instant createdAt;
}


