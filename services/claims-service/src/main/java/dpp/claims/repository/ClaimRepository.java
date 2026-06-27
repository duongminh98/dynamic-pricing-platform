package dpp.claims.repository;

import dpp.claims.entity.Claim;
import dpp.claims.entity.ClaimStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    List<Claim> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
    List<Claim> findByPolicyIdOrderByCreatedAtDesc(UUID policyId);
    List<Claim> findByClaimStatusOrderByCreatedAtDesc(ClaimStatus claimStatus);
    List<Claim> findAllByOrderByCreatedAtDesc();

    Page<Claim> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    @Query("""
        select coalesce(sum(c.paidAmount), 0)
        from Claim c
        where c.policyId = :policyId
          and c.exposureSegmentSeq = :segmentSeq
          and c.claimStatus = :status
          and c.claimId <> :excludedClaimId
    """)
    long sumApprovedPaidOnSegment(@Param("policyId") UUID policyId,
                                  @Param("segmentSeq") int segmentSeq,
                                  @Param("status") ClaimStatus status,
                                  @Param("excludedClaimId") UUID excludedClaimId);

    @Query("""
        select c from Claim c
        where (:status is null or c.claimStatus = :status)
          and (:customerId is null or c.customerId = :customerId)
          and (:policyId is null or c.policyId = :policyId)
        order by c.createdAt desc
    """)
    Page<Claim> findAdminFiltered(@Param("status") ClaimStatus status,
                                  @Param("customerId") UUID customerId,
                                  @Param("policyId") UUID policyId,
                                  Pageable pageable);
}
