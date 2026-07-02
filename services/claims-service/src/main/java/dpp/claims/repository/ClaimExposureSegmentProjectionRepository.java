package dpp.claims.repository;

import dpp.claims.entity.ClaimExposureSegmentProjection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimExposureSegmentProjectionRepository extends JpaRepository<ClaimExposureSegmentProjection, UUID> {
    List<ClaimExposureSegmentProjection> findByPolicyIdOrderByExposureSegmentSeqAsc(UUID policyId);
    Optional<ClaimExposureSegmentProjection> findByPolicyIdAndExposureSegmentSeq(UUID policyId, int exposureSegmentSeq);
}
