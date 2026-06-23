package dpp.claims.repository;

import dpp.claims.entity.Claim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    List<Claim> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
    List<Claim> findByPolicyIdOrderByCreatedAtDesc(UUID policyId);
}
