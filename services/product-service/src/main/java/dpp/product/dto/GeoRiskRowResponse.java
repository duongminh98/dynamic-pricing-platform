package dpp.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeoRiskRowResponse {
    private String province;
    private String region;
    private String urbanTierGeo;
    private Double trafficDensityScore;
    private Double vehicleTheftRiskScore;
    private Double accidentFrequencyIndex;
    private Double floodRiskScore;
    private Double stormRiskScore;
    private Double fireRiskScore;
    private Double crimeRiskScore;
    private Double healthcareAccessScore;
    private Double hospitalCostIndex;
    private Double repairCostIndex;
    private Double constructionCostIndex;
}
