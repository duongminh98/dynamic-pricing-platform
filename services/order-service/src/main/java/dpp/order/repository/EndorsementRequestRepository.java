package dpp.order.repository;

import dpp.order.entity.EndorsementRequestEntity;
import dpp.order.entity.EndorsementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndorsementRequestRepository extends JpaRepository<EndorsementRequestEntity, UUID> {
    List<EndorsementRequestEntity> findByStatusOrderByCreatedAtAsc(EndorsementStatus status);
    List<EndorsementRequestEntity> findByPolicyIdOrderByCreatedAtDesc(UUID policyId);
    List<EndorsementRequestEntity> findByStatusOrderByDueDateAsc(EndorsementStatus status);
    Optional<EndorsementRequestEntity> findByPricingRequestId(UUID pricingRequestId);

    Page<EndorsementRequestEntity> findByPolicyIdOrderByCreatedAtDesc(UUID policyId, Pageable pageable);

    @Query("SELECT e FROM EndorsementRequestEntity e WHERE " +
           "(:status IS NULL OR e.status = :status) AND " +
           "(:customerId IS NULL OR e.customerId = :customerId) AND " +
           "(:policyId IS NULL OR e.policyId = :policyId)")
    Page<EndorsementRequestEntity> findFiltered(@Param("status") EndorsementStatus status,
                                                 @Param("customerId") UUID customerId,
                                                 @Param("policyId") UUID policyId,
                                                 Pageable pageable);

    @Query("SELECT e FROM EndorsementRequestEntity e WHERE e.status = :status " +
           "AND e.invoiceId IS NULL AND e.reviewedAt IS NOT NULL " +
           "AND e.reviewedAt < :threshold")
    List<EndorsementRequestEntity> findStaleWithoutInvoice(@Param("status") EndorsementStatus status,
                                                            @Param("threshold") java.time.OffsetDateTime threshold);
}
