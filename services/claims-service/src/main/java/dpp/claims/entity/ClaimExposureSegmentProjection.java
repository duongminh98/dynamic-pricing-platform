package dpp.claims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "claim_exposure_segment_projection")
public class ClaimExposureSegmentProjection {
    @Id
    @Column(name = "segment_id")
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

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getSegmentId() { return segmentId; }
    public void setSegmentId(UUID segmentId) { this.segmentId = segmentId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public int getExposureSegmentSeq() { return exposureSegmentSeq; }
    public void setExposureSegmentSeq(int exposureSegmentSeq) { this.exposureSegmentSeq = exposureSegmentSeq; }
    public OffsetDateTime getSegmentStart() { return segmentStart; }
    public void setSegmentStart(OffsetDateTime segmentStart) { this.segmentStart = segmentStart; }
    public OffsetDateTime getSegmentEnd() { return segmentEnd; }
    public void setSegmentEnd(OffsetDateTime segmentEnd) { this.segmentEnd = segmentEnd; }
    public double getEarnedExposureYears() { return earnedExposureYears; }
    public void setEarnedExposureYears(double earnedExposureYears) { this.earnedExposureYears = earnedExposureYears; }
    public long getCoverageAmountVnd() { return coverageAmountVnd; }
    public void setCoverageAmountVnd(long coverageAmountVnd) { this.coverageAmountVnd = coverageAmountVnd; }
    public long getDeductibleVnd() { return deductibleVnd; }
    public void setDeductibleVnd(long deductibleVnd) { this.deductibleVnd = deductibleVnd; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
