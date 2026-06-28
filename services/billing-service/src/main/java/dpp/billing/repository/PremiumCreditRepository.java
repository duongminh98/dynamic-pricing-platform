package dpp.billing.repository;

import dpp.billing.entity.CreditStatus;
import dpp.billing.entity.PremiumCredit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PremiumCreditRepository extends JpaRepository<PremiumCredit, UUID> {
    List<PremiumCredit> findByPolicyIdAndStatusInOrderByCreatedAtAsc(UUID policyId, List<CreditStatus> statuses);
    List<PremiumCredit> findByPolicyIdOrderByCreatedAtAsc(UUID policyId);
    List<PremiumCredit> findByPolicyIdAndRemainingAmountVndGreaterThan(UUID policyId, long minRemaining);
    List<PremiumCredit> findByCustomerIdAndStatusInOrderByCreatedAtAsc(UUID customerId, List<CreditStatus> statuses);
    List<PremiumCredit> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);
    Page<PremiumCredit> findByCustomerIdOrderByCreatedAtAsc(UUID customerId, Pageable pageable);
}
