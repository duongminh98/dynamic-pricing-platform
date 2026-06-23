package dpp.order.repository;

import dpp.order.entity.ExposureSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExposureSegmentRepository extends JpaRepository<ExposureSegment, UUID> {
    List<ExposureSegment> findByPolicyIdOrderByExposureSegmentSeqAsc(UUID policyId);
}
