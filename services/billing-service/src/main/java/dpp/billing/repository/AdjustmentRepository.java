package dpp.billing.repository;

import dpp.billing.entity.Adjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AdjustmentRepository extends JpaRepository<Adjustment, UUID> {
    List<Adjustment> findByPolicyIdOrderByCreatedAtAsc(UUID policyId);
}
