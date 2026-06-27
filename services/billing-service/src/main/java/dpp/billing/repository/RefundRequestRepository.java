package dpp.billing.repository;

import dpp.billing.entity.RefundRequest;
import dpp.billing.entity.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {
    List<RefundRequest> findByStatusOrderByRequestedAtAsc(RefundStatus status);
    List<RefundRequest> findByPolicyIdOrderByRequestedAtDesc(UUID policyId);
}
