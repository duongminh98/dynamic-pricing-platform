package dpp.billing.repository;

import dpp.billing.entity.RefundRequest;
import dpp.billing.entity.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {
    List<RefundRequest> findByStatusOrderByRequestedAtAsc(RefundStatus status);
    List<RefundRequest> findByPolicyIdOrderByRequestedAtDesc(UUID policyId);

    @Query("SELECT r FROM RefundRequest r WHERE " +
           "(:status IS NULL OR r.status = :status) AND " +
           "(:customerId IS NULL OR r.customerId = :customerId) AND " +
           "(:policyId IS NULL OR r.policyId = :policyId)")
    Page<RefundRequest> findFiltered(@Param("status") RefundStatus status,
                                     @Param("customerId") UUID customerId,
                                     @Param("policyId") UUID policyId,
                                     Pageable pageable);
}
