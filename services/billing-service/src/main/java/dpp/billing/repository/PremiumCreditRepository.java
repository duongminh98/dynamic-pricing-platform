package dpp.billing.repository;

import dpp.billing.entity.CreditStatus;
import dpp.billing.entity.PremiumCredit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PremiumCreditRepository extends JpaRepository<PremiumCredit, UUID> {
    List<PremiumCredit> findByPolicyIdAndStatusInOrderByCreatedAtAsc(UUID policyId, List<CreditStatus> statuses);
    List<PremiumCredit> findByPolicyIdOrderByCreatedAtAsc(UUID policyId);
    List<PremiumCredit> findByPolicyIdAndRemainingAmountVndGreaterThan(UUID policyId, long minRemaining);
    List<PremiumCredit> findByCustomerIdAndStatusInOrderByCreatedAtAsc(UUID customerId, List<CreditStatus> statuses);
    List<PremiumCredit> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);
    Page<PremiumCredit> findByCustomerIdOrderByCreatedAtAsc(UUID customerId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(c.remainingAmountVnd), 0) FROM PremiumCredit c " +
           "WHERE c.customerId = :customerId AND c.status IN :statuses")
    long sumRemainingByCustomerIdAndStatuses(@Param("customerId") UUID customerId,
                                             @Param("statuses") List<CreditStatus> statuses);
}
