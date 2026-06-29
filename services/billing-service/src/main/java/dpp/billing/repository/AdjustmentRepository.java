package dpp.billing.repository;

import dpp.billing.entity.Adjustment;
import dpp.billing.entity.AdjustmentReason;
import dpp.billing.entity.AdjustmentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AdjustmentRepository extends JpaRepository<Adjustment, UUID> {
    List<Adjustment> findByPolicyIdOrderByCreatedAtAsc(UUID policyId);

    @Query("SELECT a FROM Adjustment a WHERE " +
           "(:type IS NULL OR a.type = :type) AND " +
           "(:reason IS NULL OR a.reason = :reason) AND " +
           "(:policyId IS NULL OR a.policyId = :policyId)")
    Page<Adjustment> findFiltered(@Param("type") AdjustmentType type,
                                  @Param("reason") AdjustmentReason reason,
                                  @Param("policyId") UUID policyId,
                                  Pageable pageable);
}
