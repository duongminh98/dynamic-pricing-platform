package dpp.order.repository;

import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {
    Optional<Policy> findByOrderId(UUID orderId);
    Optional<Policy> findByPricingRequestId(UUID pricingRequestId);
    List<Policy> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
    List<Policy> findByCustomerIdAndStatus(UUID customerId, PolicyStatus status);
    List<Policy> findByStatusOrderByCreatedAtDesc(PolicyStatus status);
    List<Policy> findAllByOrderByCreatedAtDesc();
    List<Policy> findByOrderIdAndStatusIn(UUID orderId, List<PolicyStatus> statuses);

    @Query("SELECT COUNT(p) > 0 FROM Policy p WHERE p.customerId = :customerId AND p.assetKey = :assetKey AND p.status = dpp.order.entity.PolicyStatus.active")
    boolean existsActivePolicy(@Param("customerId") UUID customerId, @Param("assetKey") String assetKey);

    @Query("SELECT p FROM Policy p WHERE " +
           "(:status IS NULL OR p.status = :status) AND " +
           "(:customerId IS NULL OR p.customerId = :customerId) AND " +
           "(:line IS NULL OR p.line = :line)")
    Page<Policy> findFiltered(@Param("status") PolicyStatus status,
                              @Param("customerId") UUID customerId,
                              @Param("line") String line,
                              Pageable pageable);
}
