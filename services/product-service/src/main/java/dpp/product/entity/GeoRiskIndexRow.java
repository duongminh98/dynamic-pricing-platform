package dpp.product.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "geo_risk_index_row")
public class GeoRiskIndexRow {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "row_id")
    private UUID rowId;
    @Column(name = "version_id", nullable = false)
    private UUID versionId;
    @Column(name = "province", nullable = false, length = 100)
    private String province;
    @Column(name = "region", length = 100)
    private String region;
    @Column(name = "urban_tier_geo", length = 30)
    private String urbanTierGeo;
    @Column(name = "traffic_density_score", nullable = false)
    private Double trafficDensityScore;
    @Column(name = "vehicle_theft_risk_score", nullable = false)
    private Double vehicleTheftRiskScore;
    @Column(name = "accident_frequency_index", nullable = false)
    private Double accidentFrequencyIndex;
    @Column(name = "flood_risk_score", nullable = false)
    private Double floodRiskScore;
    @Column(name = "storm_risk_score", nullable = false)
    private Double stormRiskScore;
    @Column(name = "fire_risk_score", nullable = false)
    private Double fireRiskScore;
    @Column(name = "crime_risk_score", nullable = false)
    private Double crimeRiskScore;
    @Column(name = "healthcare_access_score", nullable = false)
    private Double healthcareAccessScore;
    @Column(name = "hospital_cost_index", nullable = false)
    private Double hospitalCostIndex;
    @Column(name = "repair_cost_index", nullable = false)
    private Double repairCostIndex;
    @Column(name = "construction_cost_index", nullable = false)
    private Double constructionCostIndex;
}
