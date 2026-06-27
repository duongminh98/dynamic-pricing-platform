package dpp.order.repository;

import dpp.order.entity.EndorsementRequestEntity;
import dpp.order.entity.EndorsementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EndorsementRequestRepository extends JpaRepository<EndorsementRequestEntity, UUID> {
    List<EndorsementRequestEntity> findByStatusOrderByCreatedAtAsc(EndorsementStatus status);
    List<EndorsementRequestEntity> findByPolicyIdOrderByCreatedAtDesc(UUID policyId);
    List<EndorsementRequestEntity> findByStatusOrderByDueDateAsc(EndorsementStatus status);
}
