package dpp.order.repository;

import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {
    Optional<Policy> findByOrderId(UUID orderId);
    List<Policy> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
    List<Policy> findByCustomerIdAndStatus(UUID customerId, PolicyStatus status);
}
