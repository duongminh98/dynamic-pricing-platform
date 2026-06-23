package dpp.order.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "exposure_segment")
@Getter
@Setter
public class ExposureSegment {

    @Id
    private UUID segmentId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "exposure_segment_seq", nullable = false)
    private int exposureSegmentSeq;

    @Column(name = "segment_start", nullable = false)
    private OffsetDateTime segmentStart;

    @Column(name = "segment_end", nullable = false)
    private OffsetDateTime segmentEnd;

    @Column(name = "earned_exposure_years", nullable = false)
    private double earnedExposureYears;

    @Column(name = "coverage_amount_vnd", nullable = false)
    private long coverageAmountVnd;

    @Column(name = "deductible_vnd", nullable = false)
    private long deductibleVnd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_snapshot", columnDefinition = "jsonb", nullable = false)
    private String riskSnapshot;
}
